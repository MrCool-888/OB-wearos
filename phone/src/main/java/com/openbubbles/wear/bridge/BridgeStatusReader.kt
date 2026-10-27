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
    val openBubblesVersion: String = "",
    val openBubblesPackage: String = "",
    val automationAvailable: Boolean = false,
    val automationEnabled: Boolean = false,
    val legacyBridgeInstalled: Boolean = false,
    val relayEnabled: Boolean = true,
    val listenerConnected: Boolean = false,
    val lastNotificationAt: Long = 0L,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val trackedConversations: Int = 0,
    val cachedMedia: Int = 0,
    val watchesConnected: Int = 0,
    val lastPublishAt: Long = 0L,
    val phoneName: String = "",
    val lastWatchName: String = "",
    val bridgeVersion: String = BridgeInfo.BRIDGE_VERSION
) {
    val automationStatus: String get() = when {
        automationAvailable -> "On. Service connected; new chats and older history are available."
        automationEnabled -> "On in Android settings; service not connected yet. If this persists, switch it off and on once under Accessibility → Installed apps."
        else -> "Off. Replies to live notifications still work."
    }
}

fun readBridgeUiState(context: Context): BridgeUiState {
    NotificationCenter.init(context)
    val preferences = BridgePreferences(context)
    return BridgeUiState(
        notificationAccess = BridgeInfo.hasNotificationAccess(context),
        openBubblesInstalled = BridgeInfo.isOpenBubblesInstalled(context),
        openBubblesVersion = BridgeInfo.openBubblesVersion(context),
        openBubblesPackage = BridgeInfo.openBubblesPackage(context).orEmpty(),
        automationAvailable = BridgeInfo.isAutomationReady(context),
        automationEnabled = BridgeInfo.hasAutomationAccess(context),
        legacyBridgeInstalled = runCatching {
            context.packageManager.getPackageInfo("com.openbubbles.wear.bridge", 0)
        }.isSuccess,
        relayEnabled = NotificationCenter.relayEnabled(context),
        listenerConnected = NotificationCenter.listenerConnected(),
        lastNotificationAt = NotificationCenter.lastNotificationAt(context),
        shizukuAvailable = ShizukuSetup.isAvailable(),
        shizukuPermission = ShizukuSetup.hasPermission(),
        shizukuInstalled = ShizukuSetup.isManagerInstalled(context),
        batteryUnrestricted = ShizukuSetup.batteryUnrestricted(context),
        trackedConversations = NotificationCenter.trackedCount(context),
        cachedMedia = NotificationCenter.mediaCount(context),
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
