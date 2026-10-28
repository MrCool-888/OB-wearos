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
class PublishedChatDiscoveryTest {
    private val pkg = "com.openbubbles.messaging"
    private val groupGuid = "iMessage;+;chat1234"
    private val directGuid = "iMessage;-;+15551234567"

    private fun shortcut(id: String = groupGuid, title: String = "Family", owner: String = pkg,
        flags: String = "1", readable: String = "Dyn") = """
        ShortcutInfo {id=$id, flags=0x$flags [$readable]
          packageName=$owner
          activity=$owner/.MainActivity
          shortLabel=$title, resId=0[null]
          longLabel=null, resId=0[null]
        }
    """.trimIndent()

    private fun channel(
        id: String = "com.bluebubbles.new_messages.$groupGuid",
        name: String = "Family",
        deleted: Boolean = false
    ) = "    NotificationChannel{mId='$id', mName=$name, mDescription=null, " +
        "mImportance=3, mBypassDnd=false, mLockscreenVisibility=1, mDeleted=$deleted}"

    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("openbubbles_wear_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aospMultilineShortcutImportsGroupWithoutNotification() {
        val chats = PublishedChatDiscovery.parseShortcuts(shortcut() + "\nSuccess\n", pkg)
        val group = chats.single()
        assertEquals(groupGuid, group.id)
        assertEquals("Family", group.title)
        assertTrue(group.isGroup)
        assertFalse(group.notificationActive)
        assertNull(group.address)
    }

    @Test fun directShortcutGetsNormalizedAddressAndIsNotTreatedAsAGroup() {
        val chat = PublishedChatDiscovery.parseShortcuts(shortcut(id = directGuid, title = "Alex"), pkg).single()
        assertEquals(directGuid, chat.id)
        assertFalse(chat.isGroup)
        assertEquals("+15551234567", chat.address)
    }

    @Test fun foreignDisabledAndRedactedShortcutsAreIgnored() {
        val raw = listOf(shortcut(owner = "other.app"), shortcut(flags = "41", readable = "DynDis"),
            shortcut(title = "***"), shortcut(id = "group-looking-name"), shortcut(title = "null"))
            .joinToString("\n")
        assertTrue(PublishedChatDiscovery.parseShortcuts(raw, pkg).isEmpty())
    }

    @Test fun disabledFlagIsHonoredEvenWithoutReadableDisabledLabel() {
        assertTrue(PublishedChatDiscovery.parseShortcuts(shortcut(flags = "41"), pkg).isEmpty())
    }

    @Test fun repeatedShortcutEntriesAreDeduplicatedAndTitleCommasPreserved() {
        val raw = shortcut(title = "Friends, family") + "\n" + shortcut(title = "Friends, family")
        assertEquals("Friends, family", PublishedChatDiscovery.parseShortcuts(raw, pkg).single().title)
    }

    @Test fun channelDumpFindsGroupsAndOneToOneChatsWithoutNotifications() {
        val raw = listOf(
            channel(),
            channel(id = "com.bluebubbles.new_messages.$directGuid", name = "Alex"),
            channel(id = "com.bluebubbles.new_messages.$directGuid.notify_anyways", name = "Notify Anyways: Alex")
        ).joinToString("\n")
        val chats = PublishedChatDiscovery.parseChannels(raw)
        assertEquals(setOf(groupGuid, directGuid), chats.map { it.id }.toSet())
        assertEquals("Family", chats.first { it.id == groupGuid }.title)
        assertEquals("Alex", chats.first { it.id == directGuid }.title)
        assertTrue(chats.first { it.id == groupGuid }.isGroup)
        assertFalse(chats.first { it.id == directGuid }.isGroup)
        assertEquals("+15551234567", chats.first { it.id == directGuid }.address)
    }

    @Test fun quotedChannelNamesFromOemDumpsAreUnwrapped() {
        val raw = channel(id = "com.bluebubbles.new_messages.$directGuid", name = "'Notify Anyways: Alex'")
        val chat = PublishedChatDiscovery.parseChannels(raw).single()
        assertEquals("Alex", chat.title)
    }

    @Test fun deletedRedactedAndForeignChannelsAreIgnored() {
        val raw = listOf(
            channel(deleted = true),
            channel(id = "com.bluebubbles.new_messages.$groupGuid", name = "***"),
            channel(id = "com.other.app.new_messages.$groupGuid"),
            channel(id = "com.bluebubbles.new_messages.not-a-guid"),
            "NotificationChannel{mId='com.bluebubbles.foreground_service', mName=Service, mDescription=null}"
        ).joinToString("\n")
        assertTrue(PublishedChatDiscovery.parseChannels(raw).isEmpty())
    }

    @Test fun channelIdsMapOnlyToExactOpenBubblesChatGuids() {
        assertEquals(groupGuid,
            PublishedChatDiscovery.chatGuidFromChannelId("com.bluebubbles.new_messages.$groupGuid"))
        assertEquals(groupGuid,
            PublishedChatDiscovery.chatGuidFromChannelId("com.bluebubbles.new_messages.$groupGuid.notify_anyways"))
        assertNull(PublishedChatDiscovery.chatGuidFromChannelId("com.bluebubbles.foreground_service"))
        assertNull(PublishedChatDiscovery.chatGuidFromChannelId("other.$groupGuid"))
        assertNull(PublishedChatDiscovery.chatGuidFromChannelId("com.bluebubbles.new_messages.Family"))
    }

