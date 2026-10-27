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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState

@Composable
fun SettingsScreen(
    state: WatchState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenPhone: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        !state.phoneOnline -> scheme.error
        !state.notificationAccess -> scheme.error
        !state.relayEnabled -> scheme.tertiary
        else -> scheme.primary
    }
    val unreadCount = state.conversations.count { it.unread }

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
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                    Card(
                        onClick = onSync,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = scheme.surfaceContainerLow
                        )
                    ) {
                        StatusLine(
                            color = statusColor,
                            title = when {
                                state.syncing -> "Syncing…"
                                !state.phoneOnline -> "Phone offline"
                                !state.notificationAccess -> "Notification access is off"
                                !state.relayEnabled -> "Relay is paused"
                                else -> "Connected"
                            },
                            detail = "Tap to refresh the phone connection."
                        )
                    }
                }

                item {
                    InfoCard {
                        Text(
                            text = "Connection",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Phone", state.phoneName.ifBlank { "Unknown" })
                        InfoRow("Bridge", state.bridgeVersion.ifBlank { "Not connected" })
                        InfoRow(
                            "Notification access",
                            if (state.notificationAccess) "Granted" else "Off"
                        )
                        InfoRow(
                            "Relay",
                            if (state.relayEnabled) "On" else "Paused"
                        )
                        InfoRow(
                            "Last sync",
                            if (state.lastSyncAt > 0L) relativeTime(state.lastSyncAt) else "Never"
                        )
                    }
                }

                item {
                    InfoCard {
                        Text(
                            text = "Watch cache",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Conversations", state.conversations.size.toString())
                        InfoRow("Unread", unreadCount.toString())
                        InfoRow("Cached chats", state.threads.size.toString())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "The watch keeps a small copy for offline reading. Open a chat on the phone to refresh its notification.",
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = scheme.onSurfaceVariant
                        )
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
                        onClick = onOpenPhone,
                        enabled = state.phoneOnline,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open on phone")
                    }
                }

                item {
                    InfoCard {
                        Text(
                            text = "About this bridge",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AboutLine("Chats appear once OpenBubbles posts a notification for them.")
                        AboutLine("Muted chats, hidden alerts and old history may be missing.")
                        AboutLine("Photos show as a placeholder. Media isn't copied to the watch.")
                        AboutLine("Replies and mark-read use OpenBubbles' own notification actions, so your account and encryption stay on the phone.")
                    }
                }

                item {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Back to messages")
                    }
                }

                item {
                    SectionHeader("Watch app $WATCH_APP_VERSION")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyExtraSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AboutLine(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
