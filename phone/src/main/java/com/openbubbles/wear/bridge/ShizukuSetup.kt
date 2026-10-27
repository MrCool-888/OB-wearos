package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Optional convenience setup through Shizuku.
 *
 * Nothing here is required: every step has a manual equivalent in Android
 * settings. Shizuku runs shell commands through the `shell` user, never root,
 * and OpenBubbles itself is untouched. The commands only grant the bridge's own
 * notification-listener/accessibility components and exempt it from battery
 * optimization.
 */
object ShizukuSetup {
    const val PERMISSION_REQUEST_CODE = 1129
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val WEBSITE = "https://shizuku.rikka.app/"

    data class ShellResult(val ok: Boolean, val output: String)
    data class StepResult(val ok: Boolean, val message: String)

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun isManagerInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
    }.isSuccess

    fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (hasPermission()) return true
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        return false
    }

    fun openManager(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
        if (launch != null) {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
    }

    fun notificationAccessGranted(context: Context): Boolean =
        BridgeInfo.hasNotificationAccess(context)

    fun automationGranted(context: Context): Boolean =
        BridgeInfo.hasAutomationAccess(context)

    fun batteryUnrestricted(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun enableNotificationAccess(context: Context): StepResult =
        grantNotificationAccess(context, unblock = false)

    /** Only used by the user-confirmed restricted-settings recovery button. */
    fun unblockNotificationAccess(context: Context): StepResult =
        grantNotificationAccess(context, unblock = true)

    private fun grantNotificationAccess(context: Context, unblock: Boolean): StepResult {
        if (!hasPermission()) return notGranted()
        val component = ComponentName(
            context,
            NotificationRelayService::class.java
        ).flattenToString()
        return NotificationAccessSetup.grant(
            packageName = context.packageName, component = component, userId = userId,
            unblock = unblock, command = ::shell,
            isGranted = { notificationAccessGranted(context) },
            rebind = { NotificationCenter.requestListenerRebind() }
        )
    }

    fun enableAutomation(context: Context): StepResult {
        if (!hasPermission()) return notGranted()
        if (automationGranted(context)) {
            return StepResult(true, "Automation is already on.")
        }
        val component = ComponentName(
            context,
            OpenBubblesAutomationService::class.java
        ).flattenToString()
        val current = readSecure("enabled_accessibility_services")
            ?: return StepResult(false, "Shizuku could not read accessibility settings.")
        val parts = current.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (component !in parts) {
            parts += component
            val written = writeSecure(
                "enabled_accessibility_services",
                parts.joinToString(":")
            )
            if (!written.ok) {
                return StepResult(false, written.output.ifBlank { "Could not update accessibility settings." })
            }
        }
        val enabled = writeSecure("accessibility_enabled", "1")
        if (!enabled.ok) {
            return StepResult(false, enabled.output.ifBlank { "Could not enable the accessibility service." })
        }
        return StepResult(true, "Automation requested. Android may take a moment to bind the service.")
    }

    fun keepAlive(context: Context): StepResult {
        if (!hasPermission()) return notGranted()
        if (batteryUnrestricted(context)) {
            return StepResult(true, "The bridge is already exempt from battery optimization.")
        }
        var result = shell("cmd deviceidle whitelist +${context.packageName}")
        if (!result.ok || result.output.contains("Unknown command", ignoreCase = true)) {
            result = shell("dumpsys deviceidle whitelist +${context.packageName}")
        }
        if (!result.ok) {
            return StepResult(false, result.output.ifBlank { "Could not update the battery whitelist." })
        }
        return if (batteryUnrestricted(context) || result.output.contains("added", ignoreCase = true)) {
            StepResult(true, "Battery optimization exemption added.")
        } else {
            StepResult(false, result.output.ifBlank { "Android did not add the battery exemption." })
        }
    }

    /** Runs every optional assisted step and summarizes what happened. */
    fun runAll(context: Context): StepResult {
        if (!hasPermission()) return notGranted()
        val steps = listOf(
            enableNotificationAccess(context),
            enableAutomation(context),
            keepAlive(context)
        )
        val failed = steps.firstOrNull { !it.ok }
        return if (failed != null) {
            StepResult(false, failed.message)
        } else {
            StepResult(true, "Setup requested. Check the status cards below; if a permission stays off, enable it in Android settings.")
        }
    }

    fun readSecure(name: String): String? {
        val result = shell("settings --user $userId get secure $name")
        if (!result.ok) return null
        return result.output
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeUnless { it == "null" }
            ?: ""
    }

    private fun writeSecure(name: String, value: String): ShellResult =
        shell("settings --user $userId put secure $name ${shellQuote(value)}")

    private val userId: Int get() = android.os.Process.myUid() / 100_000

    fun shell(command: String): ShellResult {
        if (!hasPermission()) {
            return ShellResult(false, "Shizuku permission is required.")
        }
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000) {
            return ShellResult(false, "Start Shizuku through wireless debugging (shell mode), or use manual setup. Root mode is not used by this bridge.")
        }
        return runCatching {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(
                arrayOf("sh", "-c", "timeout 10 sh -c ${shellQuote("$command 2>&1")}"),
                null,
                null
            )
            val output = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
            val exit = process.waitFor()
            ShellResult(exit == 0, output.trim())
        }.getOrElse { error ->
            ShellResult(false, error.message ?: "Shizuku command failed.")
        }
    }

    private fun notGranted(): StepResult =
        StepResult(false, "Grant Shizuku access to the bridge first.")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
