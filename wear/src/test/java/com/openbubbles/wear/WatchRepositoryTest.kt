package com.openbubbles.wear

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.openbubbles.wear.protocol.ActionResult
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class WatchRepositoryTest {
    private lateinit var context: Context
    private lateinit var state: MutableStateFlow<WatchState>
    private lateinit var pending: MutableMap<String, String>
    private val local = WireMessage(id = "local-request", threadId = "chat", text = "Hello",
        timestamp = 1000, fromMe = true, status = "sending")

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("openbubbles_wear", Context.MODE_PRIVATE).edit().clear().commit()
        fun field(name: String) = WatchRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("initialized").setBoolean(null, true)
        field("appContext").set(null, context)
        field("queueFlushing").setBoolean(null, false)
        @Suppress("UNCHECKED_CAST")
        state = field("_state").get(null) as MutableStateFlow<WatchState>
        @Suppress("UNCHECKED_CAST")
        pending = field("pendingSends").get(null) as MutableMap<String, String>
        pending.clear()
        state.value = WatchState(loading = false, relayEnabled = false,
            threads = mapOf("chat" to WireThread(id = "chat", title = "Alex", messages = listOf(local))),
            queuedSends = listOf(QueuedSend("request", "chat", "Hello")), sendingThreads = setOf("chat"))
        pending["request"] = "chat"
        WatchNotifications.createChannel(context)
    }

    private fun status() = state.value.threads.getValue("chat").messages.single().status
    private fun confirm() = WatchRepository.onActionResult(ActionResult(
        requestId = "request", action = Protocol.ACTION_SEND, ok = true, threadId = "chat"))

    @Test fun fastResultCannotBeOverwrittenByTransportAcceptance() {
        confirm()
        WatchRepository.onSendDispatched("request", "chat", local.id, true)
        assertEquals("sent", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(state.value.sendingThreads.isEmpty())
    }

    @Test fun fastResultCannotBeRequeuedByLateTransportFailure() {
        confirm()
        WatchRepository.onSendDispatched("request", "chat", local.id, false)
        assertEquals("sent", status())
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun transportFailureKeepsMessageQueued() {
        WatchRepository.onSendDispatched("request", "chat", local.id, false)
        assertEquals("queued", status())
        assertEquals(1, state.value.queuedCount)
        assertFalse(pending.containsKey("request"))
        assertTrue(state.value.sendingThreads.isEmpty())
    }

    @Test fun fullOutboxRejectsNewSendWithoutDroppingOldMessages() {
        val queue = List(100) { QueuedSend("request-$it", "chat", "message-$it") }
        state.value = state.value.copy(relayEnabled = true, sendingThreads = emptySet(), queuedSends = queue)
        assertFalse(WatchRepository.sendReply("chat", "New message"))
        assertEquals(queue, state.value.queuedSends)
        assertTrue(state.value.error!!.contains("full"))
    }

    @Test fun pausedRelayDoesNotFlushOutbox() {
        pending.clear()
        WatchRepository.flushQueue(manual = true)
        assertEquals(1, state.value.queuedCount)
        assertTrue(pending.isEmpty())
    }

    @Test fun unsentIdenticalTextDoesNotDisappearOnThreadRefresh() {
        val queued = local.copy(status = "queued")
        state.value = state.value.copy(threads = mapOf("chat" to WireThread(id = "chat", title = "Alex", messages = listOf(queued))))
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", messages = listOf(
            local.copy(id = "old-message", timestamp = 900, status = "sent"))), connected = false)
        assertEquals(setOf("old-message", local.id), state.value.threads.getValue("chat").messages.map { it.id }.toSet())
    }

    @Test fun syncKeepsOfflineNewConversationVisible() {
        val id = "new:+15551234567"
        val thread = WireThread(id = id, title = "Alex", address = "+15551234567", messages = listOf(local.copy(threadId = id)))
        state.value = state.value.copy(threads = mapOf(id to thread),
            queuedSends = listOf(QueuedSend("request", id, "Hello")))
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "1.3.0",
            notificationAccess = true, conversations = emptyList(), relayEnabled = false), fromCache = true)
        assertEquals(id, state.value.conversations.single().id)
        assertTrue(state.value.threads.containsKey(id))
    }

    @Test fun newConversationMigrationKeepsQueuedMessageAndRemapsOutbox() {
        val oldId = "new:+15551234567"
        val old = WireThread(id = oldId, title = "Alex", address = "+15551234567",
            messages = listOf(local.copy(threadId = oldId, status = "queued")))
        state.value = state.value.copy(threads = mapOf(oldId to old),
            queuedSends = listOf(QueuedSend("request", oldId, "Hello")), sendingThreads = setOf(oldId))
        pending["request"] = oldId
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", address = old.address), connected = false)
        assertFalse(state.value.threads.containsKey(oldId))
        assertEquals("chat", state.value.queuedSends.single().threadId)
        assertEquals("chat", state.value.threads.getValue("chat").messages.single().threadId)
        assertEquals("chat", pending["request"])
    }

    @Test fun hashCollisionCannotRedirectReplyOrCancelAnotherChat() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        for (id in listOf("Aa", "BB")) {
            val incoming = WireThread(id = id, title = id, unread = true, canReply = true,
                messages = listOf(local.copy(id = "incoming-$id", threadId = id, fromMe = false)))
            WatchNotifications.syncThread(context, incoming, WatchState(), allowNotify = true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, manager.activeNotifications.size)
        for (posted in manager.activeNotifications) {
            val reply = posted.notification.actions.first().actionIntent
            assertEquals(posted.tag, shadowOf(reply).savedIntent.getStringExtra(WatchNotificationReceiver.EXTRA_THREAD_ID))
        }
        WatchNotifications.cancel(context, "Aa")
        assertEquals("BB", manager.activeNotifications.single().tag)
    }

    @Test fun summaryOnlyConversationCanBeMarkedRead() {
        val summary = WireThread(id = "summary", title = "Alex", unread = true).toSummary()
        state.value = state.value.copy(threads = emptyMap(), conversations = listOf(summary))
        WatchRepository.markReadLocally("summary")
        assertFalse(state.value.conversations.single().unread)
    }

    @Test fun visibleConversationDoesNotKeepAnUnreadBadge() {
        WatchRepository.setVisibleThread("chat")
        val incoming = local.copy(id = "incoming", fromMe = false, timestamp = System.currentTimeMillis() + 1000)
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", unread = true, messages = listOf(incoming)), connected = false)
        assertFalse(state.value.threads.getValue("chat").unread)
        assertFalse(state.value.conversations.single().unread)
    }

    @Test fun leavingConversationAllowsUnreadUpdatesAgain() {
        WatchRepository.setVisibleThread("chat")
        WatchRepository.setVisibleThread(null)
        val incoming = local.copy(id = "incoming", fromMe = false, timestamp = System.currentTimeMillis() + 1000)
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", unread = true, messages = listOf(incoming)), connected = false)
        assertTrue(state.value.threads.getValue("chat").unread)
    }
}
