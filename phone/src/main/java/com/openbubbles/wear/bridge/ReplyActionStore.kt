package com.openbubbles.wear.bridge

import android.app.PendingIntent

/** The pieces of a notification reply action that are needed to send text. */
data class SavedReply(
    val intent: PendingIntent,
    val resultKey: String
)

/**
 * Keeps reply tokens after notification removal, while this process lives.
 * shortcut: Android cannot persist PendingIntent Binder tokens to disk; a process
 * restart needs an active notification, or a future supported sending integration.
 */
class ReplyActionStore {
    private val replies = LinkedHashMap<String, SavedReply>()

    @Synchronized
    fun put(threadId: String, reply: SavedReply?) {
        if (reply == null || threadId.isBlank()) return
        replies.remove(threadId)
        replies[threadId] = reply
        if (replies.size > MAX_ENTRIES) replies.remove(replies.keys.first())
    }

    @Synchronized
    fun get(threadId: String): SavedReply? = replies[threadId]

    @Synchronized
    fun contains(threadId: String): Boolean = replies.containsKey(threadId)

    @Synchronized
    fun remove(threadId: String, reply: SavedReply) {
        if (replies[threadId] == reply) replies.remove(threadId)
    }

    @Synchronized
    fun clear() = replies.clear()

    private companion object {
        const val MAX_ENTRIES = 40
    }
}
