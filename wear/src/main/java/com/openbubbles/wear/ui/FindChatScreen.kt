package com.openbubbles.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.input.RemoteInputIntentHelper
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import com.openbubbles.wear.protocol.ThreadSummary

private const val ADDRESS_INPUT_KEY = "openbubbles_wear_new_address"

@Composable
fun FindChatScreen(
    state: WatchState,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val recipients = state.directChats
    val groups = state.groups

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val address = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(ADDRESS_INPUT_KEY)
            ?.toString()
            ?.trim()
        if (!address.isNullOrEmpty()) {
            WatchRepository.findChatByAddress(address)?.let(onOpenChat)
        }
    }

    fun enterRecipient() {
        val input = RemoteInput.Builder(ADDRESS_INPUT_KEY)
            .setLabel("Phone number or email")
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, "Find a chat")
        runCatching { launcher.launch(intent) }
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
                            text = "Find a chat",
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

                if (!state.phoneOnline) {
                    item {
                        NoticeCard(
                            message = "Phone offline. Only chats already saved on the watch can be opened.",
                            isError = false
                        )
                    }
                }

                item {
                    Button(onClick = onOpenContacts, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose a contact")
                    }
                }

                item {
                    Button(
                        onClick = { enterRecipient() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Enter number or email")
                    }
                }

                if (recipients.isNotEmpty()) {
                    item {
                        SectionHeader("Recent OpenBubbles chats")
                    }
                    items(
                        items = recipients,
                        key = { it.id }
                    ) { summary ->
                        RecipientCard(summary) { onOpenChat(summary.id) }
                    }
                } else {
                    item {
                        Card(
                            onClick = onOpenContacts,
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column {
                                Text(
                                    text = "No recent chats yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Use Choose a contact or enter a number/email to open a chat OpenBubbles has already published or notified. Brand-new chats aren't supported.",
                                    style = MaterialTheme.typography.bodyExtraSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item { SectionHeader("Published chats") }
                item { ChatDiscoveryControls(state) }
                items(groups, key = { "group-${it.id}" }) { group ->
                    RecipientCard(group) { onOpenChat(group.id) }
                }
                if (groups.isEmpty()) item {
                    Text("Find groups and chats published as Android shortcuts or notification channels using Shizuku on the phone, without a notification. Other chats can still appear from notifications; the private chat list is not read.",
                        style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                item {
                    Text(
                        text = "Sends are performed by OpenBubbles on your phone. Your account and encryption stay there.",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }            }
        }
    }
}

@Composable
private fun RecipientCard(summary: ThreadSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkPlaceholder(initials = initialsFor(summary.title))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (summary.isGroup) "Group chat" else summary.address ?: "OpenBubbles conversation",
                    style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
