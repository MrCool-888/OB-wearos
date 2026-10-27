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
}
