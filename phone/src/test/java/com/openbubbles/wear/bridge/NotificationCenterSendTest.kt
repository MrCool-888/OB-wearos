package com.openbubbles.wear.bridge

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class NotificationCenterSendTest {
    private lateinit var context: Context
    private var result: NotificationCenter.SendResult? = null

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        NotificationCenter.init(context)
        NotificationCenter.clearCache(context)
        BridgePreferences(context).relayEnabled = true
        result = null
    }

    @Test fun missingThreadIdIsRejected() {
        NotificationCenter.send("request", null, "Hello") { result = it }
        assertEquals("Open this chat in OpenBubbles on the phone first.", result!!.error)
        assertNull(result!!.threadId)
    }

    @Test fun threadWithoutLiveOrSavedReplyActionIsRejected() {
        NotificationCenter.send("request", "iMessage;-;nobody@example.com", "Hello") { result = it }
        assertTrue(result!!.error!!.contains("no reply action"))
        assertEquals("iMessage;-;nobody@example.com", result!!.threadId)
    }
}
