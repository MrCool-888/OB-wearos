package com.openbubbles.wear.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Debug-only command receiver for checking the relay without a watch.
 *
 * adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
 *     -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
 * ... --es op reply --es thread fake-maya --es text "On my way"
 * ... --es op mark_read --es thread fake-maya
 *
 * Thread IDs come from the live notification's shortcut ID. Run `status` to
 * list every cached thread and its current capabilities.
 */
class DebugRelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationCenter.init(context)
        when (val op = intent.getStringExtra("op")?.lowercase()) {
            "status" -> status(context)
            "reply" -> {
                val thread = intent.getStringExtra("thread") ?: "fake-maya"
                val text = intent.getStringExtra("text") ?: "On my way"
                val error = NotificationCenter.reply(thread, text)
                report(context, "reply $thread", error)
            }

            "mark_read" -> {
                val thread = intent.getStringExtra("thread") ?: "fake-maya"
                val error = NotificationCenter.markRead(thread)
                report(context, "mark_read $thread", error)
            }

            "open_phone" -> {
                val thread = intent.getStringExtra("thread")
                val error = NotificationCenter.openPhone(thread)
                report(context, "open_phone $thread", error)
            }

            "sync" -> {
                NotificationCenter.syncNow(context)
                report(context, "sync", null)
            }

            "clear_cache" -> {
                NotificationCenter.clearCache(context)
                report(context, "clear_cache", null)
            }

            else -> report(
                context,
                "unknown op=$op",
                "Use status, reply, mark_read, open_phone, sync or clear_cache"
            )
        }
    }

    private fun status(context: Context) {
        val pong = NotificationCenter.pong()
        Log.i(
            TAG,
            "status access=${BridgeInfo.hasNotificationAccess(context)} " +
                "installed=${BridgeInfo.isOpenBubblesInstalled(context)} " +
                "relay=${pong.relayEnabled} tracked=${pong.trackedConversations} " +
                "access=${pong.notificationAccess}"
        )
        BridgeStore(context).snapshot().forEach { cached ->
            val thread = NotificationCenter.threadFor(cached.id) ?: cached
            Log.i(
                TAG,
                "thread ${cached.id} cached=true " +
                    "messages=${thread.messages.size} " +
                    "unread=${thread.unread} live=${thread.notificationActive} " +
                    "reply=${thread.canReply} markRead=${thread.canMarkRead}"
            )
        }
        Toast.makeText(
            context,
            "Tracked ${pong.trackedConversations}, access ${pong.notificationAccess}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun report(context: Context, label: String, error: String?) {
        val message = if (error == null) "$label: ok" else "$label: $error"
        Log.i(TAG, message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "OBWearDebug"
    }
}
