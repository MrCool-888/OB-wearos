package com.openbubbles.wear.bridge

import android.app.PendingIntent

/**
 * Keeps the content PendingIntent that OpenBubbles attached to each chat
 * notification. The intent is owned by OpenBubbles, so sending it opens the
 * exact conversation without knowing its chat GUID. It usually stays valid
 * after the notification is dismissed, which lets the bridge open cached
 * chats without searching the conversation list.
 *
 * Tokens live only in memory: Android cannot marshal their Binder references to
 * disk. OpenBubbles may cancel them at any time.
 */
class PendingIntentStore {
    private val intents = LinkedHashMap<String, PendingIntent>()

    @Synchronized
    fun put(threadId: String, intent: PendingIntent?) {
        if (intent == null || threadId.isBlank()) return
        intents.remove(threadId)
        intents[threadId] = intent
        if (intents.size > MAX_ENTRIES) intents.remove(intents.keys.first())
    }

    @Synchronized
    fun get(threadId: String): PendingIntent? = intents[threadId]

    @Synchronized
    fun clear() = intents.clear()

    private companion object {
        const val MAX_ENTRIES = 40
    }
}
