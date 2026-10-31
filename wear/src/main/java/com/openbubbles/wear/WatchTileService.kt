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
@androidx.annotation.OptIn(markerClass = [androidx.wear.protolayout.expression.ProtoLayoutExperimental::class])
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
        val unread = state.conversationsForDisplay.filter { it.unread }
        val latest = unread.take(MAX_ROWS)
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
                                    .setRadius(DimensionBuilders.dp(20f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(DimensionBuilders.dp(10f))
                            .build()
                    )
                    .build()
            )
            .addContent(spacer(12f))
            .addContent(header(state, unread.size))

        if (latest.isEmpty()) {
            column.addContent(spacer(8f))
            column.addContent(emptyState(state))
        } else {
            latest.forEach { summary ->
                column.addContent(spacer(4f))
                column.addContent(row(summary))
            }
        }

        val footer = footer(state, unread.size - latest.size)
        if (footer != null) {
            column.addContent(spacer(4f))
            column.addContent(footer)
        }
        return column.build()
    }

    private fun header(state: WatchState, unread: Int): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
        row.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("OpenBubbles")
                .setMaxLines(1)
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(13f))
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
                    size = 11f,
                    color = ACCENT
                )
            )
        } else if (!state.phoneOnline) {
            row.addContent(text(text = "Offline", size = 11f, color = ACCENT))
        }
        // Centered so the round screen never clips the start of the title.
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
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
            .addContent(row.build())
            .build()
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
                    weight = LayoutElementBuilders.FONT_WEIGHT_MEDIUM
                )
            )
            .addContent(spacer(2f))
            .addContent(
                text(
                    text = if (state.phoneOnline) {
                        "Tap to open messages"
                    } else {
                        "Phone offline · showing saved chats"
                    },
                    size = 11f,
                    color = TERTIARY
                )
            )
            .build()

    private fun row(summary: ThreadSummary): LayoutElementBuilders.LayoutElement {
        val target = if (summary.id.isBlank()) {
            launchTo(null)
        } else {
            launchTo(summary.id)
        }
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(CARD))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(12f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(8f))
                            .setEnd(DimensionBuilders.dp(8f))
                            .setTop(DimensionBuilders.dp(5f))
                            .setBottom(DimensionBuilders.dp(5f))
                            .build()
                    )
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
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(13f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(ColorBuilders.argb(PRIMARY))
                            .build()
                    )
                    .build()
            )
            .addContent(
                text(
                    text = preview(summary),
                    size = 11f,
                    color = SECONDARY
                )
            )
            .build()
    }

    /** One combined line so the footer never pushes rows past the tile edge. */
    private fun footer(state: WatchState, hiddenUnread: Int): LayoutElementBuilders.LayoutElement? {
        val label = tileFooter(hiddenUnread, state.queuedCount) ?: return null
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(text = label, size = 11f, color = TERTIARY))
            .build()
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
        weight: Int = LayoutElementBuilders.FONT_WEIGHT_NORMAL
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(size))
                    .setWeight(weight)
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
        const val MAX_ROWS = 3
        const val BACKGROUND = 0xFF10151C.toInt()
        const val CARD = 0xFF1A222C.toInt()
        const val PRIMARY = 0xFFFFFFFF.toInt()
        const val SECONDARY = 0xFFB8C4D4.toInt()
        const val TERTIARY = 0xFF8A97A8.toInt()
        const val ACCENT = 0xFF7FB2FF.toInt()
    }
}

internal fun tileFooter(hiddenUnread: Int, queued: Int): String? {
    val parts = buildList {
        if (hiddenUnread > 0) add("+$hiddenUnread more unread")
        if (queued > 0) add("$queued queued to send")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
