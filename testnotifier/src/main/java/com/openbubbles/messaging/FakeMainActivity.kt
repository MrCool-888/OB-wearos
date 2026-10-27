package com.openbubbles.messaging

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Debug helper app that posts OpenBubbles-shaped notifications so the Wear
 * bridge can be tested without the real OpenBubbles account or a paired watch.
 */
class FakeMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = """
                    Fake OpenBubbles notifier

                    Use:
                    adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
                      -a com.openbubbles.messaging.DEBUG_POST --es chat all

                    This app only exists for bridge testing.
                """.trimIndent()
                textSize = 18f
                setPadding(48, 96, 48, 48)
            }
        )
    }
}
