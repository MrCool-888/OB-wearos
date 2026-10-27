package com.openbubbles.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun threadDataPathRoundTripsAwkwardIds() {
        val ids = listOf(
            "iMessage;-;alice@example.com",
            "iMessage;+;chat1234567890",
            "com.bluebubbles.new_messages.iMessage;-;+15551234567",
            "group/with spaces & symbols?",
            "emoji-💬-thread"
        )
        ids.forEach { id ->
            val path = Protocol.threadDataPath(id)
            assertTrue(path.startsWith(Protocol.DATA_THREAD_PREFIX))
            assertEquals(id, Protocol.threadIdFromDataPath(path))
        }
    }

    @Test
    fun threadDataPathDoesNotContainSlashInEncodedPart() {
        val path = Protocol.threadDataPath("iMessage;-;alice/example")
        val encoded = path.removePrefix(Protocol.DATA_THREAD_PREFIX)
        assertTrue(encoded.isNotEmpty())
        assertTrue(!encoded.contains('/'))
    }

    @Test
    fun threadIdFromDataPathRejectsOtherPaths() {
        assertNull(Protocol.threadIdFromDataPath("/something/else"))
        assertNull(Protocol.threadIdFromDataPath(Protocol.DATA_THREAD_PREFIX))
    }

    @Test
    fun syncPayloadRoundTrips() {
        val thread = WireThread(
            id = "thread-1",
            title = "Alice",
            messages = listOf(
                WireMessage(
                    id = "m1",
                    threadId = "thread-1",
                    text = "Hello",
                    timestamp = 1234L,
                    sender = "Alice"
                ),
                WireMessage(
                    id = "m2",
                    threadId = "thread-1",
                    text = "Hi!",
                    timestamp = 1235L,
                    fromMe = true,
                    status = "sent"
                )
            ),
            unread = true,
            notificationActive = true,
            canReply = true,
            canMarkRead = true,
            updatedAt = 1235L
        )
        val payload = SyncPayload(
            generatedAt = 999L,
            phoneName = "Pixel",
            bridgeVersion = "0.1.0",
            notificationAccess = true,
            conversations = listOf(thread.toSummary())
        )

        val decoded = Wire.decode<SyncPayload>(Wire.encode(payload))
        assertNotNull(decoded)
        assertEquals(payload, decoded)
        assertEquals("Hi!", decoded!!.conversations.single().lastPreview)
    }

    @Test
    fun updatePayloadRoundTripsAndIgnoresUnknownFields() {
        val thread = WireThread(
            id = "t",
            title = "Bob",
            messages = listOf(
                WireMessage("m", "t", "Yo", 42L, sender = "Bob")
            ),
            unread = true,
            notificationActive = true,
            canReply = true,
            canMarkRead = true,
            updatedAt = 42L
        )
        val json = Wire.encode(UpdatePayload(thread = thread))
            .dropLast(1) + ",\"futureField\":\"ignored\"}"
        val decoded = Wire.decode<UpdatePayload>(json)
        assertEquals(thread, decoded?.thread)
    }

    @Test
    fun messageIdsAreStableAndSensitiveToContent() {
        val a = Protocol.messageId("t", 100L, "Alice", "Hello")
        val b = Protocol.messageId("t", 100L, "Alice", "Hello")
        val c = Protocol.messageId("t", 100L, "Alice", "Hello!")
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun summaryDerivesLatestMessageAndPreview() {
        val thread = WireThread(
            id = "t",
            title = "Group",
            isGroup = true,
            messages = listOf(
                WireMessage("a", "t", "older", 10L, sender = "A"),
                WireMessage("b", "t", "", 20L, sender = "B", attachment = true)
            ),
            updatedAt = 5L
        )
        val summary = thread.toSummary()
        assertEquals(20L, summary.lastTimestamp)
        assertEquals("Photo", summary.lastPreview)
        assertEquals(2, summary.messageCount)
    }

    @Test
    fun defaultQuickRepliesAreNotSerializedAway() {
        val pong = PongPayload()
        val decoded = Wire.decode<PongPayload>(Wire.encode(pong))
        assertEquals(Protocol.DEFAULT_QUICK_REPLIES, decoded?.quickReplies)
    }
}
