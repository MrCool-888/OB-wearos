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
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    @Test fun savedReplyRetainsTheActualTokenWithoutDiskSerialization() {
        val store = ReplyActionStore()
        val reply = SavedReply(pendingIntent(1), "openbubbles_reply")
        store.put("iMessage;-;alex@example.com", reply)

        assertTrue(store.contains("iMessage;-;alex@example.com"))
        val restored = store.get("iMessage;-;alex@example.com")
        assertNotNull(restored)
        assertEquals("openbubbles_reply", restored!!.resultKey)
        assertEquals(reply.intent, restored.intent)
        assertTrue(context.getSharedPreferences("openbubbles_wear_replies", Context.MODE_PRIVATE).all.isEmpty())
        assertNull(ReplyActionStore().get("iMessage;-;alex@example.com"))
    }

    @Test fun storeIsBoundedAndKeepsNewestThreads() {
        val store = ReplyActionStore()
        repeat(45) { index ->
            store.put("chat-$index", SavedReply(pendingIntent(index), "reply-$index"))
        }
        assertFalse(store.contains("chat-0"))
        assertFalse(store.contains("chat-4"))
        assertTrue(store.contains("chat-5"))
        assertTrue(store.contains("chat-44"))
    }

    @Test fun missingActionOnRefreshDoesNotEraseTheLastReplyToken() {
        val store = ReplyActionStore()
        val reply = SavedReply(pendingIntent(1), "reply_text")
        store.put("chat", reply)
        store.put("chat", null)
        assertEquals(reply, store.get("chat"))
        store.clear()
        assertFalse(store.contains("chat"))
    }

    @Test fun rejectingAnOlderTokenCannotEraseItsReplacement() {
        val store = ReplyActionStore()
        val old = SavedReply(pendingIntent(1), "reply_text")
        val current = SavedReply(pendingIntent(2), "reply_text")
        store.put("chat", old)
        store.put("chat", current)
        store.remove("chat", old)
        assertEquals(current, store.get("chat"))
        store.remove("chat", current)
        assertFalse(store.contains("chat"))
    }

    @Test fun contentIntentsAlsoStayInMemoryAndAreBounded() {
        val store = PendingIntentStore()
        val intent = pendingIntent(1)
        store.put("chat", intent)
        store.put("chat", null)
        assertSame(intent, store.get("chat"))
        assertNull(PendingIntentStore().get("chat"))
        repeat(40) { store.put("other-$it", pendingIntent(it + 2)) }
        assertNull(store.get("chat"))
        assertNotNull(store.get("other-39"))
        store.clear()
        assertNull(store.get("other-39"))
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
        val notification = Notification.Builder(context, "test").addAction(choiceOnly).addAction(freeForm).build()
        assertEquals("text", NotificationActions.replyAction(notification)!!.remoteInputs.single().resultKey)
        assertFalse(NotificationActions.sendReply(context, choiceOnly, "Typed reply"))
    }
}
