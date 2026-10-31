package com.openbubbles.wear

import com.openbubbles.wear.protocol.ThreadSummary
import org.junit.Assert.*
import org.junit.Test

class ConversationListTest {
    private val dm = ThreadSummary(id = "first", title = "Alex", address = "+15551234567", lastTimestamp = 1000)

    @Test fun sameRecipientHasOneRowWithoutChangingTheUnderlyingDirectory() {
        val latest = dm.copy(id = "second", address = "tel:+1 (555) 123-4567", lastTimestamp = 2000)
        val state = WatchState(conversations = listOf(dm, latest))
        assertEquals(listOf(latest), state.conversationsForDisplay)
        assertEquals(listOf(dm, latest), state.conversations)
        assertEquals(listOf(latest), state.directChats)
    }

    @Test fun unreadNativeChatWinsSoTheRowOpensTheConversationThatNeedsReading() {
        val unread = dm.copy(unread = true)
        val newerRead = dm.copy(id = "second", lastTimestamp = 2000)
        assertEquals(unread, WatchState(conversations = listOf(newerRead, unread)).conversationsForDisplay.single())
    }

    @Test fun sameNamesDifferentRecipientsGroupsAndUnknownAddressesStaySeparate() {
        val other = dm.copy(id = "other", address = "+15557654321")
        val group = dm.copy(id = "group", isGroup = true)
        val anotherGroup = group.copy(id = "another-group")
        val unknown = dm.copy(id = "unknown", address = null)
        val anotherUnknown = unknown.copy(id = "another-unknown")
        val entries = listOf(dm, other, group, anotherGroup, unknown, anotherUnknown)
        val state = WatchState(conversations = entries)
        assertEquals(entries.map { it.id }.toSet(), state.conversationsForDisplay.map { it.id }.toSet())
        assertEquals(2, state.groups.size)
    }

    @Test fun singleParticipantAndEmailFormattingIdentifyTheSameRecipient() {
        val first = dm.copy(address = null, participants = listOf("Alex@Example.com"))
        val latest = dm.copy(id = "second", address = "mailto:alex@example.com", lastTimestamp = 2000)
        assertEquals(listOf(latest), WatchState(conversations = listOf(first, latest)).conversationsForDisplay)
    }
}
