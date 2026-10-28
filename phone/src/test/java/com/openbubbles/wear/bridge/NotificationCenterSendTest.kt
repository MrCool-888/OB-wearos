package com.openbubbles.wear.bridge

import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.openbubbles.wear.protocol.WireThread
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class NotificationCenterSendTest {
    private lateinit var context: Context
    private var result: NotificationCenter.SendResult? = null
    private val received = mutableListOf<String>()
    private lateinit var receiver: BroadcastReceiver
    private val threadId = "chat_contact:42"

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        NotificationCenter.init(context)
        NotificationCenter.clearCache(context)
        BridgePreferences(context).relayEnabled = true
        result = null
        received.clear()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply_text")?.let {
                    received += it.toString()
                }
            }
        }
        // Robolectric does not grant AndroidX's generated signature permission.
        shadowOf(context as Application).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ContextCompat.registerReceiver(context, receiver, IntentFilter("test.openbubbles.REPLY"),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @After fun cleanup() = context.unregisterReceiver(receiver)

    private fun notification(requestCode: Int = 1): Pair<StatusBarNotification, PendingIntent> {
        val intent = PendingIntent.getBroadcast(context, requestCode,
            Intent("test.openbubbles.REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = NotificationCompat.Action.Builder(0, "Reply", intent)
            .addRemoteInput(androidx.core.app.RemoteInput.Builder("reply_text").build()).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage("Hi", 1000, Person.Builder().setName("Alex").setUri("tel:+15551234567").build())
        val notification = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Alex").setShortcutId(threadId).setStyle(style).addAction(action).build()
        val sbn = StatusBarNotification("com.openbubbles.messaging", "com.openbubbles.messaging", 1, "test",
            android.os.Process.myUid(), 0, 0, notification, android.os.Process.myUserHandle(), 1000)
        return sbn to intent
    }

    private fun send(text: String) {
        NotificationCenter.send("request-$text", threadId, text) { result = it }
        ShadowLooper.idleMainLooper()
    }

    @Test fun missingThreadIdIsRejected() {
        NotificationCenter.send("request", null, "Hello") { result = it }
        assertEquals("Open this chat in OpenBubbles on the phone first.", result!!.error)
        assertNull(result!!.threadId)
    }

    @Test fun threadWithoutLiveOrSavedReplyActionIsRejected() {
        NotificationCenter.send("request", "iMessage;-;nobody@example.com", "Hello") { result = it }
        assertTrue(result!!.error!!.contains("no reply action"))
        assertEquals("iMessage;-;nobody@example.com", result!!.threadId)
    }

    @Test fun liveReplyDeliversRemoteInputWhilePhoneRemainsLocked() {
        NotificationCenter.onNotificationPosted(notification().first)
        send("Live reply")
        assertNull(result!!.error)
        assertEquals(listOf("Live reply"), received)
        assertTrue(BridgeInfo.isPhoneLocked(context))
    }

    @Test fun removedNotificationStillAllowsTwoImmediateLockedSends() {
        val (sbn, _) = notification()
        NotificationCenter.onNotificationPosted(sbn)
        NotificationCenter.onNotificationRemoved(sbn)
        assertFalse(NotificationCenter.threadFor(threadId)!!.notificationActive)
        assertTrue(NotificationCenter.threadFor(threadId)!!.canReply)
        send("First quiet reply")
        assertNull(result!!.error)
        send("Second quiet reply")
        assertNull(result!!.error)
        assertEquals(listOf("First quiet reply", "Second quiet reply"), received)
        assertTrue(BridgeInfo.isPhoneLocked(context))
    }

    @Test fun legacyReplyAlsoUsesTheRetainedActionWithoutUnlocking() {
        val (sbn, _) = notification()
        NotificationCenter.onNotificationPosted(sbn)
        NotificationCenter.onNotificationRemoved(sbn)
        assertNull(NotificationCenter.reply(threadId, "Legacy reply"))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("Legacy reply"), received)
        assertTrue(BridgeInfo.isPhoneLocked(context))
    }

    @Test fun cancelledQuietReplyFailsAndIsNotRetriedOnUnlock() {
        val (sbn, intent) = notification()
        NotificationCenter.onNotificationPosted(sbn)
        NotificationCenter.onNotificationRemoved(sbn)
        intent.cancel()
        send("Expired reply")
        assertTrue(result!!.error!!.contains("expired"))
        assertFalse(result!!.retryable)
        assertFalse(NotificationCenter.threadFor(threadId)!!.canReply)
        assertTrue(received.isEmpty())
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        ShadowLooper.idleMainLooper()
        assertTrue(received.isEmpty())
    }

    @Test fun aNewNotificationRestoresReplyAfterALiveTokenIsCancelled() {
        val (sbn, intent) = notification()
        NotificationCenter.onNotificationPosted(sbn)
        intent.cancel()
        send("Expired live reply")
        assertNotNull(result!!.error)
        assertFalse(NotificationCenter.threadFor(threadId)!!.canReply)
        NotificationCenter.onNotificationPosted(notification(2).first)
        send("Fresh reply")
        assertNull(result!!.error)
        assertEquals(listOf("Fresh reply"), received)
    }

    @Test fun discoveryCannotStrandTheLiveOrRetainedReplyAction() {
        val (sbn, _) = notification()
        NotificationCenter.onNotificationPosted(sbn)
        val store = NotificationCenter::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(NotificationCenter) as BridgeStore
        val imported = store.importPublishedChat(WireThread(
            id = "chat_addr:+15551234567", title = "Alex", address = "+15551234567"))
        assertEquals(threadId, imported.id)
        send("After discovery")
        assertNull(result!!.error)
        NotificationCenter.onNotificationRemoved(sbn)
        send("Quiet after discovery")
        assertNull(result!!.error)
        assertEquals(listOf("After discovery", "Quiet after discovery"), received)
    }
}
