package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Settings links and an explicitly requested, bridge-only restricted-settings recovery. */
object NotificationAccessSetup {
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun listenerIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationRelayService::class.java).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Injectable command runner keeps failure/verification behavior testable without Shizuku. */
    internal fun grant(
        packageName: String,
        component: String,
        userId: Int,
        sdkInt: Int = Build.VERSION.SDK_INT,
        unblock: Boolean,
        command: (String) -> ShizukuSetup.ShellResult,
        isGranted: () -> Boolean,
        rebind: () -> Unit,
        pause: (Long) -> Unit = { Thread.sleep(it) }
    ): ShizukuSetup.StepResult {
        if (isGranted()) {
            rebind()
            return ShizukuSetup.StepResult(true, "Notification access is already on.")
        }
        // This changes only our package, never a device-wide security policy.
        // It is invoked only after the user explicitly chooses the unblock action.
        if (unblock && sdkInt >= 33) {
            command("cmd appops set --user $userId ${quote(packageName)} ACCESS_RESTRICTED_SETTINGS allow")
        }
        val result = command("cmd notification allow_listener ${quote(component)} $userId")
        // Some Android/OEM commands return exit code zero despite denying a grant.
        // The system's actual component-specific grant is the source of truth.
        repeat(10) { attempt ->
            if (isGranted()) {
                rebind()
                return ShizukuSetup.StepResult(true, "Notification access is on. Reconnecting the listener…")
            }
            if (attempt < 9) pause(150)
        }
        return ShizukuSetup.StepResult(false, buildString {
            append("Android has not granted notification access. Open App info → ⋮ → Allow restricted settings, confirm, then return to Notification access and enable it. ")
            append("If Android says it is blocked by an administrator, contact the device administrator; this app cannot override that policy.")
            if (!result.ok && result.output.isNotBlank()) append("\n${result.output.take(400)}")
        })
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
