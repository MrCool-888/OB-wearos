package com.openbubbles.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.input.RemoteInputIntentHelper
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState

private const val REPLY_INPUT_KEY = "openbubbles_wear_reply"

@Composable
fun ComposerScreen(
    state: WatchState,
    threadId: String,
    onBack: () -> Unit,
    onSent: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val thread = state.threads[threadId]
    val summary = state.conversations.firstOrNull { it.id == threadId }
    val title = thread?.title ?: summary?.title ?: "Conversation"
    val canReply = (thread?.canReply ?: summary?.canReply) == true
    val canSend = (thread?.canSend ?: summary?.canSend) == true
    val enabled = (canReply || canSend) && state.relayEnabled
    val sending = threadId in state.sendingThreads
    val listState = rememberScalingLazyListState()
    var voiceError by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val results = result.data?.let { RemoteInput.getResultsFromIntent(it) }
        val reply = results?.getCharSequence(REPLY_INPUT_KEY)?.toString()?.trim()
        if (!reply.isNullOrEmpty()) {
            if (WatchRepository.sendReply(threadId, reply)) onSent()
        }
    }

    fun launchVoiceInput() {
        voiceError = null
        val input = RemoteInput.Builder(REPLY_INPUT_KEY)
            .setLabel("Reply")
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, "Reply to $title")
        runCatching { launcher.launch(intent) }
            .onFailure { voiceError = "Voice or keyboard input is not available on this watch." }
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
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SectionHeader(if (canReply) "Reply" else "Message")
                }

                if (sending) {
                    item {
                        NoticeCard(
                            message = "Sending through OpenBubbles on your phone…",
                            isError = false
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
                if (voiceError != null) {
                    item {
                        NoticeCard(
                            message = voiceError.orEmpty(),
                            isError = true,
                            onDismiss = { voiceError = null }
                        )
                    }
                }
                if (!enabled) {
                    item {
                        NoticeCard(
                            message = when {
                                !state.relayEnabled ->
                                    "Relay is paused on the phone. Resume it in the bridge app to send."

                                else ->
                                    "Enable automation in the bridge app to send in this chat without a live notification."
                            },
                            isError = false
                        )
                    }
                } else if (!state.phoneOnline) {
                    item {
                        NoticeCard(
                            message = "Phone offline. Your message is saved to the outbox and sent when the phone reconnects.",
                            isError = false
                        )
                    }
                }

                val quickReplies = state.quickReplies.ifEmpty {
                    listOf("On my way", "Yes", "No", "Thanks!")
                }
                quickReplies.forEachIndexed { index, reply ->
                    item(key = "quick-$index") {
                        Button(
                            onClick = {
                                if (WatchRepository.sendReply(threadId, reply)) onSent()
                            },
                            enabled = enabled && !sending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(reply)
                        }
                    }
                }

                item {
                    FilledTonalButton(
                        onClick = { launchVoiceInput() },
                        enabled = enabled && !sending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (canReply) "Speak or type a reply" else "Speak or type a message")
                    }
                }

                item {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
