package com.openbubbles.wear.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import com.openbubbles.wear.protocol.ThreadSummary

@Composable
fun ConversationListScreen(
    state: WatchState,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onFindChat: () -> Unit,
    onSync: () -> Unit,
    onOpenPhone: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val unreadCount = remember(state.conversations) {
        state.conversations.count { it.unread }
    }
    var groupsOnly by remember { mutableStateOf(false) }
    val visibleConversations = state.conversations.filter { !groupsOnly || it.isGroup }

    AppScaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            if (state.loading) {
                FullScreenLoading("Looking for your phone…")
                return@Box
            }
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
                    ListHeader {
                        Text(
                            text = "Messages",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                item {
                    PhoneStatusCard(
                        state = state,
                        unreadCount = unreadCount,
                        onSync = onSync
                    )
                }

                val notice = state.error ?: state.info
                if (notice != null) {
                    item {
                        NoticeCard(
                            message = notice,
                            isError = state.error != null,
                            onDismiss = onDismissNotice
                        )
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { groupsOnly = false }) { Text(if (!groupsOnly) "All ✓" else "All") }
                        TextButton(onClick = { groupsOnly = true }) { Text(if (groupsOnly) "Groups ✓" else "Groups") }
                    }
                }

                if (groupsOnly && visibleConversations.isEmpty()) {
                    item {
                        Card(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                            Text("No group chats yet", style = MaterialTheme.typography.titleSmall)
                            Text(if (state.directBridge) "Use Find chats in OpenBubbles below to load existing groups."
                                else "Open patched OpenBubbles and enable the Wear bridge, then find chats below.",
                                style = MaterialTheme.typography.bodyExtraSmall)
                        }
                    }
                } else if (state.conversations.isEmpty()) {
                    item {
                        EmptyStateCard(
                            directBridge = state.directBridge,
                            phoneOnline = state.phoneOnline,
                            onSync = onSync
                        )
                    }
                } else {
                    items(
                        items = visibleConversations,
                        key = { it.id }
                    ) { summary ->
                        ConversationCard(
                            state = state,
                            summary = summary,
                            onClick = { onOpenThread(summary.id) }
                        )
                    }
                }

                item { ChatDiscoveryControls(state) }

                item {
                    Button(
                        onClick = onFindChat,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Find a chat")
                    }
                }

                item {
                    Button(
                        onClick = onSync,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconLabel(
                            icon = Icons.Filled.Refresh,
                            text = if (state.syncing) "Syncing…" else "Sync now"
                        )
                    }
                }

                item {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Settings")
                    }
                }

                if (!state.phoneOnline) {
                    item {
                        TextButton(
                            onClick = onOpenPhone,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open on phone")
                        }
                    }
                }

                item {
                    SectionHeader(
                        text = if (state.phoneName.isNotBlank()) {
                            "${state.phoneName} · Watch ${WATCH_APP_VERSION}"
                        } else {
                            "Watch $WATCH_APP_VERSION"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneStatusCard(
    state: WatchState,
    unreadCount: Int,
    onSync: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        !state.phoneOnline -> scheme.error
        !state.directBridge -> scheme.error
        !state.relayEnabled -> scheme.tertiary
        state.phoneLocked -> scheme.tertiary
        else -> scheme.primary
    }
    val title = when {
        state.syncing -> "Syncing…"
        !state.phoneOnline -> "Phone offline"
        !state.directBridge -> "Wear patch unavailable"
        !state.relayEnabled -> "Relay paused on phone"
        state.phoneLocked -> "Phone locked"
        else -> "Connected to phone"
    }
    val detail = when {
        !state.phoneOnline && state.lastSyncAt > 0L ->
            "Showing the last synced conversations. Tap to retry."

        !state.phoneOnline ->
            "Open the bridge app on your phone."

        !state.directBridge ->
            state.backendState.ifBlank { "Enable the Wear patch in OpenBubbles and sync again." }

        !state.relayEnabled ->
            "New messages stay on the phone until relay is resumed."

        state.phoneLocked && state.queuedCount > 0 ->
            "Replies send through OpenBubbles. ${state.queuedCount} ${if (state.queuedCount == 1) "message is" else "messages are"} waiting for the phone."

        state.phoneLocked ->
            "Replies send through OpenBubbles without unlocking."

        unreadCount > 0 ->
            "$unreadCount unread ${if (unreadCount == 1) "chat" else "chats"} · Tap to refresh"

        else -> "Tap to refresh"
    }

    Card(
        onClick = onSync,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerLow
        )
    ) {
        StatusLine(
            color = statusColor,
            title = title,
            detail = detail
        )
    }
}

@Composable
private fun EmptyStateCard(
    directBridge: Boolean,
    phoneOnline: Boolean,
    onSync: () -> Unit
) {
    Card(
        onClick = onSync,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !phoneOnline ->
                        "Open the bridge app on your phone, then sync again."

                    directBridge ->
                        "Use Find chats in OpenBubbles, or choose a contact to start a conversation."

                    else ->
                        "Enable the Wear patch in OpenBubbles to load conversations."
                },
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap to sync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConversationCard(
    state: WatchState,
    summary: ThreadSummary,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (summary.unread) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(
                state = state,
                avatarId = summary.avatarId,
                initials = initialsFor(summary.title),
                color = if (summary.unread) scheme.primaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (summary.unread) scheme.onPrimaryContainer else scheme.onSurface
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (summary.unread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (summary.unread) {
                        Spacer(Modifier.width(5.dp))
                        StatusDot(scheme.primary)
                    }
                }
                Text(
                    text = summary.lastPreview.ifBlank { "No messages" },
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (summary.isGroup) "Group" else "Chat",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = relativeTime(summary.lastTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
