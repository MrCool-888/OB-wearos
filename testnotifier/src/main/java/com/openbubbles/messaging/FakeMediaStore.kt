package com.openbubbles.messaging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves the debug image and animated GIF through the same provider authority
 * OpenBubbles uses. Notification listeners receive a temporary read grant for
 * the URI, so the bridge can copy the bytes into its own cache.
 */
object FakeMediaStore {
    const val AUTHORITY = "com.openbubbles.messaging.fileprovider"

    fun mimeType(name: String): String = when {
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".webp") -> "image/webp"
        else -> "image/jpeg"
    }

    fun uri(context: Context, name: String): Uri? = runCatching {
        val directory = File(context.cacheDir, "media").apply { mkdirs() }
        val target = File(directory, name)
        if (!target.isFile || target.length() <= 0L) {
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        FileProvider.getUriForFile(context, AUTHORITY, target)
    }.onFailure {
        android.util.Log.w(FakeOpenBubblesNotifications.TAG, "Unable to serve $name", it)
    }.getOrNull()
}
