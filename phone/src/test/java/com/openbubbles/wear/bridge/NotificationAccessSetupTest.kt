package com.openbubbles.wear.bridge

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class NotificationAccessSetupTest {
    private val packageName = "com.openbubbles.wear"
    private val component = "$packageName/com.openbubbles.wear.bridge.NotificationRelayService"

    @Test fun appInfoOpensOurInstalledPackageNotTheSourceNamespace() {
        val context: Context = RuntimeEnvironment.getApplication()
        val intent = NotificationAccessSetup.appInfoIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:$packageName", intent.data.toString())
    }

    @Test fun notificationLinkTargetsTheExactListenerComponent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val intent = NotificationAccessSetup.listenerIntent(context)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        val target = ComponentName.unflattenFromString(intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME)!!)
        assertEquals(packageName, target!!.packageName)
        assertEquals(NotificationRelayService::class.java.name, target.className)
    }

    @Test fun explicitUnblockIsScopedToOurPackageAndCurrentUser() {
        val commands = mutableListOf<String>()
        var granted = false
        var rebound = false
        val result = NotificationAccessSetup.grant(packageName, component, 10, sdkInt = 35, unblock = true,
            command = {
                commands += it
                if (it.startsWith("cmd notification")) granted = true
                ShizukuSetup.ShellResult(true, "")
            }, isGranted = { granted }, rebind = { rebound = true }, pause = {})
        assertTrue(result.ok)
        assertTrue(rebound)
        assertEquals(listOf(
            "cmd appops set --user 10 '$packageName' ACCESS_RESTRICTED_SETTINGS allow",
            "cmd notification allow_listener '$component' 10"
        ), commands)
        assertTrue(commands.none { it.contains("accessibility") || it.contains("deviceidle") || it.contains("com.openbubbles.messaging") })
    }

    @Test fun normalGrantDoesNotChangeRestrictedSettings() {
        val commands = mutableListOf<String>()
        NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 35, unblock = false,
            command = { commands += it; ShizukuSetup.ShellResult(true, "") },
            isGranted = { commands.isNotEmpty() }, rebind = {}, pause = {})
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("cmd notification"))
    }

    @Test fun android12DoesNotUseAndroid13RestrictedSettingsAppOp() {
        val commands = mutableListOf<String>()
        NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 32, unblock = true,
            command = { commands += it; ShizukuSetup.ShellResult(true, "") },
            isGranted = { commands.isNotEmpty() }, rebind = {}, pause = {})
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("cmd notification"))
    }

    @Test fun exitCodeZeroWithoutActualGrantIsNotReportedAsSuccess() {
        var rebound = false
        val result = NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 35, unblock = true,
            command = { ShizukuSetup.ShellResult(true, "Access denied") },
            isGranted = { false }, rebind = { rebound = true }, pause = {})
        assertFalse(result.ok)
        assertFalse(rebound)
        assertTrue(result.message.contains("Allow restricted settings"))
        assertTrue(result.message.contains("administrator"))
    }

    @Test fun existingGrantDoesNotRunAnyCommands() {
        var rebound = false
        val result = NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 35, unblock = true,
            command = { error("No command should run") }, isGranted = { true }, rebind = { rebound = true }, pause = {})
        assertTrue(result.ok)
        assertTrue(rebound)
    }

    @Test fun delayedSystemGrantIsVerifiedBeforeSuccess() {
        var checks = 0
        var waits = 0
        val result = NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 35, unblock = true,
            command = { ShizukuSetup.ShellResult(true, "") }, isGranted = { ++checks >= 4 },
            rebind = {}, pause = { waits++ })
        assertTrue(result.ok)
        assertEquals(2, waits)
    }

    @Test fun unavailableAppOpDoesNotPreventAWorkingListenerGrant() {
        var granted = false
        val result = NotificationAccessSetup.grant(packageName, component, 0, sdkInt = 35, unblock = true,
            command = {
                if (it.startsWith("cmd notification")) {
                    granted = true
                    ShizukuSetup.ShellResult(true, "")
                } else ShizukuSetup.ShellResult(false, "Unknown operation")
            }, isGranted = { granted }, rebind = {}, pause = {})
        assertTrue(result.ok)
    }
}
