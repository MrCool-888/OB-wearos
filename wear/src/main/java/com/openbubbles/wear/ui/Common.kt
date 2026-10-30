package com.openbubbles.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun NoticeCard(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (isError) scheme.errorContainer else scheme.surfaceContainerHigh
    val content = if (isError) scheme.onErrorContainer else scheme.onSurface
    Card(
        onClick = { onDismiss?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = content
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

@Composable
fun StatusLine(
    color: Color,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FullScreenLoading(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ArtworkPlaceholder(
    initials: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
fun ContactAvatar(
    state: WatchState,
    avatarId: String?,
    initials: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val media = avatarId?.let { state.media[it] }
    LaunchedEffect(avatarId, media, state.phoneOnline, state.directBridge, state.relayEnabled) {
        if (avatarId != null && media == null && state.phoneOnline && state.directBridge && state.relayEnabled) {
            WatchRepository.requestMedia(avatarId)
        }
    }
    if (media == null) {
        ArtworkPlaceholder(initials, modifier.size(size), color, contentColor)
    } else {
        val painter = rememberAsyncImagePainter(File(media.path))
        if (painter.state is coil.compose.AsyncImagePainter.State.Error) {
            ArtworkPlaceholder(initials, modifier.size(size), color, contentColor)
        } else {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = modifier.size(size).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
    }
}

fun initialsFor(title: String): String {
    val parts = title
        .split(' ', ',', '-', '_')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(2).uppercase(Locale.getDefault())
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.getDefault())
    }
}

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 7L * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(timestamp))
    }
}

fun clockTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(timestamp))
}

fun chatSubtitle(isGroup: Boolean, participants: List<String>): String = when {
    isGroup && participants.isNotEmpty() -> "${participants.size} people"
    isGroup -> "Group chat"
    participants.firstOrNull()?.isNotBlank() == true -> participants.first()
    else -> "Conversation"
}

/**
 * Watch apps conventionally dismiss a screen with a rightward swipe. The system
 * back gesture is also handled by the activity, but this makes the interaction
 * discoverable on devices with a crown/back button.
 */
@Composable
fun Modifier.swipeToDismiss(onDismiss: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    return pointerInput(onDismiss, threshold) {
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f },
            onHorizontalDrag = { change, dragAmount ->
                accumulated += dragAmount
                if (accumulated > 0f) change.consume()
            },
            onDragEnd = {
                if (accumulated >= threshold) onDismiss()
                accumulated = 0f
            },
            onDragCancel = { accumulated = 0f }
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun IconLabel(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
