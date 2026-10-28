package com.openbubbles.wear.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class ReplyActionStoreTest {
    private lateinit var context: Context

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("openbubbles_wear_replies", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun pendingIntent(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, ReplyActionStoreTest::class.java).setAction("reply-$id"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    @Test fun savedReplyRoundTripsThroughStorage() {
        val store = ReplyActionStore(context)
        val reply = SavedReply(pendingIntent(1), "openbubbles_reply")
        store.put("iMessage;-;alex@example.com", reply)

        assertTrue(store.contains("iMessage;-;alex@example.com"))
        val restored = ReplyActionStore(context).get("iMessage;-;alex@example.com")
        assertNotNull(restored)
        assertEquals("openbubbles_reply", restored!!.resultKey)
        assertEquals(reply.intent, restored.intent)
    }

    @Test fun storeIsBoundedAndKeepsNewestThreads() {
        val store = ReplyActionStore(context)
        repeat(45) { index ->
            store.put("chat-$index", SavedReply(pendingIntent(index), "reply-$index"))
        }
        assertFalse(store.contains("chat-0"))
        assertFalse(store.contains("chat-4"))
        assertTrue(store.contains("chat-5"))
        assertTrue(store.contains("chat-44"))
    }

    @Test fun onlyFreeFormActionsAreSaved() {
        val freeForm = Notification.Action.Builder(
            0,
            "Reply",
            pendingIntent(2)
        ).addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build()).build()
        assertEquals("text", NotificationActions.savedReply(freeForm)?.resultKey)

        val choiceOnly = Notification.Action.Builder(
            0,
            "Reply",
            pendingIntent(3)
        ).addRemoteInput(RemoteInput.Builder("choice").setAllowFreeFormInput(false).build()).build()
        assertNull(NotificationActions.savedReply(choiceOnly))
    }
}
