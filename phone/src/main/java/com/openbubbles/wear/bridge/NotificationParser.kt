package com.openbubbles.wear.bridge

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread

/**
 * Reads OpenBubbles' MessagingStyle notification. The notification contains the
 * per-conversation history, sender names, optional image attachment URIs and the
 * mutable reply / mark-read actions that the watch ultimately invokes.
 */
object NotificationParser {
    private const val TAG = "OBWearParser"
    private const val MAX_TEXT_LENGTH = 1200

    data class Parsed(
        val thread: WireThread,
        val hasReply: Boolean,
        val hasMarkRead: Boolean,
        val hasContentIntent: Boolean
    )

    fun parse(context: Context, sbn: StatusBarNotification): Parsed? {
        if (!BridgeInfo.isOpenBubblesPackage(sbn.packageName)) {
            Log.d(TAG, "skip package ${sbn.packageName}")
            return null
        }
        val notification = sbn.notification ?: return null
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.onFailure {
            Log.w(TAG, "Unable to read MessagingStyle for ${sbn.key}", it)
        }.getOrNull() ?: run {
            Log.d(TAG, "No MessagingStyle for ${sbn.key}")
            return null
        }

        val threadId = notification.shortcutId
            ?.takeIf { it.isNotBlank() }
            ?: sbn.key
        if (threadId.isBlank()) return null

        val groupTitle = style.conversationTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val notificationTitle = notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val title = groupTitle ?: notificationTitle ?: "Conversation"
        val isGroup = style.isGroupConversation

        val drafts = buildDrafts(sbn, style)
        val messages = assignIds(threadId, mergeCaptionAndImage(drafts))
        val participants = participants(isGroup, title, messages)

        val reply = NotificationActions.replyAction(notification)
        val markRead = NotificationActions.markReadAction(notification)

        val thread = WireThread(
            id = threadId,
            title = title,
            isGroup = isGroup,
            participants = participants,
            messages = messages,
            unread = false,
            notificationActive = true,
            canReply = reply != null,
            canMarkRead = markRead != null,
            updatedAt = messages.lastOrNull()?.timestamp ?: sbn.postTime
        )
        return Parsed(
            thread = thread,
            hasReply = reply != null,
            hasMarkRead = markRead != null,
            hasContentIntent = notification.contentIntent != null
        )
    }

    private data class Draft(
        val text: String,
        val timestamp: Long,
        val sender: String?,
        val fromMe: Boolean,
        val attachment: Boolean
    )

    private fun buildDrafts(sbn: StatusBarNotification, style: NotificationCompat.MessagingStyle): List<Draft> {
        val fallbackTimestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        return style.messages.map { message ->
            val text = cleanText(message.text)
            val dataUri = runCatching { message.dataUri }.getOrNull()
            val person = message.person
            val senderName = person
                ?.name
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: message.sender
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val fromMe = person == null && message.sender == null
            Draft(
                text = text,
                timestamp = message.timestamp.takeIf { it > 0 } ?: fallbackTimestamp,
                sender = if (fromMe) null else (senderName ?: "Unknown"),
                fromMe = fromMe,
                attachment = dataUri != null
            )
        }.sortedBy { it.timestamp }
    }

    /**
     * OpenBubbles posts a caption and an image as two entries with the same
     * timestamp. The watch gets one compact message instead.
     */
    private fun mergeCaptionAndImage(drafts: List<Draft>): List<Draft> {
        val merged = mutableListOf<Draft>()
        for (draft in drafts) {
            val previous = merged.lastOrNull()
            if (draft.attachment && draft.text.isEmpty() && previous != null &&
                !previous.attachment && previous.timestamp == draft.timestamp &&
                previous.sender == draft.sender
            ) {
                merged[merged.lastIndex] = previous.copy(attachment = true)
            } else {
                merged += draft
            }
        }
        return merged
    }

    private fun assignIds(threadId: String, drafts: List<Draft>): List<WireMessage> {
        val counts = mutableMapOf<String, Int>()
        return drafts.map { draft ->
            val base = Protocol.messageId(
                threadId = threadId,
                timestamp = draft.timestamp,
                sender = draft.sender,
                text = draft.text
            )
            val seen = (counts[base] ?: 0) + 1
            counts[base] = seen
            val id = if (seen == 1) base else "$base-$seen"
            WireMessage(
                id = id,
                threadId = threadId,
                text = draft.text,
                timestamp = draft.timestamp,
                sender = draft.sender,
                fromMe = draft.fromMe,
                attachment = draft.attachment
            )
        }
    }

    private fun participants(
        isGroup: Boolean,
        title: String,
        messages: List<WireMessage>
    ): List<String> {
        if (!isGroup) return listOf(title)
        val senders = messages
            .asSequence()
            .filterNot { it.fromMe }
            .mapNotNull { it.sender?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        if (senders.isNotEmpty()) return senders
        return title
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(title) }
    }

    private fun cleanText(text: CharSequence?): String {
        val raw = text?.toString().orEmpty()
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_TEXT_LENGTH) {
            trimmed.take(MAX_TEXT_LENGTH - 1) + "…"
        } else {
            trimmed
        }
    }
}
