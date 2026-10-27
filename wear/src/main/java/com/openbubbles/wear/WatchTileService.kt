package com.openbubbles.wear

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.openbubbles.wear.protocol.ThreadSummary

/**
 * Quick-glance tile listing unread conversations. Each row deep links into the
 * cached chat, so reading and replying work without waiting for a sync.
 */
class WatchTileService : TileService() {
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = Futures.immediateFuture(
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    )

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        WatchRepository.attach(applicationContext)
        val state = WatchRepository.state.value
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(buildLayout(state))
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    private fun buildLayout(state: WatchState): LayoutElementBuilders.LayoutElement {
        val unread = state.conversations.filter { it.unread }
        val latest = unread.take(3)
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(BACKGROUND))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(18f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(DimensionBuilders.dp(12f))
                            .build()
                    )
                    .build()
            )
            .addContent(header(state, unread.size))

        if (latest.isEmpty()) {
            column.addContent(emptyState(state))
        } else {
            latest.forEach { summary ->
                column.addContent(spacer(6f))
                column.addContent(row(summary))
            }
            if (unread.size > latest.size) {
                column.addContent(spacer(4f))
                column.addContent(
                    text(
                        text = "+${unread.size - latest.size} more unread",
                        size = 12f,
                        color = TERTIARY,
                        maxLines = 1
                    )
                )
            }
        }

        val queued = state.queuedCount
        if (queued > 0) {
            column.addContent(spacer(4f))
            column.addContent(
                text(
                    text = "$queued queued to send",
                    size = 12f,
                    color = TERTIARY,
                    maxLines = 1
                )
            )
        }
        return column.build()
    }

    private fun header(state: WatchState, unread: Int): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open-messages")
                            .setOnClick(launchTo(null))
                            .build()
                    )
                    .build()
            )
        row.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("OpenBubbles")
                .setMaxLines(1)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(14f))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .setColor(ColorBuilders.argb(PRIMARY))
                        .build()
                )
                .build()
        )
        row.addContent(spacer(width = 6f))
        if (unread > 0) {
            row.addContent(
                text(
                    text = if (unread == 1) "1 unread" else "$unread unread",
                    size = 12f,
                    color = ACCENT,
                    maxLines = 1
                )
            )
        } else if (!state.phoneOnline) {
            row.addContent(
                text(text = "Offline", size = 12f, color = ACCENT, maxLines = 1)
            )
        }
        return row.build()
    }

    private fun emptyState(state: WatchState): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open-empty-messages")
                            .setOnClick(launchTo(null))
                            .build()
                    )
                    .build()
            )
            .addContent(
                text(
                    text = "All caught up",
                    size = 16f,
                    color = SECONDARY,
                    maxLines = 1
                )
            )
            .addContent(
                text(
                    text = if (state.phoneOnline) {
                        "Tap to open messages"
                    } else {
                        "Phone offline · showing saved chats"
                    },
                    size = 12f,
                    color = TERTIARY,
                    maxLines = 1
                )
            )
            .build()

    private fun row(summary: ThreadSummary): LayoutElementBuilders.LayoutElement {
        val target = if (summary.id.isBlank()) {
            launchTo(null)
        } else {
            launchTo(summary.id)
        }
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("chat-${summary.id}")
                            .setOnClick(target)
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(summary.title.ifBlank { "Conversation" })
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(14f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(ColorBuilders.argb(PRIMARY))
                            .build()
                    )
                    .build()
            )
            .addContent(
                text(
                    text = preview(summary),
                    size = 12f,
                    color = SECONDARY,
                    maxLines = 1
                )
            )
        return column.build()
    }

    private fun preview(summary: ThreadSummary): String = when {
        summary.lastPreview.isNotBlank() -> summary.lastPreview
        summary.isGroup -> "New group message"
        else -> "New message"
    }

    private fun launchTo(threadId: String?): ActionBuilders.Action {
        val component = ComponentName(applicationContext, MainActivity::class.java)
        val extras = if (threadId.isNullOrBlank()) {
            emptyMap()
        } else {
            mapOf(
                WatchNavigation.EXTRA_THREAD_ID to ActionBuilders.stringExtra(threadId)
            )
        }
        return ActionBuilders.launchAction(component, extras)
    }

    private fun text(
        text: String,
        size: Float,
        color: Int,
        maxLines: Int
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(size))
                    .setColor(ColorBuilders.argb(color))
                    .build()
            )
            .build()

    private fun spacer(height: Float = 0f, width: Float = 0f): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(height))
            .setWidth(DimensionBuilders.dp(width))
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 15 * 60 * 1000L
        const val BACKGROUND = 0xFF10151C.toInt()
        const val PRIMARY = 0xFFFFFFFF.toInt()
        const val SECONDARY = 0xFFB8C4D4.toInt()
        const val TERTIARY = 0xFF8A97A8.toInt()
        const val ACCENT = 0xFF7FB2FF.toInt()
    }
}
