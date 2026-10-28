package com.openbubbles.wear.protocol

import kotlinx.serialization.Serializable

@Serializable
data class WireMessage(
    val id: String,
    val threadId: String,
    val text: String,
    val timestamp: Long,
    val sender: String? = null,
    val fromMe: Boolean = false,
    val attachment: Boolean = false,
    /** Cached media identifier on the phone and watch, when the attachment is viewable. */
    val mediaId: String? = null,
    /** MIME type of the cached media, for example image/jpeg or image/gif. */
    val mediaMime: String? = null,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val mediaSize: Long = 0L,
    val status: String? = null
) {
    val displayText: String
        get() = when {
            text.isNotBlank() -> text
            mediaMime == "image/gif" -> "GIF"
            attachment -> "Photo"
            else -> "Message"
        }

    /**
     * Notification refreshes can omit image data temporarily, and restored
     * cache entries may predate media support. Keep media metadata that was
     * already known rather than degrading an attachment to a placeholder.
     */
    fun mergeMediaFrom(previous: WireMessage): WireMessage {
        if (mediaId == null && previous.mediaId != null) {
            return copy(
                attachment = true,
                mediaId = previous.mediaId,
                mediaMime = previous.mediaMime,
                mediaWidth = previous.mediaWidth,
                mediaHeight = previous.mediaHeight,
                mediaSize = previous.mediaSize
            )
        }
        if (!attachment && previous.attachment) return copy(attachment = true)
        return this
    }
}

@Serializable
data class WireThread(
    val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val participants: List<String> = emptyList(),
    val messages: List<WireMessage> = emptyList(),
    val unread: Boolean = false,
    val notificationActive: Boolean = false,
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    val canSend: Boolean = false,
    /** Phone number or email address for a one-to-one chat, when available. */
    val address: String? = null,
    val updatedAt: Long = 0L
) {
    fun latestMessage(): WireMessage? = messages.maxByOrNull { it.timestamp }

    fun lastActivity(): Long = latestMessage()?.timestamp ?: updatedAt

    fun preview(): String = latestMessage()?.displayText ?: "No messages"

    fun toSummary(): ThreadSummary {
        val latest = latestMessage()
        return ThreadSummary(
            id = id,
            title = title,
            isGroup = isGroup,
            unread = unread,
            notificationActive = notificationActive,
            canReply = canReply,
            canMarkRead = canMarkRead,
            canSend = canSend,
            lastTimestamp = latest?.timestamp ?: updatedAt,
            lastPreview = latest?.displayText ?: "",
            messageCount = messages.size,
            participants = participants,
            address = address
        )
    }
}

@Serializable
data class ThreadSummary(
    val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val unread: Boolean = false,
    val notificationActive: Boolean = false,
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    val canSend: Boolean = false,
    val lastTimestamp: Long = 0L,
    val lastPreview: String = "",
    val messageCount: Int = 0,
    val participants: List<String> = emptyList(),
    val address: String? = null
)

@Serializable
data class SyncPayload(
    val version: Int = Protocol.VERSION,
    val generatedAt: Long,
    val phoneName: String,
    val bridgeVersion: String,
    val notificationAccess: Boolean,
    val relayEnabled: Boolean = true,
    val automationAvailable: Boolean = false,
    /** True when Android is showing a secure keyguard; UI automation cannot run. */
    val phoneLocked: Boolean = false,
    val phoneAwake: Boolean = true,
    val conversations: List<ThreadSummary> = emptyList(),
    val contactsAccess: Boolean? = null
)

@Serializable
data class ThreadPayload(
    val version: Int = Protocol.VERSION,
    val thread: WireThread? = null,
    val error: String? = null
)

@Serializable
data class UpdatePayload(
    val version: Int = Protocol.VERSION,
    val thread: WireThread
)

@Serializable
data class SyncRequest(
    val version: Int = Protocol.VERSION,
    val sentAt: Long = System.currentTimeMillis(),
    val watchName: String = ""
)

