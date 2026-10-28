package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Shareable permission diagnostics; deliberately excludes messages, contacts and notification text. */
internal object BridgeDiagnostics {
    fun create(context: Context, state: BridgeUiState): String {
        val listener = ComponentName(context, NotificationRelayService::class.java)
        val configuredListener = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull()
        return buildString {
            appendLine("OpenBubbles Wear permission diagnostics ${state.bridgeVersion}")
            appendLine("Device: ${BridgeInfo.phoneName()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Installed package: ${context.packageName}")
            appendLine("Old bridge also installed: ${state.legacyBridgeInstalled}")
            appendLine("Notification listener: ${listener.flattenToString()}")
            appendLine("Listener access granted: ${state.notificationAccess}")
            appendLine("Listener listed in secure settings: ${PermissionComponents.contains(configuredListener, listener)}")
            appendLine("Listener connected: ${state.listenerConnected}")
            appendLine("Phone notification posting: not used; App info → Notifications is not listener access")
            appendLine("Shizuku running: ${state.shizukuAvailable}")
            appendLine("Shizuku permission: ${state.shizukuPermission}")
            appendLine("Battery exempt: ${state.batteryUnrestricted}")
            appendLine("Reachable watches: ${state.watchesConnected}")
        }
    }
}
