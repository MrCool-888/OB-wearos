package com.openbubbles.wear.patch

import android.app.Application
import android.content.Context
import android.widget.Switch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32], application = Application::class)
class PatchSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { context.getSharedPreferences(PatchSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun defaultsEnabledAndPersistsWithoutRepatching() {
        assertTrue(PatchSettings.isEnabled(context))
        PatchSettings.setEnabled(context, false)
        assertFalse(PatchSettings.isEnabled(context))
        PatchSettings.setEnabled(context, true)
        assertTrue(PatchSettings.isEnabled(context))
    }

    @Test fun disabledBridgeRejectsPrivateOperationsBeforeBackendAccess() {
        PatchSettings.setEnabled(context, false)
        val service = Robolectric.buildService(CompanionService::class.java).get()
        for (op in listOf("chats", "thread", "send", "markRead")) {
            val reply = JSONObject(service.dispatch("""{"apiVersion":1,"op":"$op"}"""))
            assertFalse(reply.getBoolean("ok"))
            assertFalse(reply.getBoolean("enabled"))
            assertTrue(reply.getString("error").contains("Morphe patches"))
        }
    }

    @Test fun settingsScreenToggleSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(PatchSettingsActivity::class.java).setup()
        val content = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
        fun toggle(view: android.view.View): Switch? {
            if (view is Switch) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                toggle(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        toggle(content)!!.isChecked = false
        controller.recreate()
        assertFalse(PatchSettings.isEnabled(context))
        assertFalse(toggle(controller.get().findViewById(android.R.id.content))!!.isChecked)
        controller.pause().stop().destroy()
    }
}
