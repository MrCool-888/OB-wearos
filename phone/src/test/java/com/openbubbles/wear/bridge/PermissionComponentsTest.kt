package com.openbubbles.wear.bridge

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.junit.After
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
    private val expected = ComponentName("com.openbubbles.wear", OpenBubblesAutomationService::class.java.name)
    private val short = "com.openbubbles.wear/.bridge.OpenBubblesAutomationService"
    private val full = "com.openbubbles.wear/com.openbubbles.wear.bridge.OpenBubblesAutomationService"
    private var service: OpenBubblesAutomationService? = null

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        OpenBubblesAutomation::class.java.getDeclaredField("service").apply { isAccessible = true }.set(null, null)
    }

    @After fun detach() {
        service?.let(OpenBubblesAutomation::detach)
    }

    @Test fun shortenedAndroidComponentMatchesFullyQualifiedComponent() {
        assertNotEquals(short, expected.flattenToString())
        assertTrue(PermissionComponents.matches(short, expected))
        assertTrue(PermissionComponents.matches(full, expected))
    }

    @Test fun otherAppAndOldBridgeDoNotMatch() {
        assertFalse(PermissionComponents.matches("com.openbubbles.wear.bridge/.OpenBubblesAutomationService", expected))
        assertFalse(PermissionComponents.matches("com.other/.bridge.OpenBubblesAutomationService", expected))
    }

    @Test fun differentServiceAndMalformedValuesDoNotMatch() {
        assertFalse(PermissionComponents.matches("com.openbubbles.wear/.bridge.NotificationRelayService", expected))
        assertFalse(PermissionComponents.matches(null, expected))
        assertFalse(PermissionComponents.matches("", expected))
        assertFalse(PermissionComponents.matches("not-a-component", expected))
    }

    @Test fun mixedEnabledListRecognizesShortComponentWithoutAddingDuplicate() {
        assertTrue(PermissionComponents.contains("com.other/.Service:$short:com.another/.Service", expected))
        assertFalse(PermissionComponents.contains("com.other/.Service", expected))
    }

    @Test fun secureSettingsFallbackRecognizesShortEnabledComponent() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, short)
        assertTrue(BridgeInfo.hasAutomationAccess(context))
        assertFalse(BridgeInfo.isAutomationReady(context))
    }

    @Test fun secureSettingsFallbackRecognizesFullEnabledComponent() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, full)
        assertTrue(BridgeInfo.hasAutomationAccess(context))
    }

    @Test fun enabledWithoutConnectionIsNotLabeledOffOrAdvertisedAsReady() {
        val status = BridgeUiState(automationEnabled = true, automationAvailable = false)
        assertTrue(status.automationStatus.startsWith("On in Android settings"))
        assertTrue(status.automationStatus.contains("not connected"))
        assertFalse(BridgeInfo.isAutomationReady(context))
    }

    @Test fun connectedInstanceIsRecognizedWhileSettingsHaveNotPropagated() {
        service = OpenBubblesAutomationService()
        OpenBubblesAutomation.attach(service!!)
        assertTrue(BridgeInfo.hasAutomationAccess(context))
        assertTrue(BridgeInfo.isAutomationReady(context))
        OpenBubblesAutomation.detach(service!!)
        assertFalse(BridgeInfo.isAutomationReady(context))
    }

    @Test fun diagnosticsContainOwnPermissionStateNotOtherAppsServiceNames() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.private.otherapp/.PrivateService:$short")
        val report = BridgeDiagnostics.create(context, BridgeUiState(automationEnabled = true))
        assertTrue(report.contains("Installed package: com.openbubbles.wear"))
        assertTrue(report.contains("Accessibility listed in secure settings: true"))
        assertTrue(report.contains("Accessibility connected/ready: false"))
        assertTrue(report.contains("Phone notification posting: not used"))
        assertFalse(report.contains("com.private.otherapp"))
    }
}
