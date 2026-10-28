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
            Text(if (state.discoveryRequestId != null) "Finding chats." else "Find chats (Shizuku)")
        }
        Text(
            state.discoveryNotice
                ?: "Start Shizuku and grant the phone bridge access. Reads OpenBubbles' published Android shortcuts and notification channels without needing a notification.",
            style = MaterialTheme.typography.bodyExtraSmall
        )
    }
}
