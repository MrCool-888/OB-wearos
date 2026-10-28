package com.openbubbles.wear.bridge

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

object BridgeInfo {
    const val BRIDGE_VERSION = "2.0.1"

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

    /** Only used to describe the phone state; sending never waits for unlock. */
    fun isPhoneLocked(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }.getOrDefault(false)

    fun isPhoneAwake(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(true)

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
