package com.openbubbles.wear.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread

@Composable
fun ChatScreen(
    state: WatchState,
    threadId: String,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onOpenPhone: () -> Unit,
    onLoadOlder: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val thread = state.threads[threadId]
    val summary = state.conversations.firstOrNull { it.id == threadId }
    val messages = thread?.messages.orEmpty()
    val newestMessageId = messages.lastOrNull()?.id
    val listState = rememberScalingLazyListState()
    val requesting = threadId in state.requestingThreads
    val canSend = thread?.canSend == true || summary?.canSend == true

    val title = thread?.title ?: summary?.title ?: "Conversation"
    val isGroup = thread?.isGroup ?: summary?.isGroup ?: false
    val participants = thread?.participants ?: summary?.participants.orEmpty()

    LaunchedEffect(threadId) {
        WatchRepository.requestThread(threadId)
        if (messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(messages.size + 1) }
        }
    }
    // Loading older messages prepends items without changing the newest ID, so
    // only a new message should pull the list back to the bottom.
    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null) {
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
                item {
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
                        val notice = state.error ?: state.info
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

                item {
                    ChatStatus(
                        state = state,
                        thread = thread,
                        hasSummary = summary != null,
                        requesting = requesting,
                        canSend = canSend
                    )
                }

                if (thread == null && summary == null) {
                    item {
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
                                    text = "Open this chat in OpenBubbles on the phone so its notification cache is refreshed, then try again.",
                                    style = MaterialTheme.typography.bodyExtraSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (messages.isEmpty()) {
                    item {
                        Card(
                            onClick = { WatchRepository.requestThread(threadId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No message history yet. Tap to refresh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { message ->
                        MessageBubble(
                            message = message,
                            showSender = isGroup && !message.fromMe
                        )
                    }
                }

                item {
                    ChatActions(
                        state = state,
                        thread = thread,
                        threadId = threadId,
                        requesting = requesting,
                        canSend = canSend,
                        onReply = onReply,
                        onOpenPhone = onOpenPhone,
                        onLoadOlder = onLoadOlder,
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
    canSend: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val color: Color
    val title: String
    val detail: String
    when {
        thread == null && hasSummary -> {
            color = scheme.primary
            title = if (requesting) "Loading conversation…" else "Waiting for the phone"
            detail = "The watch is asking the phone for the latest message history."
        }

        thread == null -> {
            color = scheme.error
            title = "Not loaded"
            detail = "This chat isn't cached on the watch yet."
        }

        !state.phoneOnline -> {
            color = scheme.tertiary
            title = "Phone offline"
            detail = "Showing the last messages cached on the watch."
        }

        canSend && !thread.canReply -> {
            color = scheme.primary
            title = "Send through your phone"
            detail = "No live reply action. The phone will open this chat in OpenBubbles to send."
        }

        !thread.notificationActive -> {
            color = scheme.tertiary
            title = "History only"
            detail = "Enable automation in the bridge app to send in this chat."
        }

        !thread.canReply -> {
            color = scheme.tertiary
            title = "Read only"
            detail = "This notification doesn't have a reply action right now."
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
    showSender: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val fromMe = message.fromMe
    val container = if (fromMe) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (fromMe) scheme.onPrimaryContainer else scheme.onSurface
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
            Text(
                text = message.displayText,
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
            if (message.attachment && message.text.isNotBlank()) {
                Text(
                    text = "Photo attached",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.75f)
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
                    color = content.copy(alpha = 0.7f)
                )
                when (message.status) {
                    "sending" -> Text(
                        text = " · Sending…",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.7f)
                    )

                    "failed" -> Text(
                        text = " · Not sent",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatActions(
    state: WatchState,
    thread: WireThread?,
    threadId: String,
    requesting: Boolean,
    canSend: Boolean,
    onReply: () -> Unit,
    onOpenPhone: () -> Unit,
    onLoadOlder: () -> Unit,
    onRefresh: () -> Unit,
    onMarkRead: () -> Unit
) {
    val canReply = thread?.canReply == true
    val canMarkRead = thread?.canMarkRead == true
    val sending = threadId in state.sendingThreads
    val loadingHistory = threadId in state.loadingHistory
    val historyExhausted = threadId in state.historyExhausted
    val canLoadHistory = state.automationAvailable &&
        thread?.messages?.isNotEmpty() == true &&
        state.phoneOnline &&
        state.relayEnabled
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onReply,
            enabled = (canReply || canSend) && state.phoneOnline && !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    sending -> "Sending…"
                    canReply -> "Reply"
                    canSend -> "Send message"
                    else -> "Reply unavailable"
                }
            )
        }

        if (canSend && !canReply) {
            Text(
                text = "The phone will open this chat in OpenBubbles to send.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        if (state.automationAvailable) {
            if (historyExhausted) {
                Text(
                    text = "No older messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            } else {
                FilledTonalButton(
                    onClick = onLoadOlder,
                    enabled = canLoadHistory && !loadingHistory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loadingHistory) "Loading older…" else "Load older messages")
                }
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
