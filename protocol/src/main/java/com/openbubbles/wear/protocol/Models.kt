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
    val status: String? = null
) {
    val displayText: String
        get() = when {
            text.isNotBlank() -> text
            attachment -> "Photo"
            else -> "Message"
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
            lastTimestamp = latest?.timestamp ?: updatedAt,
            lastPreview = latest?.displayText ?: "",
            messageCount = messages.size,
            participants = participants
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
    val lastTimestamp: Long = 0L,
    val lastPreview: String = "",
    val messageCount: Int = 0,
    val participants: List<String> = emptyList()
)

@Serializable
data class SyncPayload(
    val version: Int = Protocol.VERSION,
    val generatedAt: Long,
    val phoneName: String,
    val bridgeVersion: String,
    val notificationAccess: Boolean,
    val relayEnabled: Boolean = true,
    val conversations: List<ThreadSummary> = emptyList()
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
    val trackedConversations: Int = 0,
    val quickReplies: List<String> = Protocol.DEFAULT_QUICK_REPLIES
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

@Serializable
data class ActionResult(
    val version: Int = Protocol.VERSION,
    val requestId: String,
    val action: String,
    val ok: Boolean,
    val threadId: String? = null,
    val error: String? = null,
    val completedAt: Long = System.currentTimeMillis(),
    val message: WireMessage? = null
)

@Serializable
data class BridgeError(
    val version: Int = Protocol.VERSION,
    val requestId: String? = null,
    val error: String
)
