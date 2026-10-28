package com.openbubbles.wear.bridge

import android.app.PendingIntent
import android.content.Context
import android.os.Parcel
import android.util.Base64
import android.util.Log
import com.openbubbles.wear.protocol.Wire

/** The pieces of a notification reply action that are needed to send text. */
data class SavedReply(
    val intent: PendingIntent,
    val resultKey: String
)

/**
 * Keeps the reply PendingIntent last posted for each chat. Unlike the live
 * notification action, these survive the notification being dismissed, so a
 * watch reply can still go through OpenBubbles' receiver while the phone is
 * locked. This is best-effort: OpenBubbles may cancel the token, in which case
 * the send reports that the chat has no reply action.
 */
class ReplyActionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(threadId: String, reply: SavedReply?) {
        if (reply == null || threadId.isBlank()) return
        val encoded = runCatching { encode(reply) }.getOrNull() ?: return
        val order = order().filterNot { it == threadId }.toMutableList()
        order += threadId
        while (order.size > MAX_ENTRIES) {
            val removed = order.removeAt(0)
            prefs.edit().remove(KEY_PREFIX + removed).apply()
        }
        prefs.edit()
            .putString(KEY_PREFIX + threadId, encoded)
            .putString(KEY_ORDER, Wire.encode(order))
            .apply()
    }

    fun get(threadId: String): SavedReply? {
        val encoded = prefs.getString(KEY_PREFIX + threadId, null) ?: return null
        return runCatching { decode(encoded) }.getOrNull()
    }

    fun contains(threadId: String): Boolean = prefs.contains(KEY_PREFIX + threadId)

    fun remove(threadId: String) {
        val order = order().filterNot { it == threadId }
        prefs.edit()
            .remove(KEY_PREFIX + threadId)
            .putString(KEY_ORDER, Wire.encode(order))
            .apply()
    }

    fun clear() {
        val editor = prefs.edit()
        order().forEach { editor.remove(KEY_PREFIX + it) }
        editor.remove(KEY_ORDER).apply()
    }

    private fun order(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null) ?: return emptyList()
        return Wire.decode<List<String>>(raw).orEmpty()
    }

    private fun encode(reply: SavedReply): String {
        val parcel = Parcel.obtain()
        return try {
            reply.intent.writeToParcel(parcel, 0)
            parcel.writeString(reply.resultKey)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun decode(encoded: String): SavedReply? {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val intent = PendingIntent.CREATOR.createFromParcel(parcel) ?: return null
            val resultKey = parcel.readString()?.takeIf { it.isNotBlank() } ?: return null
            SavedReply(intent, resultKey)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to restore saved reply action", t)
            null
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val TAG = "OBWearReplies"
        const val PREFS_NAME = "openbubbles_wear_replies"
        const val KEY_PREFIX = "reply:"
        const val KEY_ORDER = "order"
        const val MAX_ENTRIES = 40
    }
}