    @Test fun shortcutAndChannelDuplicatesKeepOneEntry() {
        val fromShortcut = PublishedChatDiscovery.parseShortcuts(shortcut(), pkg).single()
        val fromChannel = PublishedChatDiscovery.parseChannels(channel()).single()
        val ids = listOf(fromShortcut, fromChannel).distinctBy { it.id }
        assertEquals(1, ids.size)
        assertEquals("Family", ids.single().title)
    }

    @Test fun oversizedOrMalformedDumpsAreRejected() {
        assertTrue(PublishedChatDiscovery.parseShortcuts("x".repeat(1_000_001), pkg).isEmpty())
        assertTrue(PublishedChatDiscovery.parseShortcuts("ShortcutInfo {id=$groupGuid}", pkg).isEmpty())
        assertTrue(PublishedChatDiscovery.parseChannels("x".repeat(1_000_001)).isEmpty())
        assertTrue(PublishedChatDiscovery.parseChannels("NotificationChannel{mId='broken'").isEmpty())
    }

    @Test fun queryIsReadOnlyAndScopedToOurUserAndOpenBubblesPackage() {
        assertEquals("cmd shortcut get-shortcuts --user 10 --flags 19 '$pkg'",
            PublishedChatDiscovery.shortcutCommand(pkg, 10))
        assertEquals("dumpsys notification --noredact --package '$pkg'",
            PublishedChatDiscovery.channelCommand(pkg))
        assertThrows(IllegalArgumentException::class.java) {
            PublishedChatDiscovery.shortcutCommand("other.app'; echo bad", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublishedChatDiscovery.shortcutCommand(pkg, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublishedChatDiscovery.channelCommand("other.app'; echo bad")
        }
    }

    @Test fun importingExistingGroupPreservesMessagesUnreadAndParticipants() {
        val store = BridgeStore(RuntimeEnvironment.getApplication())
        val old = WireThread(id = groupGuid, title = "Old", isGroup = true, unread = true,
            participants = listOf("Alex"), messages = listOf(WireMessage(id = "m", threadId = groupGuid, text = "Hi", timestamp = 1000)))
        store.update(old)
        val imported = store.importPublishedChat(PublishedChatDiscovery.parseShortcuts(shortcut(), pkg).single())
        assertEquals(old.messages, imported.messages)
        assertEquals(old.participants, imported.participants)
        assertTrue(imported.unread)
        assertEquals("Family", imported.title)
        assertEquals(imported, BridgeStore(RuntimeEnvironment.getApplication()).get(groupGuid))
    }

    @Test fun importingDirectGuidFoldsTemporaryNewAddressThread() {
        val store = BridgeStore(RuntimeEnvironment.getApplication())
        val temporary = WireThread(
            id = "new:+15551234567",
            title = "Alex",
            address = "+15551234567",
            canSend = true,
            messages = listOf(WireMessage(id = "m", threadId = "new:+15551234567", text = "Hi", timestamp = 1000))
        )
        store.update(temporary)
        val imported = store.importPublishedChat(
            PublishedChatDiscovery.parseShortcuts(shortcut(id = directGuid, title = "Alex"), pkg).single()
        )
        assertEquals(directGuid, imported.id)
        assertEquals(temporary.messages, imported.messages)
        assertEquals(temporary.address, imported.address)
        assertTrue(imported.canSend)
        assertNull(store.get(temporary.id))
        assertEquals(imported, store.get(directGuid))
    }

    @Test fun discoveryPlaceholdersCannotEvictConversationsWithMessages() {
        val store = BridgeStore(RuntimeEnvironment.getApplication())
        repeat(45) { index ->
            val id = "iMessage;-;+1555000$index"
            store.update(WireThread(
                id = id,
                title = "Chat $index",
                address = "+1555000$index",
                messages = listOf(WireMessage(
                    id = "m$index",
                    threadId = id,
                    text = "Message $index",
                    timestamp = index.toLong()
                ))
            ))
        }
        repeat(80) { index ->
            store.importPublishedChat(WireThread(
                id = "iMessage;+;group$index",
                title = "Group $index",
                isGroup = true
            ))
        }
        val snapshot = store.snapshot()
        assertEquals(BridgeStore.MAX_THREADS, snapshot.count { it.messages.isNotEmpty() })
        assertTrue(snapshot.count { it.messages.isEmpty() } <= BridgeStore.MAX_PLACEHOLDERS)
        assertTrue(snapshot.any { it.messages.isNotEmpty() })
    }

    @Test fun stableGroupLaunchUsesExactGuidNotGroupNameOrRecipientComposeExtras() {
        val launcher = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, "$pkg.MainActivity"))
        val intent = ConversationLaunch.existingGuid(launcher, groupGuid)
        assertEquals(groupGuid, intent.getStringExtra("chatGuid"))
        assertEquals(groupGuid, intent.getStringExtra("android.intent.extra.shortcut.ID"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(launcher.component, intent.component)
        assertFalse(intent.hasExtra("address"))
        assertFalse(launcher.hasExtra("chatGuid"))
        assertThrows(IllegalArgumentException::class.java) { ConversationLaunch.existingGuid(launcher, "Family") }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationLaunch.existingGuid(Intent().setPackage("other.app"), groupGuid)
        }
    }

    @Test fun groupHeaderMustMatchWholeTitleNotASimilarlyNamedGroup() {
        assertTrue(ConversationLaunch.matchesHeader("Family\n3 people", "Family"))
        assertTrue(ConversationLaunch.matchesHeader("  FAMILY  ", "Family"))
        assertFalse(ConversationLaunch.matchesHeader("Family reunion", "Family"))
        assertFalse(ConversationLaunch.matchesHeader("Other", ""))
    }
}
