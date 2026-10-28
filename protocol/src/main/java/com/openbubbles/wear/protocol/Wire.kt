package com.openbubbles.wear.protocol

import kotlinx.serialization.json.Json

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()

    inline fun <reified T> encodeBytes(value: T): ByteArray =
        encode(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decodeBytes(bytes: ByteArray): T? =
        decode(bytes.toString(Charsets.UTF_8))

    /**
     * The Wear data layer rejects items over 100 KB. Older history can push a
     * thread past that, so the transport copy drops its oldest messages while
     * the phone keeps the full store.
     */
    fun threadForTransport(thread: WireThread, maxBytes: Int = 90_000): WireThread {
        if (encode(thread).toByteArray(Charsets.UTF_8).size <= maxBytes) return thread
        var candidate = thread
        while (candidate.messages.size > 1) {
            val drop = maxOf(1, candidate.messages.size / 8)
            candidate = candidate.copy(messages = candidate.messages.drop(drop))
            if (encode(candidate).toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate
        }
        return candidate
    }

    fun syncForTransport(payload: SyncPayload, maxBytes: Int = 90_000): SyncPayload {
        var candidate = payload.copy(conversations = payload.conversations.map { it.forTransport() })
        while (candidate.conversations.size > 1 && encodeBytes(candidate).size > maxBytes) {
            candidate = candidate.copy(conversations = candidate.conversations.dropLast(1))
        }
        return candidate
    }

    fun groupsForTransport(result: GroupsResult, maxBytes: Int = 90_000): GroupsResult {
        var summaries = (result.groups + result.chats).sortedByDescending { it.lastTimestamp }.map { it.forTransport() }
        fun resultWith(chats: List<ThreadSummary>) = result.copy(groups = chats.filter { it.isGroup }, chats = chats.filterNot { it.isGroup })
        while (summaries.size > 1 && encodeBytes(resultWith(summaries)).size > maxBytes) summaries = summaries.dropLast(1)
        return resultWith(summaries)
    }

    private fun ThreadSummary.forTransport(): ThreadSummary = copy(
        title = title.take(160), lastPreview = lastPreview.take(160),
        participants = participants.take(12).map { it.take(80) }
    )
}
