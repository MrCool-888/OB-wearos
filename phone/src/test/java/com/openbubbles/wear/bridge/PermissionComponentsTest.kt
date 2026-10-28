package com.openbubbles.wear.bridge

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PermissionComponentsTest {
    private lateinit var context: Context
    private val expected = ComponentName("com.openbubbles.wear", NotificationRelayService::class.java.name)
    private val short = "com.openbubbles.wear/.bridge.NotificationRelayService"
    private val full = "com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService"

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
    }

    @Test fun shortenedAndroidComponentMatchesFullyQualifiedComponent() {
        assertNotEquals(short, expected.flattenToString())
        assertTrue(PermissionComponents.matches(short, expected))
        assertTrue(PermissionComponents.matches(full, expected))
    }

    @Test fun otherAppAndOldBridgeDoNotMatch() {
        assertFalse(PermissionComponents.matches("com.openbubbles.wear.bridge/.NotificationRelayService", expected))
        assertFalse(PermissionComponents.matches("com.other/.bridge.NotificationRelayService", expected))
    }

    @Test fun differentServiceAndMalformedValuesDoNotMatch() {
        assertFalse(PermissionComponents.matches("com.openbubbles.wear/.bridge.DebugRelayReceiver", expected))
        assertFalse(PermissionComponents.matches(null, expected))
        assertFalse(PermissionComponents.matches("", expected))
        assertFalse(PermissionComponents.matches("not-a-component", expected))
    }

    @Test fun mixedEnabledListRecognizesShortComponentWithoutAddingDuplicate() {
        assertTrue(PermissionComponents.contains("com.other/.Service:$short:com.another/.Service", expected))
        assertFalse(PermissionComponents.contains("com.other/.Service", expected))
    }

    @Test fun diagnosticsContainOwnPermissionStateNotOtherAppsServiceNames() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            "com.private.otherapp/.PrivateService:$short")
        val report = BridgeDiagnostics.create(context, BridgeUiState())
        assertTrue(report.contains("Installed package: com.openbubbles.wear"))
        assertTrue(report.contains("Listener listed in secure settings: true"))
        assertTrue(report.contains("Phone notification posting: not used"))
        assertFalse(report.contains("com.private.otherapp"))
        assertFalse(report.contains("Accessibility"))
    }
}
