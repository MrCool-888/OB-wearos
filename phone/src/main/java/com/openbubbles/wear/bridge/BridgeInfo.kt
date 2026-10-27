package com.openbubbles.wear.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object BridgeInfo {
    const val BRIDGE_VERSION = "1.0.0"

    val OPENBUBBLES_PACKAGES = setOf(
        "com.openbubbles.messaging",
        "com.bluebubbles.messaging"
    )

    fun isOpenBubblesPackage(packageName: String?): Boolean =
        packageName != null && packageName in OPENBUBBLES_PACKAGES

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

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
