package com.openbubbles.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * Debug-only receiver behind the fake notification's Reply and Mark as read
 * pending intents. It mirrors what OpenBubbles does: a reply is appended to the
 * notification history, mark-read dismisses the notification.
 */
class FakeOpenBubblesActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(FakeOpenBubblesNotifications.EXTRA_CHAT) ?: return
        val chat = FakeChatStore.chat(chatId) ?: return
        when (intent.action) {
            FakeOpenBubblesNotifications.ACTION_REPLY -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val text = results
                    ?.getCharSequence(FakeOpenBubblesNotifications.REPLY_TEXT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) {
                    Log.w(FakeOpenBubblesNotifications.TAG, "Reply action had no RemoteInput result")
                    return
                }
                FakeChatStore.appendReply(chatId, text)
                FakeChatStore.markRead(chatId)
                FakeOpenBubblesNotifications.post(context, chat)
                Log.i(FakeOpenBubblesNotifications.TAG, "Fake reply received for $chatId: $text")
                Toast.makeText(context, "Fake reply sent", Toast.LENGTH_SHORT).show()
            }

            FakeOpenBubblesNotifications.ACTION_READ -> {
                FakeChatStore.markRead(chatId)
                NotificationManagerCompat.from(context)
                    .cancel(FakeOpenBubblesNotifications.notificationId(chatId))
                Log.i(FakeOpenBubblesNotifications.TAG, "Fake mark-read received for $chatId")
                Toast.makeText(context, "Fake chat marked read", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
