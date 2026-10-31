package com.openbubbles.wear.ui

import com.openbubbles.wear.protocol.WireMessage
import org.junit.Assert.*
import org.junit.Test

class ChatScrollPositionTest {
    private val first = WireMessage(id = "first", threadId = "chat", text = "Hello", timestamp = 1000)
    private val next = first.copy(id = "next", timestamp = 2000)

    @Test fun initialHistoryScrollsOnceAndIdleRefreshesDoNotMoveTheChat() {
        val position = ChatScrollPosition()
        assertFalse(position.update(emptyList(), atLatest = true, scrolling = false))
        assertTrue(position.update(listOf(first), atLatest = true, scrolling = false))
        repeat(4) {
            assertFalse(position.update(listOf(first.copy(status = "read")), atLatest = true, scrolling = false))
        }
        assertFalse(position.update(listOf(first.copy(id = "older", timestamp = 500), first),
            atLatest = true, scrolling = false))
        assertEquals(2, position.messageCount)
    }

    @Test fun newMessageFollowsOnlyWhenAlreadyNearLatestAndNotScrolling() {
        for ((atLatest, scrolling) in listOf(true to false, false to false, true to true)) {
            val position = ChatScrollPosition()
            position.update(listOf(first), atLatest = true, scrolling = false)
            assertEquals(atLatest && !scrolling,
                position.update(listOf(first, next), atLatest = atLatest, scrolling = scrolling))
            assertFalse(position.update(listOf(first, next), atLatest = true, scrolling = false))
        }
    }

    @Test fun backendSendReplacementDeletedLatestAndEqualTimestampReorderDoNotJump() {
        for (replacement in listOf(first.copy(id = "backend", timestamp = 1500),
                first.copy(id = "other", timestamp = 1000), first.copy(id = "older", timestamp = 500))) {
            val position = ChatScrollPosition()
            position.update(listOf(first), atLatest = true, scrolling = false)
            assertFalse(position.update(listOf(replacement), atLatest = true, scrolling = false))
        }
        val position = ChatScrollPosition()
        position.update(listOf(first), atLatest = true, scrolling = false)
        assertFalse(position.update(listOf(first, first.copy(id = "same-time")), atLatest = true, scrolling = false))
    }

    @Test fun trimmingOldHistoryStillFollowsNewMessagesAtTheEnd() {
        val position = ChatScrollPosition()
        position.update(listOf(first, next), atLatest = true, scrolling = false)
        assertTrue(position.update(listOf(next, next.copy(id = "third", timestamp = 3000)),
            atLatest = true, scrolling = false))
    }
}
