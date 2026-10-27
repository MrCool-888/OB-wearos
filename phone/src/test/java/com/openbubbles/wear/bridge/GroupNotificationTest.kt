package com.openbubbles.wear.bridge

import android.app.Application
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class GroupNotificationTest {
    private fun parsed(group: Boolean): NotificationParser.Parsed {
        val context = RuntimeEnvironment.getApplication()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setGroupConversation(group)
            .addMessage("Hi", 1000, Person.Builder().setName("Alex").setUri("tel:+15551234567").build())
        if (group) style.setConversationTitle("Family")
        val notification = NotificationCompat.Builder(context, "test")
            .setContentTitle(if (group) "Family" else "Alex").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setShortcutId(if (group) "group/family" else "direct/alex").setStyle(style).build()
        val sbn = StatusBarNotification("com.openbubbles.messaging", "com.openbubbles.messaging", 1, "test",
            android.os.Process.myUid(), 0, 0, notification, android.os.Process.myUserHandle(), 1000)
        return NotificationParser.parse(context, sbn)!!
    }

    @Test fun groupKeepsItsConversationIdentityAndSenderWithoutSingleRecipientAddress() {
        val thread = parsed(true).thread
        assertTrue(thread.isGroup)
        assertEquals("group/family", thread.id)
        assertEquals("Family", thread.title)
        assertNull(thread.address)
        assertEquals("Alex", thread.messages.single().sender)
    }

    @Test fun groupSummaryIsNotFilteredOutOrConvertedToANewChat() {
        val summary = parsed(true).thread.toSummary()
        assertTrue(summary.isGroup)
        assertEquals("group/family", summary.id)
        assertEquals(listOf("Alex"), summary.participants)
    }

    @Test fun directContactKeepsItsAddressAndIsNotClassifiedAsGroup() {
        val thread = parsed(false).thread
        assertFalse(thread.isGroup)
        assertEquals("+15551234567", thread.address)
    }
}
