package com.openbubbles.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread

/**
 * Debug-only tool for exercising the watch UI without a paired phone.
 *
 * Usage:
 *   adb shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
 *       -a com.openbubbles.wear.DEBUG_SEED --es mode demo
 *   adb shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
 *       -a com.openbubbles.wear.DEBUG_SEED --es mode clear
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchRepository.attach(context)
        when (intent.getStringExtra("mode")) {
            "clear" -> {
                context.getSharedPreferences("openbubbles_wear", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                Toast.makeText(context, "Watch cache cleared", Toast.LENGTH_SHORT).show()
                android.os.Process.killProcess(android.os.Process.myPid())
            }

            else -> {
                seed()
                Toast.makeText(context, "Demo conversations loaded", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun seed() {
        val now = System.currentTimeMillis()
        val maya = WireThread(
            id = "demo-maya",
            title = "Maya Chen",
            participants = listOf("Maya Chen"),
            unread = true,
            notificationActive = true,
            canReply = true,
            canMarkRead = true,
            updatedAt = now,
            messages = listOf(
                message("maya-1", "demo-maya", "Are we still on for dinner?", now - 32 * 60_000L, "Maya Chen"),
                message("maya-2", "demo-maya", "Yes! 7pm at the usual place?", now - 29 * 60_000L, fromMe = true),
                message("maya-3", "demo-maya", "Perfect. I'll book a table.", now - 26 * 60_000L, "Maya Chen"),
                message("maya-4", "demo-maya", "Can you bring the book you borrowed?", now - 4 * 60_000L, "Maya Chen")
            )
        )
        val family = WireThread(
            id = "demo-family",
            title = "Family",
            isGroup = true,
            participants = listOf("Mom", "Dad", "Sam", "Me"),
            notificationActive = true,
            canReply = true,
            canMarkRead = true,
            updatedAt = now,
            messages = listOf(
                message("family-1", "demo-family", "Sunday lunch at ours?", now - 3 * 3_600_000L, "Mom"),
                message("family-2", "demo-family", "Works for me", now - 2 * 3_600_000L, "Dad"),
                message("family-3", "demo-family", "I can drive", now - 100 * 60_000L, "Sam"),
                message("family-4", "demo-family", "I'll bring dessert", now - 90 * 60_000L, fromMe = true),
                message("family-5", "demo-family", "Amazing, see you Sunday x", now - 62 * 60_000L, "Mom")
            )
        )
        val alex = WireThread(
            id = "demo-alex",
            title = "Alex Rivera",
            participants = listOf("Alex Rivera"),
            notificationActive = false,
            canReply = false,
            canMarkRead = false,
            updatedAt = now,
            messages = listOf(
                message("alex-1", "demo-alex", "Sent you the photos from the hike", now - 26 * 3_600_000L, "Alex Rivera"),
                message("alex-2", "demo-alex", "These are great, thanks!", now - 25 * 3_600_000L, fromMe = true)
            )
        )
        val threads = listOf(maya, family, alex)
        val payload = SyncPayload(
            generatedAt = now,
            phoneName = "Pixel 9 (demo)",
            bridgeVersion = "1.0.0",
            notificationAccess = true,
            relayEnabled = true,
            conversations = threads.map { it.toSummary() }
        )
        WatchRepository.onSync(payload)
        threads.forEach { WatchRepository.onThread(it) }
    }

    private fun message(
        id: String,
        threadId: String,
        text: String,
        timestamp: Long,
        sender: String? = null,
        fromMe: Boolean = false
    ) = WireMessage(
        id = id,
        threadId = threadId,
        text = text,
        timestamp = timestamp,
        sender = sender,
        fromMe = fromMe
    )
}
