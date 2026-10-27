package com.openbubbles.wear.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Shared wire contract between the phone bridge and the Wear OS app.
 *
 * The phone is the source of truth. The watch only receives snapshots and
 * asks the phone to perform actions against the live OpenBubbles notification.
 */
object Protocol {
    const val VERSION = 2

    const val CAPABILITY_PHONE = "openbubbles_wear_bridge"
    const val CAPABILITY_WATCH = "openbubbles_wear"

    const val BASE_PATH = "/openbubbles_wear"

    // MessageClient paths (small, request/response payloads).
    const val PATH_PING = "$BASE_PATH/ping"
    const val PATH_PONG = "$BASE_PATH/pong"
    const val PATH_SYNC_REQUEST = "$BASE_PATH/sync_request"
    const val PATH_SYNC = "$BASE_PATH/sync"
    const val PATH_THREAD_REQUEST = "$BASE_PATH/thread_request"
    const val PATH_THREAD = "$BASE_PATH/thread"
    const val PATH_UPDATE = "$BASE_PATH/update"
    const val PATH_REPLY = "$BASE_PATH/reply"
    const val PATH_REPLY_RESULT = "$BASE_PATH/reply_result"
    const val PATH_MARK_READ = "$BASE_PATH/mark_read"
    const val PATH_ACTION_RESULT = "$BASE_PATH/action_result"
    const val PATH_OPEN_PHONE = "$BASE_PATH/open_phone"
    const val PATH_SEND = "$BASE_PATH/send"
    const val PATH_HISTORY = "$BASE_PATH/history"
    const val PATH_HISTORY_RESULT = "$BASE_PATH/history_result"

    // DataApi paths (durable snapshots for when the watch app is not running).
    const val DATA_SYNC = "$BASE_PATH/sync"
    const val DATA_THREAD_PREFIX = "$BASE_PATH/thread/"
    const val DATA_KEY_JSON = "json"

    const val ACTION_REPLY = "reply"
    const val ACTION_MARK_READ = "mark_read"
    const val ACTION_OPEN_PHONE = "open_phone"
    const val ACTION_SEND = "send"
    const val ACTION_HISTORY = "history"

    val DEFAULT_QUICK_REPLIES: List<String> = listOf(
        "On my way",
        "Yes",
        "No",
        "Can't talk right now",
        "Thanks!",
        "👍"
    )

    /**
     * Thread IDs come from notification shortcut IDs, which can contain slashes,
     * spaces and other characters that are not safe in a DataApi path segment.
     */
    fun threadDataPath(threadId: String): String {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(threadId.toByteArray(StandardCharsets.UTF_8))
        return DATA_THREAD_PREFIX + encoded
    }

    fun threadIdFromDataPath(path: String): String? {
        if (!path.startsWith(DATA_THREAD_PREFIX)) return null
        val encoded = path.removePrefix(DATA_THREAD_PREFIX)
        if (encoded.isEmpty()) return null
        val padded = when (encoded.length % 4) {
            0 -> encoded
            2 -> "$encoded=="
            3 -> "$encoded="
            else -> return null
        }
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Normalizes a phone number or email address so a notification address and
     * an address typed on the watch resolve to the same conversation. Returns
     * null for values that do not look like either.
     */
    fun normalizeAddress(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        var address = value
        val lower = address.lowercase(Locale.ROOT)
        address = when {
            lower.startsWith("tel:") -> address.substring(4)
            lower.startsWith("mailto:") -> address.substring(7)
            lower.startsWith("sms:") -> address.substring(4)
            lower.startsWith("smsto:") -> address.substring(6)
            else -> address
        }
        if (address.contains(";-;")) address = address.substringAfterLast(";-;")
        address = address.substringBefore('?').substringBefore(';').trim()
        return when {
            address.contains('@') && address.length >= 3 ->
                address.lowercase(Locale.ROOT)
            address.count { it.isDigit() } >= 5 ->
                address.filter { it.isDigit() || it == '+' }
            else -> null
        }
    }

    /** Stable local ID for a conversation created from an address on the watch. */
    fun newThreadId(address: String): String =
        "new:" + (normalizeAddress(address) ?: address.trim().lowercase(Locale.ROOT))

    /**
     * Stable ID for a message discovered inside a notification. OpenBubbles does
     * not put a message GUID in the MessagingStyle message extras, so identity is
     * derived from the timestamp, sender and content. Attachment state is
     * deliberately excluded: the same message can appear with or without its
     * image data as notifications are refreshed, and it must not become a
     * duplicate when that happens. This ID is stable across repeated notification
     * updates and is the same on the phone and the watch.
     */
    fun messageId(
        threadId: String,
        timestamp: Long,
        sender: String?,
        text: String
    ): String {
        val raw = buildString {
            append(threadId)
            append('\u0001')
            append(timestamp)
            append('\u0001')
            append(sender.orEmpty())
            append('\u0001')
            append(text)
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return "m-" + digest.joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
