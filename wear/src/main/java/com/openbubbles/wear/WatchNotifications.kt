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
import com.openbubbles.wear.protocol.Protocol

/**
 * Builds the watch's own rich notification for an incoming message.
 *
 * The phone forwards eligible live alerts only while its lock screen is showing.
 * This notification adds inline text/voice reply, a mark-read action, and a deep
 * link into the cached chat. The user can turn it off in Settings.
 */
object WatchNotifications {
    private const val TAG = "OBWearNotify"
    private const val PREFS_NAME = "openbubbles_wear"
    private const val KEY_ALERTED_IDS = "alerted_backend_message_ids"
    private const val KEY_DISMISSED_AT = "notification_dismissed_at:"

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
        alertMessageId: String?,
        hideAlertPreview: Boolean
    ) {
        if (thread.id == state.visibleThreadId || !thread.unread) {
            cancel(context, thread.id)
            alertMessageId?.let { recordAlerted(context, it) }
            return
        }
        val messageId = alertMessageId ?: return
        val latest = thread.messages.firstOrNull { it.id == messageId && !it.fromMe } ?: return
        if (latest.timestamp <= prefs(context).getLong(KEY_DISMISSED_AT + thread.id, 0L)) {
            recordAlerted(context, messageId)
            return
        }
        if (wasAlerted(context, messageId)) return
        if (!state.canPostNotifications) return
        if (!areSystemNotificationsAllowed(context)) return
        if (show(context, thread, latest, state.quickReplies, hideAlertPreview)) recordAlerted(context, messageId)
    }

    private fun show(
        context: Context,
        thread: WireThread,
        latest: WireMessage,
        quickReplies: List<String>,
        hidePreview: Boolean
    ): Boolean {
        val id = notificationId(thread.id)
        val isTest = thread.id == Protocol.TEST_NOTIFICATION_THREAD_ID
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
            style.addMessage(if (hidePreview) "New message" else message.displayText, message.timestamp, sender)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                data = threadUri(thread.id)
                putExtra(Protocol.EXTRA_NOTIFICATION_THREAD, thread.id)
                putExtra(Protocol.EXTRA_NOTIFICATION_TIME, latest.timestamp)
                if (!isTest) {
                    action = WatchNavigation.ACTION_OPEN_CHAT
                    putExtra(WatchNavigation.EXTRA_THREAD_ID, thread.id)
                }
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
            .setContentText(if (hidePreview) "New message" else latest.displayText)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setDeleteIntent(PendingIntent.getBroadcast(context, id,
                Intent(context, WatchNotificationReceiver::class.java).apply {
                    action = WatchNotificationReceiver.ACTION_DISMISS
                    data = threadUri(thread.id).buildUpon().appendPath(latest.timestamp.toString()).build()
                    putExtra(Protocol.EXTRA_NOTIFICATION_THREAD, thread.id)
                    putExtra(Protocol.EXTRA_NOTIFICATION_TIME, latest.timestamp)
                }, immutableFlags()))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setNumber(1)
            .apply {
                if (!isTest) {
                    addAction(replyAction)
                    addAction(markReadAction)
                }
            }
            .extend(NotificationCompat.WearableExtender()
                .setContentIntentAvailableOffline(true))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(thread.id, id, notification)
            if (isTest) Log.i("OBWearTest", "Watch test posted")
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked for ${thread.id}", error)
            return false
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to post notification for ${thread.id}", error)
            return false
        }
        return true
    }

    fun cancel(context: Context, threadId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(threadId, notificationId(threadId))
        }
    }

    @Synchronized
    fun dismiss(context: Context, event: com.openbubbles.wear.protocol.NotificationDismissal) {
        if (!event.isValid()) return
        val key = KEY_DISMISSED_AT + event.threadId
        val watermark = maxOf(prefs(context).getLong(key, 0L), event.postedAt)
        prefs(context).edit().putLong(key, watermark).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.activeNotifications.any { it.tag == event.threadId && it.notification.`when` <= watermark })
            cancel(context, event.threadId)
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

    @Synchronized
    private fun wasAlerted(context: Context, messageId: String): Boolean = messageId in alertedIds(context)

    private fun alertedIds(context: Context): List<String> =
        prefs(context).getString(KEY_ALERTED_IDS, null)?.let {
            com.openbubbles.wear.protocol.Wire.decode<List<String>>(it)
        }.orEmpty()

    @Synchronized
    private fun recordAlerted(context: Context, messageId: String) {
        val ids = (alertedIds(context).filterNot { it == messageId } + messageId).takeLast(200)
        prefs(context).edit().putString(KEY_ALERTED_IDS, com.openbubbles.wear.protocol.Wire.encode(ids)).apply()
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
