package com.openbubbles.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState

@Composable
fun ChatDiscoveryControls(state: WatchState) {
    Column {
        Button(
            onClick = { WatchRepository.discoverChats() },
            enabled = state.discoveryRequestId == null && state.phoneOnline && state.relayEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(when {
                state.discoveryRequestId != null -> "Finding chats…"
                else -> "Find chats in OpenBubbles"
            })
        }
        Text(
            state.discoveryNotice
                ?: if (state.directBridge) "Reads chats directly with the Wear patch. Open a chat to load recent history."
                else "Open patched OpenBubbles and enable the Wear bridge in Settings → Morphe patches.",
            style = MaterialTheme.typography.bodyExtraSmall
        )
    }
}