@Serializable
data class ThreadRequest(
    val version: Int = Protocol.VERSION,
    val threadId: String,
    val sentAt: Long = System.currentTimeMillis()
)

@Serializable
data class PingPayload(
    val version: Int = Protocol.VERSION,
    val sentAt: Long = System.currentTimeMillis(),
    val watchName: String = ""
)

@Serializable
data class PongPayload(
    val version: Int = Protocol.VERSION,
    val sentAt: Long = System.currentTimeMillis(),
    val receivedAt: Long = System.currentTimeMillis(),
    val phoneName: String = "",
    val bridgeVersion: String = "",
    val notificationAccess: Boolean = false,
    val relayEnabled: Boolean = true,
    val automationAvailable: Boolean = false,
    val phoneLocked: Boolean = false,
    val phoneAwake: Boolean = true,
    val trackedConversations: Int = 0,
    val quickReplies: List<String> = Protocol.DEFAULT_QUICK_REPLIES,
    val contactsAccess: Boolean? = null
)

@Serializable
data class ReplyRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String,
    val text: String,
    val sentAt: Long = System.currentTimeMillis()
)

@Serializable
data class MarkReadRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String,
    val sentAt: Long = System.currentTimeMillis()
)

@Serializable
data class OpenPhoneRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String? = null,
    val sentAt: Long = System.currentTimeMillis()
)

/**
 * Sends a message through OpenBubbles' own compose UI. This is used when the
 * conversation has no live notification (and therefore no reply action), or
 * when starting a brand-new conversation.
 */
@Serializable
data class SendRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String? = null,
    val address: String? = null,
    val title: String? = null,
    val text: String,
    val sentAt: Long = System.currentTimeMillis()
)

/** Asks the phone to scroll OpenBubbles' UI and return older messages. */
@Serializable
data class HistoryRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String,
    val title: String? = null,
    val address: String? = null,
    val limit: Int = 100,
    val sentAt: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    // Null preserves older watches; empty means no cached text anchor yet.
    val anchorMessages: List<WireMessage>? = null
)

@Serializable
data class HistoryPayload(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val threadId: String,
    val messages: List<WireMessage> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
    /** The phone was locked, so the same request can succeed after unlock. */
    val retryable: Boolean = false,
    val completedAt: Long = System.currentTimeMillis()
)

/** Asks the phone to publish one cached image or GIF over the Data Layer. */
@Serializable
data class MediaRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val mediaId: String,
    val sentAt: Long = System.currentTimeMillis()
)

@Serializable
data class MediaPayload(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val mediaId: String,
    val ok: Boolean,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0L,
    val error: String? = null,
    val completedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ActionResult(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val action: String,
    val ok: Boolean,
    val threadId: String? = null,
    val error: String? = null,
    /** The action did no work and should be retried after the phone unlocks. */
    val retryable: Boolean = false,
    val completedAt: Long = System.currentTimeMillis(),
    val message: WireMessage? = null
)

@Serializable
data class BridgeError(
    val version: Int = Protocol.VERSION,
    val requestId: String? = null,
    val error: String
)

@Serializable
data class ContactAddress(val address: String, val label: String = "")

@Serializable
data class WatchContact(
    val id: String,
    val name: String,
    val addresses: List<ContactAddress> = emptyList()
)

@Serializable
data class ContactsRequest(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val query: String = "",
    val offset: Int = 0
)

@Serializable
data class ContactsPage(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val query: String = "",
    val offset: Int = 0,
    val contacts: List<WatchContact> = emptyList(),
    val nextOffset: Int? = null,
    val contactsAccess: Boolean = false,
    val error: String? = null
)

@Serializable
data class GroupsRequest(val version: Int = Protocol.VERSION, val requestId: String)

@Serializable
data class GroupsResult(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    /** Groups only, kept for 1.5.0 watches. */
    val groups: List<ThreadSummary> = emptyList(),
    /** One-to-one chats found without a notification. */
    val chats: List<ThreadSummary> = emptyList(),
    val error: String? = null,
    val note: String? = null
)
