package com.openbubbles.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Base64
import android.widget.Toast
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.io.ByteArrayOutputStream

/**
 * Debug-only tool for exercising the watch UI without a paired phone.
 *
 * Usage:
 *   adb shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
 *       -a com.openbubbles.wear.DEBUG_SEED --es mode demo
 *   adb shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
 *       -a com.openbubbles.wear.DEBUG_SEED --es mode media
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

            "media" -> {
                seedMedia()
                Toast.makeText(context, "Demo media loaded", Toast.LENGTH_SHORT).show()
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
        publish(listOf(maya, family, alex), now)
    }

    /** Seeds a thread whose photo and animated GIF are already on the watch. */
    private fun seedMedia() {
        val now = System.currentTimeMillis()
        val photoBytes = buildPhoto()
        val gifBytes = Base64.decode(DEMO_GIF, Base64.NO_WRAP)
        val photo = WatchRepository.importLocalMedia(
            id = "demo-photo",
            mimeType = "image/png",
            width = 320,
            height = 200,
            bytes = photoBytes
        )
        val gif = WatchRepository.importLocalMedia(
            id = "demo-gif",
            mimeType = "image/gif",
            width = 48,
            height = 48,
            bytes = gifBytes
        )
        val thread = WireThread(
            id = "demo-media",
            title = "Riley Park",
            participants = listOf("Riley Park"),
            notificationActive = true,
            canReply = true,
            canMarkRead = true,
            updatedAt = now,
            messages = listOfNotNull(
                message(
                    id = "media-1",
                    threadId = "demo-media",
                    text = "Look at this view from the trail",
                    timestamp = now - 9 * 60_000L,
                    sender = "Riley Park",
                    media = photo
                ),
                message(
                    id = "media-2",
                    threadId = "demo-media",
                    text = "And this one moves",
                    timestamp = now - 6 * 60_000L,
                    sender = "Riley Park",
                    media = gif
                ),
                message(
                    id = "media-3",
                    threadId = "demo-media",
                    text = "Sending one back later",
                    timestamp = now - 2 * 60_000L,
                    fromMe = true
                )
            )
        )
        publish(listOf(thread), now)
    }

    private fun publish(threads: List<WireThread>, now: Long) {
        val payload = SyncPayload(
            generatedAt = now,
            phoneName = "Pixel 9 (demo)",
            bridgeVersion = "1.2.0",
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
        fromMe: Boolean = false,
        media: WatchMedia? = null
    ) = WireMessage(
        id = id,
        threadId = threadId,
        text = text,
        timestamp = timestamp,
        sender = sender,
        fromMe = fromMe,
        attachment = media != null,
        mediaId = media?.id,
        mediaMime = media?.mimeType,
        mediaWidth = media?.width ?: 0,
        mediaHeight = media?.height ?: 0,
        mediaSize = media?.size ?: 0L
    )

    private fun buildPhoto(): ByteArray {
        val width = 320
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                0xFF1565C0.toInt(),
                0xFF64B5F6.toInt(),
                0xFFFFCC80.toInt()
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.color = 0xFFFFF176.toInt()
        canvas.drawCircle(252f, 54f, 26f, paint)

        paint.color = 0xFF2E7D32.toInt()
        val hills = Path().apply {
            moveTo(0f, height.toFloat())
            lineTo(0f, 142f)
            lineTo(88f, 88f)
            lineTo(168f, 148f)
            lineTo(232f, 108f)
            lineTo(width.toFloat(), 168f)
            lineTo(width.toFloat(), height.toFloat())
            close()
        }
        canvas.drawPath(hills, paint)

        paint.color = 0xFF1B5E20.toInt()
        val foreground = Path().apply {
            moveTo(0f, height.toFloat())
            lineTo(0f, 174f)
            lineTo(width.toFloat(), 158f)
            lineTo(width.toFloat(), height.toFloat())
            close()
        }
        canvas.drawPath(foreground, paint)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private companion object {
        // A 48x48, three-frame animated GIF generated for the debug harness.
        const val DEMO_GIF = "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"
    }
}
