package com.openbubbles.wear.bridge

import android.content.Context
import android.util.Log
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread

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
     */
    fun upsertIncoming(incoming: WireThread): WireThread = synchronized(lock) {
        val existing = threads[incoming.id]
        val merged = mergeMessages(existing?.messages.orEmpty(), incoming.messages)
        val updated = incoming.copy(
            messages = merged,
            updatedAt = maxOf(incoming.updatedAt, merged.lastOrNull()?.timestamp ?: 0L)
        )
        threads.remove(updated.id)
        threads[updated.id] = updated
        trim()
        persist()
        updated
    }

    fun update(thread: WireThread) = synchronized(lock) {
        threads[thread.id] = thread
        trim()
        persist()
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
            val previous = byId[incoming.id]
            byId[incoming.id] = if (previous?.attachment == true && !incoming.attachment) {
                // Notification refreshes can omit image data temporarily. Keep the
                // photo marker rather than degrading an already-known attachment.
                incoming.copy(attachment = true)
            } else {
                incoming
            }
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
        const val MAX_THREADS = 40
        const val MAX_MESSAGES = 60
    }
}
