package com.openbubbles.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.openbubbles.wear.protocol.MessageReaction
import com.openbubbles.wear.protocol.MessageReference
import com.openbubbles.wear.protocol.Tapback
import com.openbubbles.wear.protocol.WireMessage
import kotlinx.coroutines.delay

@Composable
internal fun MessageQuote(quote: MessageReference, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 5.dp)) {
        Text(
            text = "↪ ${if (quote.fromMe) "You" else quote.sender ?: "Reply to message"}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = quote.text.ifBlank { "Original message" },
            style = MaterialTheme.typography.bodyExtraSmall,
            color = color.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ReactionBadges(reactions: List<MessageReaction>, color: Color) {
    reactions.filter { it.count > 0 }.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            row.forEach { reaction ->
                val tapback = Tapback.fromType(reaction.type)
                val symbol = tapback?.symbol ?: when {
                    reaction.type.startsWith("emoji:") -> reaction.type.removePrefix("emoji:")
                    reaction.type == "stickerback" -> "🏷️"
                    else -> "♡"
                }
                Text(
                    text = "$symbol ${reaction.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = if (reaction.fromMe) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = if (reaction.fromMe) 0.28f else 0.12f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .semantics {
                            contentDescription = "${tapback?.label ?: reaction.type}: ${reaction.count}" +
                                if (reaction.fromMe) ", including you" else ""
                        }
                )
            }
        }
    }
}

@Composable
internal fun MessageActionsDialog(
    message: WireMessage,
    canReact: Boolean,
    canReply: Boolean,
    canUnsend: Boolean,
    onDismiss: () -> Unit,
    onReact: (String, Int) -> Unit,
    onReply: () -> Unit,
    onUnsend: () -> Unit
) {
    var partIndex by remember(message.id) { mutableIntStateOf(0) }
    var withinUnsendWindow by remember(message) { mutableStateOf(message.canUnsendAt()) }
    LaunchedEffect(message) {
        if (withinUnsendWindow) {
            delay((message.timestamp + 120_000L - System.currentTimeMillis()).coerceAtLeast(0L))
            withinUnsendWindow = false
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).swipeToDismiss(onDismiss),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            item {
                Text(message.displayText, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (message.canReact) {
                Tapback.entries.chunked(3).forEach { choices ->
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { reaction ->
                                val mine = message.reactions.any {
                                    it.type == reaction.type && it.partIndex == partIndex && it.fromMe
                                }
                                IconButton(
                                    onClick = { onReact(reaction.type, partIndex) },
                                    enabled = canReact,
                                    modifier = Modifier.size(48.dp).clip(CircleShape)
                                        .background(if (mine) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .semantics { selected = mine }
                                ) {
                                    Text(reaction.symbol, fontSize = 23.sp, modifier = Modifier.semantics {
                                        contentDescription = if (mine) "Remove ${reaction.label}" else reaction.label
                                    })
                                }
                            }
                        }
                    }
                }
                val otherParts = message.reactions.map { it.partIndex }.filter { it != 0 }.distinct().sorted()
                if (otherParts.isNotEmpty()) {
                    item {
                        TextButton(onClick = {
                            val parts = listOf(0) + otherParts
                            partIndex = parts[(parts.indexOf(partIndex) + 1) % parts.size]
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Message part ${partIndex + 1}")
                        }
                    }
                }
                if (!canReact) item {
                    Text("Connect your phone to react.", style = MaterialTheme.typography.bodyExtraSmall,
                        textAlign = TextAlign.Center)
                }
            }
            if (message.canReply) item {
                Button(onClick = onReply, enabled = canReply, modifier = Modifier.fillMaxWidth()) { Text("Reply") }
            }
            if (withinUnsendWindow) item {
                Button(onClick = onUnsend, enabled = canUnsend, modifier = Modifier.fillMaxWidth()) { Text("Unsend") }
            }
            if (withinUnsendWindow && !canUnsend) item {
                Text("Connect your phone to unsend.", style = MaterialTheme.typography.bodyExtraSmall,
                    textAlign = TextAlign.Center)
            }
            item {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
internal fun Modifier.swipeToReply(enabled: Boolean, fromMe: Boolean, reverse: Boolean, onReply: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { 44.dp.toPx() }
    val edge = with(LocalDensity.current) { 20.dp.toPx() }
    val reply by rememberUpdatedState(onReply)
    var offset by remember { mutableFloatStateOf(0f) }
    return graphicsLayer { translationX = offset }.pointerInput(enabled, fromMe, reverse, threshold) {
        if (!enabled) return@pointerInput
        val direction = if (fromMe != reverse) 1f else -1f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Keep the left edge available for the watch's back gesture.
            if (down.position.x < edge) return@awaitEachGesture
            var distance = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop * direction > 0) {
                    distance = overSlop
                    offset = distance.coerceIn(-threshold * 1.3f, threshold * 1.3f)
                    change.consume()
                }
            } ?: return@awaitEachGesture
            try {
                val finished = horizontalDrag(start.id) { change ->
                    distance += change.positionChange().x
                    offset = distance.coerceIn(-threshold * 1.3f, threshold * 1.3f)
                    change.consume()
                }
                if (finished && distance * direction >= threshold) reply()
            } finally {
                offset = 0f
            }
        }
    }
}
