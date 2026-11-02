package com.openbubbles.wear

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openbubbles.wear.protocol.Protocol

/**
 * Handles the actions on the watch notification. Text and voice dictation both
 * arrive through the same RemoteInput, and the repository decides whether the
 * reply goes out immediately or into the offline outbox.
 */
class WatchNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            WatchRepository.attach(context)
            val pending = goAsync()
            val task = WatchRepository.dismissNotification(intent.getStringExtra(Protocol.EXTRA_NOTIFICATION_THREAD),
                intent.getLongExtra(Protocol.EXTRA_NOTIFICATION_TIME, 0L))
            if (task == null) pending?.finish() else
                com.google.android.gms.tasks.Tasks.withTimeout(task, 9L, java.util.concurrent.TimeUnit.SECONDS)
                    .addOnCompleteListener { pending?.finish() }
            return
        }
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: return
        WatchRepository.attach(context)
        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)
                    ?.toString()
                    ?.trim()
                if (!reply.isNullOrEmpty()) {
                    if (!WatchRepository.sendReply(threadId, reply)) return
                    WatchRepository.markReadLocally(threadId)
                }
            }

            ACTION_MARK_READ -> {
                WatchRepository.markReadLocally(threadId)
                WatchRepository.markRead(threadId)
            }
        }
        WatchNotifications.cancel(context, threadId)
    }

    companion object {
        const val ACTION_REPLY = "com.openbubbles.wear.action.REPLY"
        const val ACTION_DISMISS = "com.openbubbles.wear.action.DISMISS_NOTIFICATION"
        const val ACTION_MARK_READ = "com.openbubbles.wear.action.MARK_READ"
        const val EXTRA_THREAD_ID = "com.openbubbles.wear.extra.THREAD_ID"
        const val EXTRA_NOTIFICATION_ID = "com.openbubbles.wear.extra.NOTIFICATION_ID"
        const val KEY_REPLY = "openbubbles_wear_notification_reply"
    }
}
