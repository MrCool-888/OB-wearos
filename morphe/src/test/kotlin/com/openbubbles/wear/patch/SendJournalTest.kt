package com.openbubbles.wear.patch

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32], application = Application::class)
class SendJournalTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("journal", Context.MODE_PRIVATE)
    private fun request(id: String = "request") = JSONObject().put("requestId", id)
        .put("threadId", "chat_iMessage;-;+15551234567").put("text", "Hello")

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun replayReturnsTheRealMessageWithoutRepeatingTheSend() {
        var sends = 0
        val send = SendJournal.Send {
            sends++
            JSONObject().put("ok", true).put("message", JSONObject().put("id", "backend-guid"))
                .put("thread", JSONObject().put("messages", "large history"))
        }
        assertTrue(SendJournal.run(prefs, request(), send).has("thread"))
        val replay = SendJournal.run(prefs, request(), send)
        assertTrue(replay.getBoolean("ok"))
        assertEquals("backend-guid", replay.getJSONObject("message").getString("id"))
        assertFalse(replay.has("thread"))
        assertEquals(1, sends)
    }

    @Test fun interruptedSendIsNotRepeatedAfterTheProcessLosesItsResult() {
        assertThrows(IllegalStateException::class.java) {
            SendJournal.run(prefs, request()) { throw IllegalStateException("lost connection") }
        }
        val replay = SendJournal.run(prefs, request()) { fail("A send must not be repeated"); JSONObject() }
        assertFalse(replay.getBoolean("ok"))
        assertTrue(replay.getString("error").contains("interrupted"))
    }

    @Test fun reusedIdCannotConfirmADifferentMessage() {
        SendJournal.run(prefs, request()) { JSONObject().put("ok", true) }
        assertThrows(IllegalArgumentException::class.java) {
            SendJournal.run(prefs, request().put("text", "Different")) { JSONObject().put("ok", true) }
        }
    }

    @Test fun invalidIdIsRejectedBeforeASideEffect() {
        for (id in listOf("", "a".repeat(129), "comma,id")) {
            assertThrows(IllegalArgumentException::class.java) {
                SendJournal.run(prefs, request(id)) { fail("Invalid send ran"); JSONObject() }
            }
        }
    }

    @Test fun serviceRejectsWrongApiBeforeCallingTheBackend() {
        val service = org.robolectric.Robolectric.buildService(CompanionService::class.java).get()
        val result = JSONObject(service.dispatch("""{"apiVersion":99,"op":"send"}"""))
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("API"))
    }
}
