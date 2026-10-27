package com.openbubbles.wear.bridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service used only for OpenBubbles UI automation:
 * starting a chat, sending in a chat whose notification is gone, and reading
 * older message bubbles. It never reads or modifies OpenBubbles' database.
 */
class OpenBubblesAutomationService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        OpenBubblesAutomation.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Polling is used while an automation job is active. Events are not
        // needed, and ignoring them avoids doing work in the background.
    }

    override fun onInterrupt() {
        OpenBubblesAutomation.interrupt()
    }

    override fun onDestroy() {
        OpenBubblesAutomation.detach(this)
        super.onDestroy()
    }
}
