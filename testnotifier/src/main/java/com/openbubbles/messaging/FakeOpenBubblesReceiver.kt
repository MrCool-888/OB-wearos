package com.openbubbles.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Debug-only receiver that posts OpenBubbles-shaped MessagingStyle
 * notifications. It lets us exercise notification parsing, the watch relay and
 * the mutable reply / mark-read actions on an emulator without the real app.
 *
 * adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
 *     -a com.openbubbles.messaging.DEBUG_POST --es chat maya
 */
class FakeOpenBubblesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("mode") == "clear") {
            FakeChatStore.allChats().forEach { chat ->
                NotificationManagerCompat.from(context)
                    .cancel(FakeOpenBubblesNotifications.notificationId(chat.id))
            }
            FakeChatStore.reset()
            Toast.makeText(context, "Fake notifications cleared", Toast.LENGTH_SHORT).show()
            return
        }

        val requested = intent.getStringExtra("chat")?.lowercase()
        val chats = when (requested) {
            null, "all" -> FakeChatStore.allChats()
            "maya" -> listOfNotNull(FakeChatStore.chat("fake-maya"))
            "family" -> listOfNotNull(FakeChatStore.chat("fake-family"))
            else -> emptyList()
        }
        if (chats.isEmpty()) {
            Log.w(FakeOpenBubblesNotifications.TAG, "No fake chat matched $requested")
            return
        }
        chats.forEach { FakeOpenBubblesNotifications.post(context, it) }
        Toast.makeText(context, "Fake notification posted", Toast.LENGTH_SHORT).show()
    }
}

internal object FakeOpenBubblesNotifications {
    const val TAG = "OBWearFake"
    const val ACTION_REPLY = "com.openbubbles.wear.bridge.FAKE_REPLY"
    const val ACTION_READ = "com.openbubbles.wear.bridge.FAKE_READ"
    const val CHANNEL_ID = "fake_openbubbles"
    const val REPLY_TEXT_KEY = "reply_text"
    const val EXTRA_CHAT = "chat"
    private const val REQUEST_REPLY = 41
    private const val REQUEST_READ = 42
    private const val REQUEST_CONTENT = 43

    fun notificationId(chatId: String): Int =
        if (chatId == "fake-family") 9002 else 9001

    fun post(context: Context, chat: FakeChatStore.Chat) {
        ensureChannel(context)
        ensureShortcut(context, chat)
        val messages = FakeChatStore.initialMessages(chat.id)
        val style = NotificationCompat.MessagingStyle(FakeChatStore.person("Me"))
            .setConversationTitle(if (chat.isGroup) chat.title else null)
            .setGroupConversation(chat.isGroup)
        messages.forEach { message ->
            val entry = FakeMessage.create(
                message.text,
                message.timestamp,
                FakeChatStore.personOrNull(message.sender)
            )
            if (message.attachment) {
                entry.setData("image/jpeg", Uri.parse("content://fake.openbubbles/photo.jpg"))
            }
            style.addMessage(entry)
        }
        // A photo-only entry for the last incoming message, like OpenBubbles.
        // Keeping it on the same message across updates matters: the bridge
        // derives message IDs from content, not from transient attachment state.
        if (!chat.isGroup) {
            val last = messages.lastOrNull { it.sender != null }
            if (last != null) {
                val photo = FakeMessage.create(
                    "",
                    last.timestamp,
                    FakeChatStore.personOrNull(last.sender)
                )
                photo.setData("image/jpeg", Uri.parse("content://fake.openbubbles/photo.jpg"))
                style.addMessage(photo)
            }
        }

        val requestBase = chat.id.hashCode() and 0x00ffffff
        val replyIntent = PendingIntent.getBroadcast(
            context,
            requestBase,
            Intent(context, FakeOpenBubblesActionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_CHAT, chat.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyIntent
        ).addRemoteInput(
            RemoteInput.Builder(REPLY_TEXT_KEY)
                .setLabel("Reply")
                .setAllowFreeFormInput(true)
                .build()
        ).build()

        val readIntent = PendingIntent.getBroadcast(
            context,
            requestBase + 1,
            Intent(context, FakeOpenBubblesActionReceiver::class.java)
                .setAction(ACTION_READ)
                .putExtra(EXTRA_CHAT, chat.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            readIntent
        ).build()

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, FakeMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val latest = messages.lastOrNull()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(chat.title)
            .setContentText(latest?.text?.ifBlank { "Photo" } ?: "New message")
            .setStyle(style)
            .setShortcutId(chat.id)
            .setWhen(latest?.timestamp ?: System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(replyAction)
            .addAction(readAction)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(chat.id), notification)
        Log.i(TAG, "Posted fake notification for ${chat.id} with ${messages.size} messages")
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Fake OpenBubbles messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Debug notifications used to test the Wear bridge"
            }
        )
    }

    private fun ensureShortcut(context: Context, chat: FakeChatStore.Chat) {
        val person = FakeChatStore.person(chat.title)
        val shortcut = ShortcutInfoCompat.Builder(context, chat.id)
            .setShortLabel(chat.title)
            .setLongLabel(chat.title)
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.sym_action_chat))
            .setIntent(
                Intent(context, FakeMainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
            .setLongLived(true)
            .setLocusId(LocusIdCompat(chat.id))
            .setCategories(setOf("android.shortcut.conversation"))
            .setIsConversation()
            .setPerson(person)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
            .onFailure { Log.w(TAG, "Unable to publish fake shortcut ${chat.id}", it) }
    }
}
