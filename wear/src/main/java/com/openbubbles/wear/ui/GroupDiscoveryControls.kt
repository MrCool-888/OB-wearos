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
fun GroupDiscoveryControls(state: WatchState) {
    Column {
        Button(onClick = { WatchRepository.discoverGroups() },
            enabled = state.groupsRequestId == null && state.phoneOnline && state.relayEnabled,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (state.groupsRequestId != null) "Finding groups…" else "Find groups (Shizuku)")
        }
        Text(state.groupsNotice ?: "Start Shizuku and grant the phone bridge access. Finds only groups OpenBubbles published as Android shortcuts.",
            style = MaterialTheme.typography.bodyExtraSmall)
    }
}
