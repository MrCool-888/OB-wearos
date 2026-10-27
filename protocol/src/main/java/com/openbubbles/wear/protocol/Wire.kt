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
}
