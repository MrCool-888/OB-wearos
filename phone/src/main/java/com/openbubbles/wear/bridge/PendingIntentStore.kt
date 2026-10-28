package com.openbubbles.wear.bridge

import android.app.PendingIntent
import android.content.Context
import android.os.Parcel
import android.util.Base64
import android.util.Log
import com.openbubbles.wear.protocol.Wire

/**
 * Keeps the content PendingIntent that OpenBubbles attached to each chat
 * notification. The intent is owned by OpenBubbles, so sending it opens the
 * exact conversation without knowing its chat GUID. It usually stays valid
 * after the notification is dismissed, which lets the bridge open cached
 * chats without searching the conversation list.
 *
 * This is best-effort: the token can be cancelled by OpenBubbles at any time,
 * and callers must fall back to launching OpenBubbles normally.
 */
class PendingIntentStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(threadId: String, intent: PendingIntent?) {
        if (intent == null || threadId.isBlank()) return
        val encoded = runCatching { encode(intent) }.getOrNull() ?: return
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

    fun get(threadId: String): PendingIntent? {
        val encoded = prefs.getString(KEY_PREFIX + threadId, null) ?: return null
        return runCatching { decode(encoded) }.getOrNull()
    }

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

    private fun encode(intent: PendingIntent): String {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun decode(encoded: String): PendingIntent? {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            PendingIntent.CREATOR.createFromParcel(parcel)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to restore saved content intent", t)
            null
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val TAG = "OBWearIntents"
        const val PREFS_NAME = "openbubbles_wear_intents"
        const val KEY_PREFIX = "intent:"
        const val KEY_ORDER = "order"
        const val MAX_ENTRIES = 40
    }
}
