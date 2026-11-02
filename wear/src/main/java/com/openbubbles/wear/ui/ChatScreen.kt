package com.openbubbles.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import coil.compose.AsyncImage
import com.openbubbles.wear.WatchMedia
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.io.File

@Composable
fun ChatScreen(
    state: WatchState,
    threadId: String,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onOpenPhone: () -> Unit,
    onOpenMedia: (String) -> Unit,
    onSendQueued: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val thread = state.threads[threadId]
    val summary = state.conversations.firstOrNull { it.id == threadId }
    val messages = thread?.messages.orEmpty()
    val listState = rememberScalingLazyListState()
    val scrollPosition = remember(threadId) { ChatScrollPosition() }
    val requesting = threadId in state.requestingThreads
    val canReply = state.canReplyTo(threadId)

    val title = thread?.title ?: summary?.title ?: "Conversation"
    val isGroup = thread?.isGroup ?: summary?.isGroup ?: false
    val participants = thread?.participants ?: summary?.participants.orEmpty()

    LaunchedEffect(threadId) {
        WatchRepository.requestThread(threadId)
    }
    LaunchedEffect(threadId, messages.map { it.id }) {
        if (scrollPosition.update(messages,
                atLatest = listState.centerItemIndex >= scrollPosition.messageCount,
                scrolling = listState.isScrollInProgress)) {
            runCatching { listState.scrollToItem(messages.size + 1) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .swipeToDismiss(onBack)
    ) {
        AppScaffold(timeText = { TimeText() }) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 30.dp,
                    bottom = 44.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "chat-header") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            ContactAvatar(
                                state = state,
                                avatarId = thread?.avatarId ?: summary?.avatarId,
                                initials = initialsFor(title),
                                size = 32.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = chatSubtitle(isGroup, participants),
                                    style = MaterialTheme.typography.bodyExtraSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        val notice = state.error
                        if (notice != null) {
                            Spacer(Modifier.height(6.dp))
                            NoticeCard(
                                message = notice,
                                isError = state.error != null,
                                onDismiss = onDismissNotice
                            )
                        }
                    }
                }

                item(key = "chat-status") {
                    ChatStatus(
                        state = state,
                        thread = thread,
                        hasSummary = summary != null,
                        requesting = requesting,
                        canReply = canReply
                    )
                }

                if (thread == null && summary == null) {
                    item(key = "chat-unavailable") {
                        Card(
                            onClick = { WatchRepository.requestThread(threadId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "Conversation not available",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Enable the Wear patch in OpenBubbles, then refresh this conversation.",
                                    style = MaterialTheme.typography.bodyExtraSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (messages.isEmpty()) {
                    item(key = "chat-empty") {
                        NoticeCard(
                            message = if (state.directBridge) "No messages loaded. Refresh the conversation to load recent history."
                                else "Enable the Wear patch in OpenBubbles to load conversation history.",
                            isError = false
                        )
                    }
                } else {
                    items(
                        items = messages,
                        key = { "message:${it.id}" }
                    ) { message ->
                        MessageBubble(
                            message = message,
                            media = message.mediaId?.let { state.media[it] },
                            requestingMedia = message.mediaId != null &&
                                message.mediaId in state.requestingMedia,
                            showSender = isGroup && !message.fromMe,
                            onOpenMedia = onOpenMedia
                        )
                    }
                }

                if (state.error == null && state.info != null) {
                    item(key = "chat-info") {
                        NoticeCard(message = state.info, isError = false, onDismiss = onDismissNotice)
                    }
                }

                item(key = "chat-actions") {
                    ChatActions(
                        state = state,
                        threadId = threadId,
                        canReply = canReply,
                        requesting = requesting,
                        onReply = onReply,
                        onOpenPhone = onOpenPhone,
                        onSendQueued = onSendQueued,
                        onRefresh = { WatchRepository.requestThread(threadId) },
                        onMarkRead = { WatchRepository.markRead(threadId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatStatus(
    state: WatchState,
    thread: WireThread?,
    hasSummary: Boolean,
    requesting: Boolean,
    canReply: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val color: Color
    val title: String
    val detail: String
    when {
        thread == null && hasSummary -> {
            color = scheme.primary
            title = if (requesting) "Loading conversation…" else "Waiting for the phone"
            detail = "The watch is asking the phone for the latest messages."
        }

        thread == null -> {
            color = scheme.error
            title = "Not loaded"
            detail = "This chat isn't cached on the watch yet."
        }

        !state.phoneOnline -> {
            color = scheme.tertiary
            title = "Phone offline"
            detail = if (state.queuedCount > 0) {
                "Showing cached messages. Your reply is queued until the phone reconnects."
            } else {
                "Showing the last messages cached on the watch. Sends will be queued."
            }
        }

        !canReply -> {
            color = scheme.tertiary
            title = "Read only"
            detail = "Enable the Wear patch in OpenBubbles to send messages."
        }

        else -> {
            color = scheme.primary
            title = "Live through your phone"
            detail = "Your normal OpenBubbles account and encryption are used."
        }
    }
    StatusLine(
        color = color,
        title = title,
        detail = detail,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

@Composable
private fun MessageBubble(
    message: WireMessage,
    media: WatchMedia?,
    requestingMedia: Boolean,
    showSender: Boolean,
    onOpenMedia: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val fromMe = message.fromMe
    val container = if (fromMe) Color(0xFF0066CC) else scheme.surfaceContainerHigh
    val content = if (fromMe) Color.White else scheme.onSurface
    val secondaryContent = content.copy(alpha = if (fromMe) 0.9f else 0.7f)
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (fromMe) 16.dp else 4.dp,
        bottomEnd = if (fromMe) 4.dp else 16.dp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(shape)
                .background(container)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            val sender = message.sender
            if (showSender && !sender.isNullOrBlank()) {
                Text(
                    text = sender,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary
                )
            }
            if (message.attachment) {
                AttachmentBubble(
                    message = message,
                    media = media,
                    requesting = requestingMedia,
                    content = content,
                    onOpenMedia = onOpenMedia
                )
                if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
            }
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            } else if (!message.attachment) {
                Text(
                    text = message.displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clockTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryContent
                )
                when (message.status) {
                    "sending" -> Text(
                        text = " · Sending…",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContent
                    )

                    "queued" -> Text(
                        text = " · Queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.8f)
                    )

                    "failed" -> Text(
                        text = " · Not sent",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fromMe) content else scheme.error,
                        fontWeight = FontWeight.SemiBold
                    )

                    "sent" -> Text(
                        text = " · Sent",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContent
                    )

                    "delivered" -> Text(
                        text = " · Delivered",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContent
                    )

                    "read" -> Text(
                        text = " · Read",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContent
                    )

                    "unconfirmed" -> Text(
                        text = " · Not confirmed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fromMe) content else scheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentBubble(
    message: WireMessage,
    media: WatchMedia?,
    requesting: Boolean,
    content: Color,
    onOpenMedia: (String) -> Unit
) {
    val mediaId = message.mediaId
    val isGif = media?.isGif == true || message.mediaMime == "image/gif"
    val thumbnailShape = RoundedCornerShape(10.dp)
    when {
        media != null && mediaId != null -> {
            AsyncImage(
                model = File(media.path),
                contentDescription = if (isGif) "Animated GIF" else "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(thumbnailShape)
                    .clickable { onOpenMedia(mediaId) }
            )
            Text(
                text = if (isGif) "Tap to play GIF" else "Tap to view photo",
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        mediaId != null && requesting -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(thumbnailShape)
                    .background(content.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isGif) "Loading GIF…" else "Loading photo…",
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }

        mediaId != null -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(thumbnailShape)
                    .background(content.copy(alpha = 0.12f))
                    .clickable { WatchRepository.requestMedia(mediaId) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isGif) "Load GIF" else "Load photo",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = content
                    )
                    Text(
                        text = "From your phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.7f)
                    )
                }
            }
        }

        else -> {
            Text(
                text = message.displayText,
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
        }
    }
}

@Composable
private fun ChatActions(
    state: WatchState,
    threadId: String,
    canReply: Boolean,
    requesting: Boolean,
    onReply: () -> Unit,
    onOpenPhone: () -> Unit,
    onSendQueued: () -> Unit,
    onRefresh: () -> Unit,
    onMarkRead: () -> Unit
) {
    val canMarkRead = state.directBridge && (state.threads[threadId]?.canMarkRead == true ||
        state.conversations.firstOrNull { it.id == threadId }?.canMarkRead == true)
    val sending = threadId in state.sendingThreads
    val queuedCount = state.queuedSends.count { it.threadId == threadId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onReply,
            enabled = canReply && state.relayEnabled && !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    sending -> "Sending…"
                    !state.phoneOnline && canReply -> "Queue reply"
                    canReply -> "Reply"
                    else -> "Reply unavailable"
                }
            )
        }

        if (!state.phoneOnline && canReply) {
            Text(
                text = "Phone offline. Messages are saved on the watch and sent when it reconnects.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        if (queuedCount > 0) {
            Text(
                text = "$queuedCount queued ${if (queuedCount == 1) "message" else "messages"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            FilledTonalButton(
                onClick = onSendQueued,
                enabled = state.phoneOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        state.phoneOnline -> "Send queued now"
                        else -> "Queued until reconnect"
                    }
                )
            }
        }

        if (canMarkRead) {
            FilledTonalButton(
                onClick = onMarkRead,
                enabled = state.phoneOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mark as read")
            }
        }

        TextButton(
            onClick = onOpenPhone,
            enabled = state.phoneOnline,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open on phone")
        }

        TextButton(
            onClick = onRefresh,
            enabled = !requesting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (requesting) "Refreshing…" else "Refresh chat")
        }
    }
}
