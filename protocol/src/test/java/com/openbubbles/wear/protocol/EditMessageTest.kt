package com.openbubbles.wear.protocol

import org.junit.Assert.*
import org.junit.Test

class EditMessageTest {
    @Test fun recentOwnPlainTextOnly() {
        val message = WireMessage("message", "chat", "Hello", 1_000, fromMe = true, canEdit = true)
        assertTrue(message.canEditAt(900_999))
        assertFalse(message.canEditAt(901_000))
        assertFalse(message.canEditAt(999))
        assertFalse(message.copy(fromMe = false).canEditAt(2_000))
        assertFalse(message.copy(unsent = true).canEditAt(2_000))
        assertFalse(message.copy(attachment = true).canEditAt(2_000))
    }

    @Test fun olderPayloadsKeepEditingDisabled() {
        val old = Wire.decode<WireMessage>("""{"id":"m","threadId":"t","text":"Hi","timestamp":1,"fromMe":true}""")!!
        assertFalse(old.canEditAt(2L))
        assertEquals(0L, old.editedAt)
        val request = EditMessageRequest(requestId = "request", threadId = "t", messageId = "m",
            originalText = "Hi", text = "Hello!\n👋")
        assertEquals(request, Wire.decode<EditMessageRequest>(Wire.encode(request)))
    }
}
