package com.openbubbles.wear.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.util.UUID

/**
 * Debug-only command receiver for checking the relay without a watch.
 *
 * adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
 *     -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
 * ... --es op reply --es thread fake-maya --es text "On my way"
 * ... --es op mark_read --es thread fake-maya
 * ... --es op send --es thread fake-maya --es text "Sent from adb"
 * ... --es op send --es address +15551234567 --es text "New chat"
 * ... --es op history --es thread fake-maya
 * ... --es op media                       (list cached media IDs)
 * ... --es op media --es id media-abc123  (publish one item to the watch)
 *
 * Thread IDs come from the live notification's shortcut ID. Run `status` to
 * list every cached thread and its current capabilities. The `send` and
 * `history` operations exercise the same accessibility automation path as the
 * watch, so they require the optional automation service.
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

            "send" -> {
                val thread = intent.getStringExtra("thread")
                val address = intent.getStringExtra("address")
                val title = intent.getStringExtra("title")
                val text = intent.getStringExtra("text") ?: "Sent from adb"
                NotificationCenter.send(
                    requestId = UUID.randomUUID().toString(),
                    threadId = thread,
                    address = address,
                    title = title,
                    rawText = text
                ) { result ->
                    report(
                        context,
                        "send ${result.threadId ?: thread ?: address}",
                        result.error
                    )
                }
            }

            "history" -> {
                val thread = intent.getStringExtra("thread") ?: "fake-maya"
                val title = intent.getStringExtra("title")
                val address = intent.getStringExtra("address")
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 60
                NotificationCenter.history(thread, title, address, limit) { result ->
                    val summary = "history $thread messages=${result.messages.size} " +
                        "hasMore=${result.hasMore}"
                    report(context, summary, result.error)
                }
            }

            "sync" -> {
                NotificationCenter.syncNow(context)
                report(context, "sync", null)
            }

            "media" -> {
                val id = intent.getStringExtra("id")
                if (id.isNullOrBlank()) {
                    val ids = BridgeStore(context).snapshot()
                        .flatMap { thread -> thread.messages.mapNotNull { it.mediaId } }
                        .distinct()
                    report(context, "media ids=${ids.joinToString(",")}", null)
                } else {
                    NotificationCenter.media(id) { info, error ->
                        val summary = if (info != null) {
                            "media $id ${info.mimeType} ${info.width}x${info.height} " +
                                "${info.size}B"
                        } else {
                            "media $id"
                        }
                        report(context, summary, error)
                    }
                }
            }

            "clear_cache" -> {
                NotificationCenter.clearCache(context)
                report(context, "clear_cache", null)
            }

            else -> report(
                context,
                "unknown op=$op",
                "Use status, reply, mark_read, open_phone, send, history, media, sync or clear_cache"
            )
        }
    }

    private fun status(context: Context) {
        val pong = NotificationCenter.pong()
        Log.i(
            TAG,
            "status access=${BridgeInfo.hasNotificationAccess(context)} " +
                "installed=${BridgeInfo.isOpenBubblesInstalled(context)} " +
                "relay=${pong.relayEnabled} automation=${pong.automationAvailable} " +
                "tracked=${pong.trackedConversations} " +
                "media=${NotificationCenter.mediaCount(context)} " +
                "access=${pong.notificationAccess}"
        )
        BridgeStore(context).snapshot().forEach { cached ->
            val thread = NotificationCenter.threadFor(cached.id) ?: cached
            Log.i(
                TAG,
                "thread ${cached.id} cached=true " +
                    "messages=${thread.messages.size} " +
                    "media=${thread.messages.count { it.mediaId != null }} " +
                    "unread=${thread.unread} live=${thread.notificationActive} " +
                    "reply=${thread.canReply} markRead=${thread.canMarkRead} " +
                    "send=${thread.canSend} address=${thread.address}"
            )
        }
        Toast.makeText(
            context,
            "Tracked ${pong.trackedConversations}, access ${pong.notificationAccess}, " +
                "automation ${pong.automationAvailable}",
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
