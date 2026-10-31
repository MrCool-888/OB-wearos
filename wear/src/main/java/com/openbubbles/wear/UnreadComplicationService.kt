package com.openbubbles.wear

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication showing the unread count. Tapping opens the newest
 * unread chat when one exists, otherwise the conversation list.
 */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData? {
        WatchRepository.attach(applicationContext)
        return data(request.complicationType, WatchRepository.state.value)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return data(
            type,
            WatchState(
                conversations = listOf(
                    com.openbubbles.wear.protocol.ThreadSummary(
                        id = "preview",
                        title = "Alex",
                        unread = true,
                        lastPreview = "See you at 7"
                    )
                )
            )
        )
    }

    private fun data(type: ComplicationType, state: WatchState): ComplicationData? {
        val conversations = state.conversationsForDisplay
        val unread = conversations.count { it.unread }
        val target = conversations.firstOrNull { it.unread }?.id
        val tapAction = tapIntent(target)

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val text = if (unread > 0) unread.toString() else "✓"
                val description = when {
                    unread == 1 -> "1 unread message"
                    unread > 1 -> "$unread unread messages"
                    else -> "No unread messages"
                }
                ShortTextComplicationData.Builder(
                    plain(text),
                    plain(description)
                )
                    .setTitle(plain("Messages"))
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                val text = when {
                    unread == 1 -> "1 unread message"
                    unread > 1 -> "$unread unread messages"
                    state.phoneOnline -> "No unread messages"
                    else -> "Phone offline"
                }
                LongTextComplicationData.Builder(
                    plain(text),
                    plain("OpenBubbles unread messages")
                )
                    .setTitle(plain("OpenBubbles"))
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    unread.coerceAtMost(20).toFloat(),
                    0f,
                    20f,
                    plain(if (unread > 0) "$unread unread" else "No unread messages")
                )
                    .setText(plain("Messages"))
                    .setTapAction(tapAction)
                    .build()
            }

            else -> null
        }
    }

    private fun tapIntent(threadId: String?): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("openbubbles-wear").authority("complication")
                .appendPath(threadId.orEmpty()).build()
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            if (!threadId.isNullOrBlank()) {
                action = WatchNavigation.ACTION_OPEN_CHAT
                putExtra(WatchNavigation.EXTRA_THREAD_ID, threadId)
            }
        }
        return PendingIntent.getActivity(
            applicationContext,
            threadId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun plain(text: CharSequence): PlainComplicationText =
        PlainComplicationText.Builder(text).build()
}
