package com.openbubbles.wear.bridge

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.openbubbles.wear.protocol.Protocol
import java.util.concurrent.TimeUnit

data class BridgeUiState(
    val notificationAccess: Boolean = false,
    val openBubblesInstalled: Boolean = false,
    val relayEnabled: Boolean = true,
    val trackedConversations: Int = 0,
    val watchesConnected: Int = 0,
    val lastPublishAt: Long = 0L,
    val phoneName: String = "",
    val lastWatchName: String = "",
    val bridgeVersion: String = BridgeInfo.BRIDGE_VERSION
)

fun readBridgeUiState(context: Context): BridgeUiState {
    NotificationCenter.init(context)
    val preferences = BridgePreferences(context)
    return BridgeUiState(
        notificationAccess = BridgeInfo.hasNotificationAccess(context),
        openBubblesInstalled = BridgeInfo.isOpenBubblesInstalled(context),
        relayEnabled = NotificationCenter.relayEnabled(context),
        trackedConversations = NotificationCenter.trackedCount(context),
        watchesConnected = reachableWatchCount(context),
        lastPublishAt = NotificationCenter.lastPublishAt(context),
        phoneName = BridgeInfo.phoneName(),
        lastWatchName = preferences.lastSeenWatchName,
        bridgeVersion = BridgeInfo.BRIDGE_VERSION
    )
}

private fun reachableWatchCount(context: Context): Int {
    val capability = runCatching {
        Tasks.await(
            Wearable.getCapabilityClient(context)
                .getCapability(Protocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
            2,
            TimeUnit.SECONDS
        )
    }.getOrNull()
    if (capability != null && capability.nodes.isNotEmpty()) return capability.nodes.size

    val nodes = runCatching {
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
    }.getOrNull()
    return nodes?.size ?: 0
}
