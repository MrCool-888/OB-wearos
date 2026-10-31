package com.openbubbles.wear.ui

import com.openbubbles.wear.protocol.WireMessage

/** Follow appended messages, never history refreshes or replacement send IDs. */
internal class ChatScrollPosition {
    private var latest: WireMessage? = null
    var messageCount: Int = 0
        private set

    fun update(messages: List<WireMessage>, atLatest: Boolean, scrolling: Boolean): Boolean {
        val next = messages.lastOrNull() ?: return false
        val previous = latest
        latest = next
        messageCount = messages.size
        if (scrolling) return false
        return previous == null || atLatest && next.id != previous.id &&
            next.timestamp > previous.timestamp && messages.any { it.id == previous.id }
    }
}
