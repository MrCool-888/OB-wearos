package com.openbubbles.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread

/**
 * Builds the watch's own rich notification for an incoming message.
 *
 * The phone bridge already mirrors OpenBubbles' notification to the watch. This
 * notification is the companion's enhanced version: it has an inline text/voice
 * reply, a mark-read action, and a deep link straight into the cached chat. The
 * user can turn it off in Settings if the mirrored notification is enough.
 */
object WatchNotifications {
    private const val TAG = "OBWearNotify"
    private const val PREFS_NAME = "openbubbles_wear"
    private const val KEY_NOTIFIED_AT = "notified_at:"

    const val CHANNEL_ID = "messages"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New OpenBubbles messages"
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun areSystemNotificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Called after a thread update. The notification is raised only for an
     * unread incoming message that the user is not already looking at. Read
     * chats cancel their notification, and a visible chat is implicitly read.
     */
    fun syncThread(
        context: Context,
        thread: WireThread,
        state: WatchState,
        allowNotify: Boolean
    ) {
        val latest = thread.messages.lastOrNull { !it.fromMe } ?: return
        if (thread.id == state.visibleThreadId || !thread.unread) {
            cancel(context, thread.id)
            markNotified(context, thread.id, latest.timestamp)
            return
        }
        if (!allowNotify) return
        if (!state.canPostNotifications) return
        if (!areSystemNotificationsAllowed(context)) return
        if (latest.timestamp <= lastNotified(context, thread.id)) return
        show(context, thread, latest, state.quickReplies)
    }

    private fun show(
        context: Context,
        thread: WireThread,
        latest: WireMessage,
        quickReplies: List<String>
    ) {
        val id = notificationId(thread.id)
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (thread.isGroup) style.setConversationTitle(thread.title)
        style.setGroupConversation(thread.isGroup)
        thread.messages.takeLast(5).forEach { message ->
            val sender = if (message.fromMe) {
                me
            } else {
                Person.Builder()
                    .setName(message.sender?.takeIf { it.isNotBlank() } ?: thread.title)
                    .build()
            }
            style.addMessage(message.displayText, message.timestamp, sender)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                action = WatchNavigation.ACTION_OPEN_CHAT
                data = threadUri(thread.id)
                putExtra(WatchNavigation.EXTRA_THREAD_ID, thread.id)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            immutableFlags()
        )

        val remoteInput = RemoteInput.Builder(WatchNotificationReceiver.KEY_REPLY)
            .setLabel("Reply")
            .apply {
                val choices = quickReplies.take(3).filter { it.isNotBlank() }
                if (choices.isNotEmpty()) setChoices(choices.toTypedArray())
            }
            .build()

        val replyIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, WatchNotificationReceiver::class.java).apply {
                action = WatchNotificationReceiver.ACTION_REPLY
                data = threadUri(thread.id)
                putExtra(WatchNotificationReceiver.EXTRA_THREAD_ID, thread.id)
                putExtra(WatchNotificationReceiver.EXTRA_NOTIFICATION_ID, id)
            },
            mutableFlags()
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply,
            "Reply",
            replyIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val markReadIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, WatchNotificationReceiver::class.java).apply {
                action = WatchNotificationReceiver.ACTION_MARK_READ
                data = threadUri(thread.id)
                putExtra(WatchNotificationReceiver.EXTRA_THREAD_ID, thread.id)
                putExtra(WatchNotificationReceiver.EXTRA_NOTIFICATION_ID, id)
            },
            immutableFlags()
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_mark_read,
            "Mark read",
            markReadIntent
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2E6FCC.toInt())
            .setContentTitle(thread.title)
            .setContentText(latest.displayText)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setNumber(1)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(thread.id, id, notification)
        }.onFailure {
            Log.w(TAG, "Unable to post notification for ${thread.id}", it)
        }
        markNotified(context, thread.id, latest.timestamp)
    }

    fun cancel(context: Context, threadId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(threadId, notificationId(threadId))
        }
    }

    fun cancelAll(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    fun notificationId(threadId: String): Int {
        val raw = threadId.hashCode() and 0x7fffffff
        return if (raw == 0) 1 else raw
    }

    fun lastNotified(context: Context, threadId: String): Long =
        prefs(context).getLong(KEY_NOTIFIED_AT + threadId, 0L)

    @Synchronized
    fun markNotified(context: Context, threadId: String, timestamp: Long) {
        if (timestamp <= 0L) return
        val prefs = prefs(context)
        if (prefs.getLong(KEY_NOTIFIED_AT + threadId, 0L) >= timestamp) return
        prefs.edit().putLong(KEY_NOTIFIED_AT + threadId, timestamp).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun threadUri(threadId: String): Uri =
        Uri.Builder().scheme("openbubbles-wear").authority("chat").appendPath(threadId).build()

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun mutableFlags(): Int {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or mutable
    }
}
