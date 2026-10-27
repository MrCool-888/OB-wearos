package com.openbubbles.wear.bridge

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object BridgeInfo {
    const val BRIDGE_VERSION = "1.5.0"

    val OPENBUBBLES_PACKAGES = setOf(
        "com.openbubbles.messaging",
        "com.bluebubbles.messaging"
    )

    fun isOpenBubblesPackage(packageName: String?): Boolean =
        packageName != null && packageName in OPENBUBBLES_PACKAGES

    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context, NotificationRelayService::class.java)
        )
    }.getOrDefault(false)

    /**
     * Optional accessibility automation. It is only used for sending in chats
     * without a live notification and for loading older messages. Normal
     * replies still use OpenBubbles' notification actions.
     */
    fun hasAutomationAccess(context: Context): Boolean {
        // A connected instance is direct evidence, even while OEM settings APIs
        // are still propagating their enabled-services list.
        if (OpenBubblesAutomation.isReady()) return true
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        val component = ComponentName(context, OpenBubblesAutomationService::class.java)
        val listed = runCatching {
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { PermissionComponents.matches(it.id, component) } == true
        }.getOrDefault(false)
        return listed || PermissionComponents.contains(enabledAccessibilityServices(context), component)
    }

    fun isAutomationReady(context: Context): Boolean =
        hasAutomationAccess(context) && OpenBubblesAutomation.isReady()

    internal fun enabledAccessibilityServices(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun isOpenBubblesInstalled(context: Context): Boolean =
        OPENBUBBLES_PACKAGES.any { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
            }.isSuccess
        }

    fun openBubblesPackage(context: Context): String? =
        OPENBUBBLES_PACKAGES.firstOrNull { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
            }.isSuccess
        }

    fun openBubblesVersion(context: Context): String {
        val packageName = openBubblesPackage(context) ?: return ""
        return runCatching {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            info.versionName.orEmpty()
        }.getOrDefault("")
    }

    fun phoneName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            model.isEmpty() -> manufacturer.ifEmpty { "Android phone" }
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    fun notificationSummary(context: Context): Int {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications?.size ?: 0
    }

    fun packageLabel(context: Context, packageName: String?): String? {
        if (packageName == null) return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
