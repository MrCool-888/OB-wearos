package com.openbubbles.wear.bridge

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class GroupShortcutDiscoveryTest {
    private val pkg = "com.openbubbles.messaging"
    private val guid = "iMessage;+;chat1234"
    private fun shortcut(id: String = guid, title: String = "Family", owner: String = pkg,
        flags: String = "1", readable: String = "Dyn") = """
        ShortcutInfo {id=$id, flags=0x$flags [$readable]
          packageName=$owner
          activity=$owner/.MainActivity
          shortLabel=$title, resId=0[null]
          longLabel=null, resId=0[null]
        }
    """.trimIndent()

    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("openbubbles_wear_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aospMultilineDumpImportsGroupWithoutNotification() {
        val groups = GroupShortcutDiscovery.parse(shortcut() + "\nSuccess\n", pkg)
        val group = groups.single()
        assertEquals(guid, group.id)
        assertEquals("Family", group.title)
        assertTrue(group.isGroup)
        assertFalse(group.notificationActive)
        assertNull(group.address)
    }

    @Test fun directForeignDisabledAndRedactedEntriesAreIgnored() {
        val raw = listOf(shortcut(id = "iMessage;-;+15551234567"), shortcut(owner = "other.app"),
            shortcut(flags = "41", readable = "DynDis"), shortcut(title = "***"),
            shortcut(id = "group-looking-name"), shortcut(title = "null")).joinToString("\n")
        assertTrue(GroupShortcutDiscovery.parse(raw, pkg).isEmpty())
    }

    @Test fun disabledFlagIsHonoredEvenWithoutReadableDisabledLabel() {
        assertTrue(GroupShortcutDiscovery.parse(shortcut(flags = "41"), pkg).isEmpty())
    }

    @Test fun repeatedDumpEntriesAreDeduplicatedAndTitleCommasPreserved() {
        val raw = shortcut(title = "Friends, family") + "\n" + shortcut(title = "Friends, family")
        assertEquals("Friends, family", GroupShortcutDiscovery.parse(raw, pkg).single().title)
    }

    @Test fun oversizedOrMalformedDumpIsRejected() {
        assertTrue(GroupShortcutDiscovery.parse("x".repeat(1_000_001), pkg).isEmpty())
        assertTrue(GroupShortcutDiscovery.parse("ShortcutInfo {id=$guid}", pkg).isEmpty())
    }

    @Test fun queryIsReadOnlyAndScopedToOurUserAndOpenBubblesPackage() {
        assertEquals("cmd shortcut get-shortcuts --user 10 --flags 19 '$pkg'", GroupShortcutDiscovery.command(pkg, 10))
        assertThrows(IllegalArgumentException::class.java) { GroupShortcutDiscovery.command("other.app'; echo bad", 0) }
        assertThrows(IllegalArgumentException::class.java) { GroupShortcutDiscovery.command(pkg, -1) }
    }

    @Test fun importingExistingGroupPreservesMessagesUnreadAndParticipants() {
        val store = BridgeStore(RuntimeEnvironment.getApplication())
        val old = WireThread(id = guid, title = "Old", isGroup = true, unread = true,
            participants = listOf("Alex"), messages = listOf(WireMessage(id = "m", threadId = guid, text = "Hi", timestamp = 1000)))
        store.update(old)
        val imported = store.importPublishedGroup(GroupShortcutDiscovery.parse(shortcut(), pkg).single())
        assertEquals(old.messages, imported.messages)
        assertEquals(old.participants, imported.participants)
        assertTrue(imported.unread)
        assertEquals("Family", imported.title)
        assertEquals(imported, BridgeStore(RuntimeEnvironment.getApplication()).get(guid))
    }

    @Test fun stableGroupLaunchUsesExactGuidNotGroupNameOrRecipientComposeExtras() {
        val launcher = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, "$pkg.MainActivity"))
        val intent = ConversationLaunch.existingGuid(launcher, guid)
        assertEquals(guid, intent.getStringExtra("chatGuid"))
        assertEquals(guid, intent.getStringExtra("android.intent.extra.shortcut.ID"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(launcher.component, intent.component)
        assertFalse(intent.hasExtra("address"))
        assertFalse(launcher.hasExtra("chatGuid"))
        assertThrows(IllegalArgumentException::class.java) { ConversationLaunch.existingGuid(launcher, "Family") }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationLaunch.existingGuid(Intent().setPackage("other.app"), guid)
        }
    }

    @Test fun groupHeaderMustMatchWholeTitleNotASimilarlyNamedGroup() {
        assertTrue(ConversationLaunch.matchesHeader("Family\n3 people", "Family"))
        assertTrue(ConversationLaunch.matchesHeader("  FAMILY  ", "Family"))
        assertFalse(ConversationLaunch.matchesHeader("Family reunion", "Family"))
        assertFalse(ConversationLaunch.matchesHeader("Other", ""))
    }
}
