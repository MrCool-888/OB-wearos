package com.openbubbles.wear.bridge

import android.content.Context
import android.util.Log
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import kotlin.math.abs

/**
 * Durable, notification-derived conversation cache. OpenBubbles does not expose a
 * readable database to us, so the notification's own MessagingStyle history is the
 * source for the watch.
 */
class BridgeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var threads: LinkedHashMap<String, WireThread> = load()

    fun snapshot(): List<WireThread> = synchronized(lock) {
        threads.values.sortedByDescending { it.lastActivity() }
    }

    fun get(threadId: String): WireThread? = synchronized(lock) { threads[threadId] }

    /**
     * Adds a notification-derived thread, keeping older messages that have already
     * scrolled out of the notification's history.
     *
     * [mergeFromId] is used when a conversation started from a raw address later
     * receives its first real notification. The temporary thread's messages are
     * folded into the notification's shortcut-based thread and the temp entry is
     * removed.
     */
    fun upsertIncoming(incoming: WireThread, mergeFromId: String? = null): WireThread =
        synchronized(lock) {
            val existing = threads[incoming.id]
            val legacy = mergeFromId
                ?.takeIf { it != incoming.id }
                ?.let { threads[it] }
            val oldMessages = mergeMessages(legacy?.messages.orEmpty(), existing?.messages.orEmpty())
            val merged = mergeMessages(oldMessages, incoming.messages)
            val updated = incoming.copy(
                messages = merged,
                address = incoming.address ?: existing?.address ?: legacy?.address,
                updatedAt = maxOf(incoming.updatedAt, merged.lastOrNull()?.timestamp ?: 0L)
            )
            if (mergeFromId != null && legacy != null) threads.remove(mergeFromId)
            threads.remove(updated.id)
            threads[updated.id] = updated
            trim()
            persist()
            updated
        }

    /**
     * Merges messages recovered by UI automation (or an optimistic automation
     * send) into a thread. A fallback thread is created for a brand-new address
     * that has never produced a notification.
     */
    fun mergeHistory(
        threadId: String,
        messages: List<WireMessage>,
        fallback: WireThread? = null
    ): WireThread? = synchronized(lock) {
        val existing = threads[threadId]
            ?: fallback?.copy(id = threadId, messages = emptyList())
            ?: return@synchronized null
        val merged = mergeMessages(existing.messages, messages)
        val updated = existing.copy(
            title = existing.title.ifBlank { fallback?.title ?: "Conversation" },
            address = existing.address ?: fallback?.address,
            messages = merged,
            updatedAt = maxOf(
                existing.updatedAt,
                fallback?.updatedAt ?: 0L,
                merged.lastOrNull()?.timestamp ?: 0L
            )
        )
        threads.remove(threadId)
        threads[threadId] = updated
        trim()
        persist()
        updated
    }

    fun update(thread: WireThread) = synchronized(lock) {
        threads[thread.id] = thread
        trim()
        persist()
    }

    fun importPublishedGroup(group: WireThread): WireThread = synchronized(lock) {
        require(group.isGroup && com.openbubbles.wear.protocol.Protocol.isGroupGuid(group.id))
        val existing = threads[group.id]
        val updated = (existing ?: group).copy(title = group.title, isGroup = true, address = null,
            updatedAt = maxOf(existing?.updatedAt ?: 0L, System.currentTimeMillis()))
        threads[updated.id] = updated
        trim()
        persist()
        updated
    }

    fun markRemoved(threadId: String) = synchronized(lock) {
        val existing = threads[threadId] ?: return@synchronized
        threads[threadId] = existing.copy(
            notificationActive = false,
            canReply = false,
            canMarkRead = false,
            unread = false
        )
        persist()
    }

    fun markRead(threadId: String) = synchronized(lock) {
        val existing = threads[threadId] ?: return@synchronized
        threads[threadId] = existing.copy(unread = false)
        persist()
    }

    fun clear() = synchronized(lock) {
        threads = linkedMapOf()
        prefs.edit().remove(KEY_THREADS).apply()
    }

    private fun mergeMessages(old: List<WireMessage>, new: List<WireMessage>): List<WireMessage> {
        val byId = LinkedHashMap<String, WireMessage>(old.size + new.size)
        old.forEach { byId[it.id] = it }
        new.forEach { incoming ->
            if (incoming.fromMe) {
                // An optimistic accessibility send is replaced by the real
                // message when the next notification or history scrape sees it.
                val optimistic = byId.values.firstOrNull { candidate ->
                    candidate.id.startsWith(AUTO_MESSAGE_PREFIX) &&
                        candidate.fromMe &&
                        candidate.text == incoming.text &&
                        abs(candidate.timestamp - incoming.timestamp) <= AUTO_MATCH_WINDOW_MS
                }
                if (optimistic != null) byId.remove(optimistic.id)
            }
            val previous = byId[incoming.id]
            byId[incoming.id] = previous?.let(incoming::mergeMediaFrom) ?: incoming
        }
        return byId.values
            .sortedBy { it.timestamp }
            .takeLast(MAX_MESSAGES)
    }

    private fun trim() {
        while (threads.size > MAX_THREADS) {
            val oldest = threads.values.minByOrNull { it.lastActivity() } ?: break
            threads.remove(oldest.id)
        }
        threads.values.forEach { thread ->
            if (thread.messages.size > MAX_MESSAGES) {
                threads[thread.id] = thread.copy(messages = thread.messages.takeLast(MAX_MESSAGES))
            }
        }
    }

    private fun load(): LinkedHashMap<String, WireThread> {
        val raw = prefs.getString(KEY_THREADS, null) ?: return linkedMapOf()
        val decoded = Wire.decode<List<WireThread>>(raw) ?: return linkedMapOf()
        val map = LinkedHashMap<String, WireThread>()
        decoded
            .sortedByDescending { it.lastActivity() }
            .take(MAX_THREADS)
            .forEach { map[it.id] = it }
        return map
    }

    private fun persist() {
        val list = threads.values
            .sortedByDescending { it.lastActivity() }
            .take(MAX_THREADS)
        runCatching {
            prefs.edit().putString(KEY_THREADS, Wire.encode(list)).apply()
        }.onFailure {
            Log.w(TAG, "Unable to persist bridge cache", it)
        }
    }

    companion object {
        private const val TAG = "OBWearStore"
        private const val PREFS_NAME = "openbubbles_wear_store"
        private const val KEY_THREADS = "threads"
        const val AUTO_MESSAGE_PREFIX = "auto-"
        private const val AUTO_MATCH_WINDOW_MS = 2 * 60_000L
        const val MAX_THREADS = 40
        const val MAX_MESSAGES = 240
    }
}
