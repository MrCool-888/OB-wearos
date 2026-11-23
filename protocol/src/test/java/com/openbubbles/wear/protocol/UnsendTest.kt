package com.openbubbles.wear.protocol

import org.junit.Assert.*
import org.junit.Test

class UnsendTest {
    @Test fun ownRecentMessagesOnlyAndStrictTwoMinuteBoundary() {
        val message = WireMessage("m", "t", "Hi", 1_000, fromMe = true, canUnsend = true)
        assertTrue(message.canUnsendAt(120_999))
        assertFalse(message.canUnsendAt(121_000))
        assertFalse(message.canUnsendAt(999))
        assertFalse(message.copy(fromMe = false).canUnsendAt(1_001))
        assertFalse(message.copy(canUnsend = false).canUnsendAt(1_001))
        assertFalse(message.copy(unsent = true).canUnsendAt(1_001))
    }

    @Test fun wireDefaultsAndRoundTripDoNotEnableOldBridges() {
        val old = Wire.decode<WireMessage>("""{"id":"m","threadId":"t","text":"Hi","timestamp":1}""")!!
        assertFalse(old.canUnsend)
        assertFalse(old.unsent)
        val request = UnsendRequest(requestId = "r", threadId = "t", messageId = "m")
        assertEquals(request, Wire.decode<UnsendRequest>(Wire.encode(request)))
        val tombstone = old.copy(text = "Message unsent", unsent = true)
        assertEquals(tombstone, Wire.decode<WireMessage>(Wire.encode(tombstone)))
        assertEquals(tombstone, tombstone.mergeMediaFrom(old.copy(attachment = true, mediaId = "private-photo")))
        val partial = old.copy(hasRetractedParts = true)
        assertEquals(partial, partial.mergeMediaFrom(old.copy(attachment = true, mediaId = "retracted-photo")))
    }
}
