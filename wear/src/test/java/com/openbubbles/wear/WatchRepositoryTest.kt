package com.openbubbles.wear

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.openbubbles.wear.protocol.ActionResult
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import com.openbubbles.wear.protocol.WatchContact
import com.openbubbles.wear.protocol.ContactAddress
import com.openbubbles.wear.protocol.ContactsPage
import com.openbubbles.wear.protocol.PongPayload
import com.openbubbles.wear.protocol.GroupsResult
import com.openbubbles.wear.protocol.ThreadPayload
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class WatchRepositoryTest {
    private lateinit var context: Context
    private lateinit var state: MutableStateFlow<WatchState>
    private lateinit var pending: MutableMap<String, String>
    private lateinit var threadRequests: MutableMap<String, String>
    private val local = WireMessage(id = "local-request", threadId = "chat", text = "Hello",
        timestamp = 1000, fromMe = true, status = "sending")

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("openbubbles_wear", Context.MODE_PRIVATE).edit().clear().commit()
        fun field(name: String) = WatchRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("initialized").setBoolean(null, true)
        field("appContext").set(null, context)
        // Drive transport results explicitly instead of racing real Play Services callbacks.
        field("queueFlushing").setBoolean(null, true)
        @Suppress("UNCHECKED_CAST")
        state = field("_state").get(null) as MutableStateFlow<WatchState>
        @Suppress("UNCHECKED_CAST")
        pending = field("pendingSends").get(null) as MutableMap<String, String>
        pending.clear()
        @Suppress("UNCHECKED_CAST")
        threadRequests = field("pendingThreadRequests").get(null) as MutableMap<String, String>
        threadRequests.clear()
        WatchRepository::class.java.getDeclaredField("discoveryOffset").apply { isAccessible = true }.setInt(null, 0)
        WatchNavigation.consume()
        state.value = WatchState(loading = false, relayEnabled = false,
            threads = mapOf("chat" to WireThread(id = "chat", title = "Alex", messages = listOf(local))),
            queuedSends = listOf(QueuedSend("request", "chat", "Hello")), sendingThreads = setOf("chat"))
        pending["request"] = "chat"
        WatchNotifications.createChannel(context)
    }

    private fun status() = state.value.threads.getValue("chat").messages.single().status
    private fun status(id: String) = state.value.threads.getValue("chat").messages.single { it.id == id }.status
    private fun message(id: String, time: Long = 1000, text: String = "Hello") = WireMessage(id, "chat", text, time)
    private fun confirm() = WatchRepository.onActionResult(ActionResult(
        requestId = "request", action = Protocol.ACTION_SEND, ok = true, threadId = "chat"))

    @Test fun fastResultCannotBeOverwrittenByTransportAcceptance() {
        confirm()
        WatchRepository.onSendDispatched("request", "chat", local.id, true)
        assertEquals("sent", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(state.value.sendingThreads.isEmpty())
    }

    @Test fun acceptedSendPreservesBackendSendingStatus() {
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND, ok = true,
            threadId = "chat", message = local.copy(id = "backend", status = "sending")))
        assertEquals("sending", status("backend"))
        assertFalse(state.value.threads.getValue("chat").messages.any { it.id == local.id })
    }

    @Test fun acceptedSendPreservesBackendFailedStatus() {
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND, ok = true,
            threadId = "chat", message = local.copy(id = "backend", status = "failed")))
        assertEquals("failed", status("backend"))
    }

    @Test fun acceptedSendWithoutBackendStatusRemainsUnconfirmed() {
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND, ok = true,
            threadId = "chat", message = local.copy(id = "backend", status = null)))
        assertEquals("unconfirmed", status("backend"))
    }

    @Test fun matchingHistoryKeepsFailureWithoutStatusAndUpdatesWhenPhoneReportsOne() {
        val failed = local.copy(status = "failed")
        val thread = WireThread("chat", "Alex", messages = listOf(failed))
        state.value = state.value.copy(threads = mapOf("chat" to thread))
        val remote = message("backend").copy(fromMe = true)
        WatchRepository.onThread(thread.copy(messages = listOf(remote)), connected = false)
        assertEquals("failed", status("backend"))
        WatchRepository.onThread(thread.copy(directHistory = true,
            messages = listOf(remote.copy(status = "delivered"))), connected = false)
        assertEquals("delivered", status("backend"))
    }

    @Test fun fastResultCannotBeRequeuedByLateTransportFailure() {
        confirm()
        WatchRepository.onSendDispatched("request", "chat", local.id, false)
        assertEquals("sent", status())
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun transportFailureKeepsMessageQueued() {
        WatchRepository.onSendDispatched("request", "chat", local.id, false)
        assertEquals("queued", status())
        assertEquals(1, state.value.queuedCount)
        assertFalse(pending.containsKey("request"))
        assertTrue(state.value.sendingThreads.isEmpty())
    }

    @Test fun fullOutboxRejectsNewSendWithoutDroppingOldMessages() {
        val queue = List(100) { QueuedSend("request-$it", "chat", "message-$it") }
        state.value = state.value.copy(relayEnabled = true, sendingThreads = emptySet(), queuedSends = queue)
        assertFalse(WatchRepository.sendReply("chat", "New message"))
        assertEquals(queue, state.value.queuedSends)
        assertTrue(state.value.error!!.contains("full"))
    }

    @Test fun pausedRelayDoesNotFlushOutbox() {
        WatchRepository::class.java.getDeclaredField("queueFlushing").apply { isAccessible = true }.setBoolean(null, false)
        pending.clear()
        WatchRepository.flushQueue(manual = true)
        assertEquals(1, state.value.queuedCount)
        assertTrue(pending.isEmpty())
    }

    @Test fun unsentIdenticalTextDoesNotDisappearOnThreadRefresh() {
        val queued = local.copy(status = "queued")
        state.value = state.value.copy(threads = mapOf("chat" to WireThread(id = "chat", title = "Alex", messages = listOf(queued))))
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", messages = listOf(
            local.copy(id = "old-message", timestamp = 900, status = "sent"))), connected = false)
        assertEquals(setOf("old-message", local.id), state.value.threads.getValue("chat").messages.map { it.id }.toSet())
    }

    @Test fun syncKeepsOfflineNewConversationVisible() {
        val id = "new:+15551234567"
        val thread = WireThread(id = id, title = "Alex", address = "+15551234567", messages = listOf(local.copy(threadId = id)))
        state.value = state.value.copy(threads = mapOf(id to thread),
            queuedSends = listOf(QueuedSend("request", id, "Hello")))
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "1.3.0",
            notificationAccess = true, conversations = emptyList(), relayEnabled = false), fromCache = true)
        assertEquals(id, state.value.conversations.single().id)
        assertTrue(state.value.threads.containsKey(id))
    }

    @Test fun newConversationMigrationKeepsQueuedMessageAndRemapsOutbox() {
        val oldId = "new:+15551234567"
        val old = WireThread(id = oldId, title = "Alex", address = "+15551234567",
            messages = listOf(local.copy(threadId = oldId, status = "queued")))
        state.value = state.value.copy(threads = mapOf(oldId to old),
            queuedSends = listOf(QueuedSend("request", oldId, "Hello")), sendingThreads = setOf(oldId))
        pending["request"] = oldId
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", address = old.address), connected = false)
        assertFalse(state.value.threads.containsKey(oldId))
        assertEquals("chat", state.value.queuedSends.single().threadId)
        assertEquals("chat", state.value.threads.getValue("chat").messages.single().threadId)
        assertEquals("chat", pending["request"])
    }

    @Test fun hashCollisionCannotRedirectReplyOrCancelAnotherChat() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        for (id in listOf("Aa", "BB")) {
            val incoming = WireThread(id = id, title = id, unread = true, canReply = true,
                messages = listOf(local.copy(id = "incoming-$id", threadId = id, fromMe = false)))
            WatchNotifications.syncThread(context, incoming, WatchState(), allowNotify = true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, manager.activeNotifications.size)
        for (posted in manager.activeNotifications) {
            val reply = posted.notification.actions.first().actionIntent
            assertEquals(posted.tag, shadowOf(reply).savedIntent.getStringExtra(WatchNotificationReceiver.EXTRA_THREAD_ID))
        }
        WatchNotifications.cancel(context, "Aa")
        assertEquals("BB", manager.activeNotifications.single().tag)
    }

    @Test
    @Config(shadows = [PermissionRevokedNotificationManager::class])
    fun revokedNotificationPermissionDoesNotDiscardTheAlert() {
        val incoming = WireThread(id = "incoming", title = "Alex", unread = true,
            messages = listOf(local.copy(id = "incoming-message", threadId = "incoming", fromMe = false)))
        assertTrue(WatchNotifications.areSystemNotificationsAllowed(context))
        WatchNotifications.syncThread(context, incoming, WatchState(), allowNotify = true)
        assertEquals(0L, WatchNotifications.lastNotified(context, incoming.id))
    }

    @Implements(NotificationManager::class)
    class PermissionRevokedNotificationManager : ShadowNotificationManager() {
        @Implementation
        override fun notify(tag: String?, id: Int, notification: Notification?) {
            throw SecurityException("Notification permission revoked after the availability check")
        }
    }

    @Test fun summaryOnlyConversationCanBeMarkedRead() {
        val summary = WireThread(id = "summary", title = "Alex", unread = true).toSummary()
        state.value = state.value.copy(threads = emptyMap(), conversations = listOf(summary))
        WatchRepository.markReadLocally("summary")
        assertFalse(state.value.conversations.single().unread)
    }

    @Test fun genericAndRawTitlesDoNotReplaceMeaningfulNames() {
        val alex = WireThread(id = "chat", title = "Alex", address = "+15551234567")
        state.value = state.value.copy(threads = mapOf("chat" to alex), conversations = listOf(alex.toSummary()))

        WatchRepository.onThread(alex.copy(title = "Conversation"), connected = false)
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = true, relayEnabled = true,
            conversations = listOf(alex.toSummary().copy(title = "a".repeat(72)))))

        assertEquals("Alex", state.value.threads.getValue("chat").title)
        assertEquals("Alex", state.value.conversations.single { it.id == "chat" }.title)
    }

    @Test fun meaningfulIncomingTitleUpdatesExistingName() {
        val alex = WireThread(id = "chat", title = "Alex", address = "+15551234567")
        state.value = state.value.copy(threads = mapOf("chat" to alex), conversations = listOf(alex.toSummary()))

        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = true, relayEnabled = true,
            conversations = listOf(alex.toSummary().copy(title = "Alex Work"))))

        assertEquals("Alex Work", state.value.threads.getValue("chat").title)
        assertEquals("Alex Work", state.value.conversations.single { it.id == "chat" }.title)
    }

    @Test fun aliasMigrationKeepsTheExistingNameWhenCanonicalTitleIsRawId() {
        val old = WireThread(id = "old-id", title = "Alex", address = "alex@example.com")
        state.value = state.value.copy(threads = mapOf(old.id to old), conversations = listOf(old.toSummary()))

        WatchRepository.onThread(WireThread(id = "canonical-id", title = "canonical-id",
            address = old.address), connected = false)

        assertEquals("Alex", state.value.threads.getValue("canonical-id").title)
        assertEquals("Alex", state.value.conversations.single { it.id == "canonical-id" }.title)
    }

    @Test fun visibleConversationDoesNotKeepAnUnreadBadge() {
        WatchRepository.setVisibleThread("chat")
        val incoming = local.copy(id = "incoming", fromMe = false, timestamp = System.currentTimeMillis() + 1000)
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", unread = true, messages = listOf(incoming)), connected = false)
        assertFalse(state.value.threads.getValue("chat").unread)
        assertFalse(state.value.conversations.single().unread)
    }

    @Test fun leavingConversationAllowsUnreadUpdatesAgain() {
        WatchRepository.setVisibleThread("chat")
        WatchRepository.setVisibleThread(null)
        val incoming = local.copy(id = "incoming", fromMe = false, timestamp = System.currentTimeMillis() + 1000)
        WatchRepository.onThread(WireThread(id = "chat", title = "Alex", unread = true, messages = listOf(incoming)), connected = false)
        assertTrue(state.value.threads.getValue("chat").unread)
    }

    private fun contact(id: String) = WatchContact(id, "Alex $id", listOf(ContactAddress("alex$id@example.com")))

    @Test fun contactPagesAppendWithoutDuplicatesAndRejectStaleSearches() {
        state.value = state.value.copy(contacts = listOf(contact("1")), contactsQuery = "Alex",
            contactsRequestId = "page2", contactsRequestOffset = 40)
        WatchRepository.onContactsPage(ContactsPage(requestId = "old-search", query = "Old", contactsAccess = true))
        assertEquals("page2", state.value.contactsRequestId)
        WatchRepository.onContactsPage(ContactsPage(requestId = "page2", query = "Alex", offset = 40,
            contacts = listOf(contact("1"), contact("2")), nextOffset = 42, contactsAccess = true))
        assertEquals(listOf("1", "2"), state.value.contacts.map { it.id })
        assertEquals(42, state.value.contactsNextOffset)
        assertFalse(state.value.contactsLoading)
    }

    @Test fun deniedContactPageClearsCachedAddresses() {
        state.value = state.value.copy(contacts = listOf(contact("1")), contactsRequestId = "r")
        WatchRepository.onContactsPage(ContactsPage(requestId = "r", contactsAccess = false, error = "Allow Contacts"))
        assertTrue(state.value.contacts.isEmpty())
        assertNull(state.value.contactsNextOffset)
        assertEquals("Allow Contacts", state.value.contactsError)
    }

    @Test fun contactPermissionRevocationOnMetadataClearsCache() {
        state.value = state.value.copy(contacts = listOf(contact("1")), contactsNextOffset = 40, contactsRequestId = "r")
        WatchRepository.onPong(PongPayload(relayEnabled = false, contactsAccess = false))
        assertTrue(state.value.contacts.isEmpty())
        assertNull(state.value.contactsRequestId)
    }

    @Test fun oldPhoneMetadataDoesNotEraseCachedContacts() {
        state.value = state.value.copy(contacts = listOf(contact("1")), contactsAccess = true)
        WatchRepository.onPong(PongPayload(relayEnabled = false))
        assertEquals(1, state.value.contacts.size)
        assertEquals(true, state.value.contactsAccess)
    }

    @Test fun pickerIncludesGroupsAndDirectChatsWithoutAddresses() {
        val group = WireThread(id = "group-id", title = "Family", isGroup = true).toSummary()
        val direct = WireThread(id = "person-id", title = "Alex").toSummary()
        val watch = WatchState(conversations = listOf(group, direct))
        assertEquals(listOf(group), watch.groups)
        assertEquals(listOf(direct), watch.directChats)
    }

    @Test fun choosingContactOpensExistingChatAndNeverSendsOnSelection() {
        val thread = WireThread(id = "real-chat", title = "Alex", address = "alex@example.com")
        state.value = state.value.copy(threads = mapOf(thread.id to thread), conversations = listOf(thread.toSummary()), queuedSends = emptyList())
        assertEquals("real-chat", WatchRepository.findChatByAddress("alex@example.com"))
        assertTrue(state.value.queuedSends.isEmpty())
        assertNull(state.value.error)
    }

    @Test fun choosingContactPrefersLatestPhoneIdOverStaleCachedThread() {
        val address = "+15551234567"
        val stale = WireThread(id = "iMessage;-;+15551234567", title = "Alex", address = address)
        val current = WireThread(id = "chat_addr:+15551234567", title = "Alex", address = address).toSummary()
        state.value = state.value.copy(
            threads = mapOf(stale.id to stale),
            conversations = listOf(current)
        )
        assertEquals(current.id, WatchRepository.findChatByAddress(address))
    }

    @Test fun discoveryAcceptsOpenBubbles2ChatIds() {
        val group = WireThread(id = "chat_iMessage;+;chat123", title = "Family", isGroup = true).toSummary()
        val direct = WireThread(id = "chat_contact:42", title = "Alex").toSummary()
        state.value = state.value.copy(conversations = emptyList(), discoveryRequestId = "r")
        WatchRepository.onGroups(GroupsResult(requestId = "r", groups = listOf(group), chats = listOf(direct)))
        assertEquals(setOf(group.id, direct.id), state.value.conversations.map { it.id }.toSet())
        assertEquals(listOf(group.id), state.value.groups.map { it.id })
        assertEquals(listOf(direct.id), state.value.directChats.map { it.id })
    }

    @Test fun unknownContactDoesNotCreateAChat() {
        state.value = state.value.copy(threads = emptyMap(), conversations = emptyList())
        assertNull(WatchRepository.findChatByAddress("nobody@example.com"))
        assertTrue(state.value.threads.isEmpty())
        assertTrue(state.value.conversations.isEmpty())
        assertTrue(state.value.error!!.contains("No saved chat"))
    }

    @Test fun mismatchedContactQueryOrOffsetCannotReplaceCurrentPage() {
        state.value = state.value.copy(contacts = listOf(contact("1")), contactsQuery = "Alex",
            contactsRequestId = "r", contactsRequestOffset = 40)
        WatchRepository.onContactsPage(ContactsPage(requestId = "r", query = "Other", offset = 40, contactsAccess = true))
        WatchRepository.onContactsPage(ContactsPage(requestId = "r", query = "Alex", offset = 0, contactsAccess = true))
        assertEquals(listOf("1"), state.value.contacts.map { it.id })
        assertEquals("r", state.value.contactsRequestId)
    }

    @Test fun contactCacheIsSeparateFromMessageJournalAndClearedOnRevocation() {
        state.value = state.value.copy(contactsRequestId = "r")
        WatchRepository.onContactsPage(ContactsPage(requestId = "r", contacts = listOf(contact("1")), contactsAccess = true))
        val cache = context.getSharedPreferences("openbubbles_wear_contacts", Context.MODE_PRIVATE)
        assertTrue(cache.getString("contacts", "")!!.contains("alex1@example.com"))
        assertFalse(context.getSharedPreferences("openbubbles_wear", Context.MODE_PRIVATE).contains("contacts"))
        WatchRepository.onPong(PongPayload(relayEnabled = false, contactsAccess = false))
        assertEquals("[]", cache.getString("contacts", ""))
    }

    @Test fun groupResultsAreCorrelatedAndDoNotRemoveNotificationGroups() {
        val old = WireThread(id = "notification-group", title = "Old", isGroup = true).toSummary()
        val found = WireThread(id = "iMessage;+;chat123", title = "Family", isGroup = true).toSummary()
        state.value = state.value.copy(conversations = listOf(old), discoveryRequestId = "new")
        WatchRepository.onGroups(GroupsResult(requestId = "stale", groups = listOf(found)))
        assertEquals("new", state.value.discoveryRequestId)
        assertEquals(listOf(old), state.value.conversations)
        WatchRepository.onGroups(GroupsResult(requestId = "new", groups = listOf(found), note = "Partial list"))
        assertEquals(setOf(old.id, found.id), state.value.groups.map { it.id }.toSet())
        assertNull(state.value.discoveryRequestId)
        assertEquals("Partial list", state.value.discoveryNotice)
    }

    @Test fun discoveryImportsDirectChatsAndMergesByAddress() {
        val old = WireThread(id = "new:+15551234567", title = "Alex", address = "+15551234567",
            unread = true, canSend = true,
            messages = listOf(WireMessage(id = "old", threadId = "new:+15551234567",
                text = "Hi", timestamp = 900L))).toSummary()
        val found = WireThread(id = "iMessage;-;+15551234567", title = "Alex", address = "+15551234567").toSummary()
        state.value = state.value.copy(conversations = listOf(old), discoveryRequestId = "r")
        WatchRepository.onGroups(GroupsResult(requestId = "r", chats = listOf(found)))
        val merged = state.value.conversations.single()
        assertEquals(found.id, merged.id)
        assertEquals("Alex", merged.title)
        assertTrue(merged.unread)
        assertEquals("Hi", merged.lastPreview)
        assertEquals(900L, merged.lastTimestamp)
        assertFalse(merged.canSend)
        assertTrue(state.value.directChats.any { it.id == found.id })
        assertFalse(state.value.groups.any { it.id == found.id })
    }

    @Test fun discoveryPagesMergeWithoutLosingEarlierSummaries() {
        val first = WireThread(id = "first", title = "First").toSummary()
        val second = WireThread(id = "second", title = "Second").toSummary()
        state.value = state.value.copy(directBridge = true, discoveryRequestId = "pages")
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 0, nextOffset = 40, chats = listOf(first)))
        assertTrue(state.value.conversations.any { it.id == first.id })

        WatchRepository::class.java.getDeclaredField("discoveryOffset").apply { isAccessible = true }.setInt(null, 40)
        state.value = state.value.copy(discoveryRequestId = "pages")
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 40, chats = listOf(second)))
        assertEquals(setOf(first.id, second.id), state.value.conversations.map { it.id }.toSet())
        assertNull(state.value.discoveryRequestId)
    }

    @Test fun boundedPhoneSyncPreservesPreviouslyDiscoveredDirectoryEntries() {
        val all = List(95) { index -> WireThread(id = "directory-$index", title = "Chat $index",
            updatedAt = 10_000L - index).toSummary() }
        pending.clear()
        state.value = state.value.copy(directBridge = true, discoveryRequestId = "pages",
            threads = emptyMap(), queuedSends = emptyList(), sendingThreads = emptySet())
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 0, nextOffset = 40, chats = all.take(40)))
        WatchRepository::class.java.getDeclaredField("discoveryOffset").apply { isAccessible = true }.setInt(null, 40)
        state.value = state.value.copy(discoveryRequestId = "pages")
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 40, nextOffset = 80, chats = all.drop(40).take(40)))
        WatchRepository::class.java.getDeclaredField("discoveryOffset").apply { isAccessible = true }.setInt(null, 80)
        state.value = state.value.copy(discoveryRequestId = "pages")
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 80, chats = all.takeLast(15)))
        assertEquals(95, state.value.conversations.size)

        WatchRepository.onSync(SyncPayload(generatedAt = 2_000L, phoneName = "Phone", bridgeVersion = "2.1.2",
            notificationAccess = true, conversations = all.take(60)), fromCache = true)
        assertEquals(95, state.value.conversations.size)
        assertTrue(state.value.conversations.map { it.id }.containsAll(all.map { it.id }))
    }

    @Test fun staleDiscoveryPageCannotReplaceCurrentPage() {
        val current = WireThread(id = "current", title = "Current").toSummary()
        val stale = WireThread(id = "stale", title = "Stale").toSummary()
        state.value = state.value.copy(conversations = listOf(current), discoveryRequestId = "pages")
        WatchRepository::class.java.getDeclaredField("discoveryOffset").apply { isAccessible = true }.setInt(null, 40)
        WatchRepository.onGroups(GroupsResult(requestId = "pages", offset = 0, chats = listOf(stale)))
        assertEquals(listOf(current), state.value.conversations)
        assertEquals("pages", state.value.discoveryRequestId)
    }

    @Test fun groupDiscoveryErrorKeepsSavedChats() {
        val old = state.value.conversations
        state.value = state.value.copy(discoveryRequestId = "r")
        WatchRepository.onGroups(GroupsResult(requestId = "r", error = "Start Shizuku"))
        assertEquals(old, state.value.conversations)
        assertEquals("Start Shizuku", state.value.discoveryNotice)
        assertNull(state.value.discoveryRequestId)
    }

    @Test fun discoveryUsesPhoneReplyAvailabilityInsteadOfStaleCachedFlags() {
        val old = WireThread(id = "chat_contact:42", title = "Alex", canReply = false).toSummary()
        val ready = old.copy(canReply = true, canSend = true)
        state.value = state.value.copy(conversations = listOf(old), discoveryRequestId = "ready")
        WatchRepository.onGroups(GroupsResult(requestId = "ready", chats = listOf(ready)))
        assertTrue(state.value.conversations.single().canReply)
        state.value = state.value.copy(discoveryRequestId = "expired")
        WatchRepository.onGroups(GroupsResult(requestId = "expired", chats = listOf(old)))
        assertFalse(state.value.conversations.single().canReply)
        assertFalse(state.value.conversations.single().canSend)
    }

    @Test fun failedSendIsMarkedFailedAndLeavesTheOutbox() {
        WatchRepository.onActionResult(ActionResult(
            requestId = "request",
            action = Protocol.ACTION_SEND,
            ok = false,
            threadId = "chat",
            error = "OpenBubbles has no reply action for this chat yet.",
            retryable = true
        ))
        assertEquals("failed", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertEquals("OpenBubbles has no reply action for this chat yet.", state.value.error)
        assertTrue(state.value.sendingThreads.isEmpty())
    }

    @Test fun chatWithoutReplyActionIsRejectedOnTheWatch() {
        state.value = state.value.copy(
            connected = true,
            relayEnabled = true,
            phoneLocked = true,
            threads = mapOf("chat" to WireThread(
                id = "chat",
                title = "Alex",
                address = "+15551234567",
                canSend = true
            )),
            conversations = emptyList(),
            queuedSends = emptyList(),
            sendingThreads = emptySet()
        )
        assertFalse(WatchRepository.sendReply("chat", "Send now"))
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(state.value.error!!.contains("reply action"))
    }

    @Test fun lockedLiveReplyDispatchesThroughTheNotification() {
        pending.clear()
        state.value = state.value.copy(
            connected = true,
            relayEnabled = true,
            phoneLocked = true,
            threads = mapOf("chat" to WireThread(
                id = "chat",
                title = "Alex",
                address = "+15551234567",
                canReply = true,
                canSend = true
            )),
            conversations = emptyList(),
            queuedSends = emptyList(),
            sendingThreads = emptySet()
        )
        assertTrue(WatchRepository.sendReply("chat", "Live reply"))
        val queued = state.value.queuedSends.single()
        assertEquals(0L, queued.nextAttemptAt)
    }

    @Test fun savedReplyActionSendsWhileLockedWithoutALiveNotification() {
        pending.clear()
        state.value = state.value.copy(
            connected = true,
            relayEnabled = true,
            phoneLocked = true,
            threads = mapOf("chat" to WireThread(
                id = "chat",
                title = "Alex",
                notificationActive = false,
                canReply = true
            )),
            conversations = emptyList(),
            queuedSends = emptyList(),
            sendingThreads = emptySet()
        )
        assertTrue(WatchRepository.sendReply("chat", "Saved reply"))
        assertEquals(1, state.value.queuedCount)
        assertTrue(state.value.info!!.contains("outbox"))
    }

    @Test fun unlockOnlyUpdatesPhoneState() {
        state.value = state.value.copy(
            phoneLocked = true,
            queuedSends = listOf(QueuedSend(
                requestId = "request",
                threadId = "chat",
                text = "Hello",
                attempts = 2,
                nextAttemptAt = System.currentTimeMillis() + 60_000L
            ))
        )
        val retryAt = state.value.queuedSends.single().nextAttemptAt
        WatchRepository.onPong(PongPayload(relayEnabled = false, phoneLocked = false))
        assertFalse(state.value.phoneLocked)
        assertEquals(retryAt, state.value.queuedSends.single().nextAttemptAt)
        assertTrue(state.value.info!!.contains("unlocked", ignoreCase = true))
    }

    @Test fun directBridgeCreatesAConversationForANewAddress() {
        state.value = state.value.copy(directBridge = true)
        val id = WatchRepository.findChatByAddress("+1 (555) 123-4567")
        assertEquals("new:+15551234567", id)
        assertTrue(state.value.canReplyTo(id!!))
        assertFalse(state.value.threads.getValue(id).canReply)
        assertTrue(state.value.conversations.any { it.id == id })
        WatchRepository.requestThread(id)
        assertFalse(state.value.requestingThreads.contains(id))
    }

    @Test fun directBridgeSendsWithoutAReplyAction() {
        state.value = state.value.copy(
            directBridge = true,
            relayEnabled = true,
            sendingThreads = emptySet(),
            queuedSends = emptyList(),
            threads = mapOf("chat" to WireThread(
                id = "chat",
                title = "Alex",
                address = "+15551234567"
            )),
            conversations = emptyList()
        )
        pending.clear()
        assertTrue(WatchRepository.sendReply("chat", "New message"))
        assertEquals(1, state.value.queuedCount)
    }

    @Test fun backendConfirmationReplacesTheExactLocalBubbleAndMigratesTheOpenChat() {
        val oldId = "new:+15551234567"
        val canonical = "chat_iMessage;-;+15551234567"
        val thread = WireThread(id = oldId, title = "Alex", address = "+15551234567",
            messages = listOf(local.copy(threadId = oldId, status = "queued")))
        state.value = state.value.copy(threads = mapOf(oldId to thread),
            visibleThreadId = oldId, requestingThreads = setOf(oldId),
            sendingThreads = setOf(oldId), queuedSends = listOf(QueuedSend("request", oldId, "Hello")))
        pending["request"] = oldId
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND,
            ok = true, threadId = canonical,
            message = local.copy(id = "backend-id", threadId = canonical, timestamp = 9_000_000)))
        assertFalse(state.value.threads.containsKey(oldId))
        assertEquals(listOf("backend-id"), state.value.threads.getValue(canonical).messages.map { it.id })
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(state.value.requestingThreads.isEmpty())
        assertTrue(state.value.queuedSends.isEmpty())
        assertEquals(canonical, state.value.visibleThreadId)
        assertEquals(canonical, WatchNavigation.openThread.value)
        assertTrue(pending.isEmpty())
    }

    @Test fun directDiscoveryReplacesStalePreviewAndUnreadState() {
        val old = WireThread(id = "chat", title = "Alex", unread = true,
            messages = listOf(local)).toSummary()
        val found = old.copy(unread = false, lastTimestamp = 2000, lastPreview = "New preview")
        state.value = state.value.copy(directBridge = true, conversations = listOf(old), discoveryRequestId = "r")
        WatchRepository.onGroups(GroupsResult(requestId = "r", chats = listOf(found)))
        assertEquals(found, state.value.conversations.single())
    }

    @Test fun canonicalThreadAdoptsASummaryOnlyAliasAndAllPendingState() {
        val old = WireThread(id = "chat_addr:+15551234567", title = "Alex", address = "+15551234567").toSummary()
        state.value = state.value.copy(threads = emptyMap(), conversations = listOf(old),
            visibleThreadId = old.id, requestingThreads = setOf(old.id), sendingThreads = setOf(old.id),
            queuedSends = listOf(QueuedSend("request", old.id, "Hello")))
        pending["request"] = old.id
        threadRequests[old.id] = "load"
        WatchRepository.onThread(WireThread(id = "canonical", title = "Alex", address = old.address), connected = false)
        assertEquals(listOf("canonical"), state.value.conversations.map { it.id })
        assertEquals("canonical", state.value.visibleThreadId)
        assertEquals("canonical", WatchNavigation.openThread.value)
        assertEquals("canonical", state.value.queuedSends.single().threadId)
        assertEquals(setOf("canonical"), state.value.sendingThreads)
        assertEquals("canonical", pending["request"])
        assertTrue(state.value.requestingThreads.isEmpty())
        assertTrue(threadRequests.isEmpty())
    }

    @Test fun syncMigratesSummaryOnlyAliasesBeforeReplacingTheDirectory() {
        val old = WireThread(id = "old", title = "Alex", address = "alex@example.com").toSummary()
        state.value = state.value.copy(threads = emptyMap(), conversations = listOf(old),
            visibleThreadId = old.id, queuedSends = listOf(QueuedSend("request", old.id, "Hello")))
        pending["request"] = old.id
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = true, relayEnabled = false, conversations = listOf(old.copy(id = "canonical"))), fromCache = true)
        assertEquals(listOf("canonical"), state.value.conversations.map { it.id })
        assertEquals("canonical", state.value.visibleThreadId)
        assertEquals("canonical", state.value.queuedSends.single().threadId)
        assertEquals("canonical", pending["request"])
    }

    @Test fun discoveryMigratesCachedHistoryAndQueuedSends() {
        val old = WireThread(id = "old", title = "Alex", address = "alex@example.com", messages = listOf(local.copy(threadId = "old")))
        state.value = state.value.copy(threads = mapOf(old.id to old), conversations = listOf(old.toSummary()),
            discoveryRequestId = "discover", queuedSends = listOf(QueuedSend("request", old.id, "Hello")))
        pending["request"] = old.id
        WatchRepository.onGroups(GroupsResult(requestId = "discover", chats = listOf(old.toSummary().copy(id = "canonical"))))
        assertEquals(setOf("canonical"), state.value.threads.keys)
        assertEquals("canonical", state.value.threads.getValue("canonical").messages.single().threadId)
        assertEquals("canonical", state.value.queuedSends.single().threadId)
        assertEquals("canonical", pending["request"])
    }

    @Test fun aliasMigrationPreservesReadAndNotificationWatermarksWithoutMergingGroups() {
        val old = WireThread(id = "old", title = "Alex", address = "alex@example.com", messages = listOf(local.copy(id = "history")))
        val summaryOnly = old.toSummary().copy(id = "other-old")
        val group = old.toSummary().copy(id = "group", isGroup = true)
        state.value = state.value.copy(threads = mapOf(old.id to old), conversations = listOf(old.toSummary(), summaryOnly, group))
        WatchRepository.markReadLocally(summaryOnly.id)
        WatchNotifications.markNotified(context, summaryOnly.id, 5000)
        WatchRepository.onThread(WireThread(id = "canonical", title = "Alex", address = old.address, unread = true,
            messages = listOf(local.copy(id = "incoming", threadId = "canonical", fromMe = false, timestamp = 2000))), connected = false)
        assertEquals(setOf("canonical", "group"), state.value.conversations.map { it.id }.toSet())
        val canonical = state.value.threads.getValue("canonical")
        assertEquals(setOf("history", "incoming"), canonical.messages.map { it.id }.toSet())
        assertTrue(canonical.messages.all { it.threadId == "canonical" })
        assertFalse(canonical.unread)
        assertEquals(5000L, WatchNotifications.lastNotified(context, "canonical"))
    }

    @Test fun confirmationWithoutABackendMessageStillMigratesTheNewChatId() {
        val oldId = "new:alex@example.com"
        val thread = WireThread(id = oldId, title = "Alex", address = "alex@example.com", messages = listOf(local.copy(threadId = oldId)))
        state.value = state.value.copy(threads = mapOf(oldId to thread), conversations = listOf(thread.toSummary()),
            visibleThreadId = oldId, sendingThreads = setOf(oldId), queuedSends = emptyList())
        pending["request"] = oldId
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND,
            ok = true, threadId = "canonical"))
        assertEquals(setOf("canonical"), state.value.threads.keys)
        assertEquals("sent", state.value.threads.getValue("canonical").messages.single().status)
        assertEquals("canonical", state.value.visibleThreadId)
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun replyConfirmationMigratesAndReplacesOnlyItsExactLocalBubble() {
        val oldId = "old"
        val other = local.copy(id = "local-other", threadId = oldId, status = "queued")
        val thread = WireThread(id = oldId, title = "Alex", address = "alex@example.com",
            messages = listOf(local.copy(threadId = oldId), other))
        state.value = state.value.copy(threads = mapOf(oldId to thread), conversations = listOf(thread.toSummary()),
            sendingThreads = setOf(oldId), queuedSends = listOf(QueuedSend("other", oldId, "Hello")))
        pending["request"] = oldId
        val confirmation = ActionResult(requestId = "request", action = Protocol.ACTION_REPLY, ok = true,
            threadId = "canonical", message = local.copy(id = "backend", threadId = "canonical"))
        WatchRepository.onActionResult(confirmation)
        WatchRepository.onActionResult(confirmation)
        assertEquals(setOf("local-other", "backend"), state.value.threads.getValue("canonical").messages.map { it.id }.toSet())
        assertEquals(2, state.value.threads.getValue("canonical").messages.size)
        assertEquals("canonical", state.value.queuedSends.single().threadId)
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun failedReplyResultUsesItsPendingTargetEvenWhenThePhoneReturnsAnotherId() {
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_REPLY,
            ok = false, threadId = "canonical", error = "Not confirmed", retryable = true))
        assertEquals("failed", status())
        assertEquals("Not confirmed", state.value.error)
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun missingTransportCallbackIsNotRequeued() {
        assertTrue(WatchRepository.prepareSendDispatch("request"))
        WatchRepository.onSendDispatched("request", "chat", local.id, null)
        assertEquals("sending", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertEquals("chat", pending["request"])
        confirm()
        assertEquals("sent", status())
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun definiteTransportFailureMakesTheDurableDispatchReplayableAgain() {
        assertTrue(WatchRepository.prepareSendDispatch("request"))
        assertTrue(state.value.queuedSends.single().dispatching)
        WatchRepository.onSendDispatched("request", "chat", local.id, false)
        assertFalse(state.value.queuedSends.single().dispatching)
        assertEquals("queued", status())
    }

    private fun restartRepository() {
        WatchRepository::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        pending.clear()
        WatchRepository.attach(context)
    }

    @Test fun restartNeverReplaysAnUncertainDispatchOrRestoresPatchCapability() {
        state.value = state.value.copy(conversations = listOf(state.value.threads.getValue("chat").toSummary()), directBridge = true)
        assertTrue(WatchRepository.prepareSendDispatch("request"))
        context.getSharedPreferences("openbubbles_wear", Context.MODE_PRIVATE).edit().putBoolean("direct_bridge", true).commit()
        restartRepository()
        assertFalse(state.value.directBridge)
        assertEquals("unconfirmed", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(state.value.error!!.contains("avoid a duplicate"))
    }

    @Test fun disconnectAndCachedSyncCannotReenableThePatch() {
        state.value = state.value.copy(directBridge = true, connected = true, reachable = true)
        WatchRepository.onPeerDisconnected()
        assertFalse(state.value.directBridge)
        WatchRepository.onPeerConnected()
        assertFalse(state.value.directBridge)
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = false, relayEnabled = false, directBridge = true), fromCache = true)
        assertFalse(state.value.directBridge)
        WatchRepository.onPong(PongPayload(relayEnabled = false, directBridge = true))
        assertTrue(state.value.directBridge)
        WatchRepository.onSync(SyncPayload(generatedAt = 3000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = false, relayEnabled = false), fromCache = true)
        assertTrue(state.value.directBridge)
    }

    @Test fun newChatPlaceholderDoesNotPretendToHaveANotificationReplyAction() {
        state.value = state.value.copy(directBridge = true, relayEnabled = true, sendingThreads = emptySet(), queuedSends = emptyList())
        val id = WatchRepository.findChatByAddress("new@example.com")!!
        WatchRepository.onPeerDisconnected()
        assertFalse(state.value.canReplyTo(id))
        assertFalse(WatchRepository.sendReply(id, "Hello"))
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun freshSummaryReplyAvailabilityOverridesStaleCachedFlags() {
        val cached = WireThread(id = "chat", title = "Alex", canReply = false)
        state.value = state.value.copy(threads = mapOf("chat" to cached), conversations = listOf(cached.toSummary().copy(canReply = true)),
            relayEnabled = true, queuedSends = emptyList(), sendingThreads = emptySet())
        assertTrue(state.value.canReplyTo("chat"))
        assertTrue(WatchRepository.sendReply("chat", "Saved reply"))
        state.value = state.value.copy(threads = mapOf("chat" to cached.copy(canReply = true)),
            conversations = listOf(cached.toSummary()))
        assertFalse(state.value.canReplyTo("chat"))
    }

    @Test fun threadTimeoutClearsLoadingEvenWhenCachedHistoryExists() {
        threadRequests["chat"] = "load"
        state.value = state.value.copy(requestingThreads = setOf("chat"))
        WatchRepository.failThreadRequest("chat", "load", "Timed out")
        assertTrue(state.value.requestingThreads.isEmpty())
        assertTrue(threadRequests.isEmpty())
        assertEquals("Timed out", state.value.error)
        assertTrue(state.value.threads.containsKey("chat"))
    }

    @Test fun completedOrOlderRequestTimeoutCannotClearANewerRequestOrOverwriteItsNotice() {
        threadRequests["chat"] = "old"
        state.value = state.value.copy(requestingThreads = setOf("chat"))
        WatchRepository.onThread(state.value.threads.getValue("chat"), connected = false)
        threadRequests["chat"] = "new"
        state.value = state.value.copy(requestingThreads = setOf("chat"), error = "Keep this")
        WatchRepository.failThreadRequest("chat", "old", "Stale timeout", unreachable = true)
        assertEquals(setOf("chat"), state.value.requestingThreads)
        assertEquals("new", threadRequests["chat"])
        assertEquals("Keep this", state.value.error)
    }

    @Test fun threadErrorClearsPendingRequestsAndTheirLaterTimeoutsAreIgnored() {
        threadRequests["chat"] = "load"
        threadRequests["other"] = "other-load"
        state.value = state.value.copy(requestingThreads = setOf("chat", "other"))
        WatchRepository.onThreadPayload(ThreadPayload(error = "Backend unavailable"))
        WatchRepository.failThreadRequest("chat", "load", "Stale timeout")
        assertTrue(state.value.requestingThreads.isEmpty())
        assertTrue(threadRequests.isEmpty())
        assertEquals("Backend unavailable", state.value.error)
    }

    @Test fun notificationRemoteInputUsesTheSameDirectSendOutbox() {
        pending.clear()
        state.value = state.value.copy(directBridge = true, relayEnabled = true, threads = emptyMap(),
            conversations = listOf(WireThread(id = "summary", title = "Alex", address = "alex@example.com").toSummary()),
            queuedSends = emptyList(), sendingThreads = emptySet())
        val intent = Intent(context, WatchNotificationReceiver::class.java)
            .setAction(WatchNotificationReceiver.ACTION_REPLY)
            .putExtra(WatchNotificationReceiver.EXTRA_THREAD_ID, "summary")
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(WatchNotificationReceiver.KEY_REPLY).build()), intent,
            Bundle().apply { putCharSequence(WatchNotificationReceiver.KEY_REPLY, "  From notification  ") })
        WatchNotificationReceiver().onReceive(context, intent)
        assertEquals("From notification", state.value.queuedSends.single().text)
        assertEquals("summary", state.value.queuedSends.single().threadId)
        assertEquals("From notification", state.value.threads.getValue("summary").messages.single().text)
    }

    @Test fun syncAndDiscoveryDoNotRestoreUnreadBadgesForALocallyReadChat() {
        val summary = WireThread(id = "chat", title = "Alex", unread = true,
            messages = listOf(local.copy(id = "inbound", fromMe = false))).toSummary()
        state.value = state.value.copy(conversations = listOf(summary))
        WatchRepository.markReadLocally("chat")
        WatchRepository.onSync(SyncPayload(generatedAt = 2000, phoneName = "Phone", bridgeVersion = "test",
            notificationAccess = true, relayEnabled = false, conversations = listOf(summary)), fromCache = true)
        assertFalse(state.value.conversations.single().unread)
        state.value = state.value.copy(discoveryRequestId = "discover", directBridge = true)
        WatchRepository.onGroups(GroupsResult(requestId = "discover", chats = listOf(summary)))
        assertFalse(state.value.conversations.single().unread)
    }

    @Test fun transportFailureClearsTheLivePatchCapabilityAndPendingLoads() {
        state.value = state.value.copy(directBridge = true, connected = true, reachable = true, requestingThreads = setOf("chat"))
        threadRequests["chat"] = "load"
        WatchRepository.failThreadRequest("chat", "load", "Phone unreachable", unreachable = true)
        assertFalse(state.value.directBridge)
        assertFalse(state.value.phoneOnline)
        assertTrue(state.value.requestingThreads.isEmpty())
        assertEquals("Phone unreachable", state.value.error)
    }

    @Test fun unconfirmedSendTimeoutNeverReplaysAndALateResultCanStillConfirmIt() {
        WatchRepository.onSendDispatched("request", "chat", local.id, null)
        WatchRepository.onSendTimedOut("request", local.id)
        assertEquals("unconfirmed", status())
        assertTrue(state.value.queuedSends.isEmpty())
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(state.value.error!!.contains("avoid a duplicate"))
        confirm()
        assertEquals("sent", status())
        WatchRepository.onSendTimedOut("request", local.id)
        assertEquals("sent", status())
        assertNull(state.value.error)
    }

    @Test fun migrationDuringTransportKeepsThePendingSendOnItsCanonicalId() {
        val oldId = "new:alex@example.com"
        val thread = WireThread(id = oldId, title = "Alex", address = "alex@example.com", messages = listOf(local.copy(threadId = oldId)))
        state.value = state.value.copy(threads = mapOf(oldId to thread), conversations = listOf(thread.toSummary()),
            queuedSends = listOf(QueuedSend("request", oldId, "Hello")), sendingThreads = setOf(oldId))
        pending["request"] = oldId
        assertTrue(WatchRepository.prepareSendDispatch("request"))
        WatchRepository.onThread(WireThread(id = "canonical", title = "Alex", address = thread.address), connected = false)
        WatchRepository.onSendDispatched("request", oldId, local.id, null)
        assertEquals("canonical", pending["request"])
        assertEquals("sending", state.value.threads.getValue("canonical").messages.single().status)
        assertEquals(setOf("canonical"), state.value.sendingThreads)
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun directHistoryReplacesAllOldNotificationIdsWithoutCollapsingRepeatedBackendMessages() {
        val derived = Protocol.messageId("chat", 1000, "Alex", "Hello")
        val old = WireThread("chat", "Alex", messages = listOf(message(derived, 1), message("$derived-1")))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        val actual = listOf(message("guid-1"), message("guid-2"))
        WatchRepository.onThread(old.copy(messages = actual, directHistory = true), connected = false)
        assertEquals(actual, state.value.threads.getValue("chat").messages)
        assertTrue(state.value.threads.getValue("chat").directHistory)
    }

    @Test fun directHistoryRemovesDeletionsAndEditsItsWindowWhilePreservingOlderBackendGuids() {
        val old = WireThread("chat", "Alex", directHistory = true, messages = listOf(
            message("older", 1), message("m-not-a-notification-hash", 2),
            message("edited", text = "Original"), message("deleted", 1001)))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(messages = listOf(message("edited", text = "Corrected"))), connected = false)
        val merged = state.value.threads.getValue("chat").messages
        assertEquals(listOf("older", "m-not-a-notification-hash", "edited"), merged.map { it.id })
        assertEquals("Corrected", merged.last().text)
    }

    @Test fun eightyMessageSnapshotDoesNotDiscardOlderGuidHistory() {
        val old = WireThread("chat", "Alex", directHistory = true, messages = listOf(message("older", 1)))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        val recent = List(80) { message("guid-$it", 1000L + it) }
        WatchRepository.onThread(old.copy(messages = recent), connected = false)
        assertEquals(listOf(message("older", 1)) + recent, state.value.threads.getValue("chat").messages)
    }

    @Test fun authoritativeCachedDeletionCanMoveLastActivityBackward() {
        val old = WireThread("chat", "Alex", directHistory = true, updatedAt = 2000,
            messages = listOf(message("kept"), message("deleted-latest", 2000)))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(updatedAt = 1000, messages = listOf(message("kept"))), connected = false)
        assertEquals(listOf("kept"), state.value.threads.getValue("chat").messages.map { it.id })
        WatchRepository.onThread(old.copy(updatedAt = 0, messages = emptyList()), connected = false)
        assertTrue(state.value.threads.getValue("chat").messages.isEmpty())
    }

    @Test fun authoritativeEmptyHistoryRemovesRemoteMessagesButKeepsUnmatchedOptimisticSends() {
        val queued = local.copy(status = "queued")
        val sending = local.copy(id = "local-other", text = "Other")
        val optimistic = local.copy(id = "auto-pending", status = "sent")
        val old = WireThread("chat", "Alex", messages = listOf(message("real"),
            message(Protocol.messageId("chat", 1000, "Alex", "Hello")), queued, sending, optimistic))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(messages = emptyList(), directHistory = true), connected = false)
        assertEquals(setOf(queued.id, sending.id, optimistic.id), state.value.threads.getValue("chat").messages.map { it.id }.toSet())
    }

    @Test fun authoritativeHistoryPreservesMediaForTheSameRealIdInsideTheReplacementWindow() {
        val photo = message("guid-photo").copy(attachment = true, mediaId = "cached-photo", mediaMime = "image/jpeg",
            mediaWidth = 300, mediaHeight = 200, mediaSize = 1024,
            sourceUri = "content://com.openbubbles.messaging.wear.media/photo")
        val old = WireThread("chat", "Alex", messages = listOf(photo), directHistory = true)
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(messages = listOf(message(photo.id, text = "Updated caption"))), connected = false)
        assertEquals(photo.copy(text = "Updated caption"), state.value.threads.getValue("chat").messages.single())
    }

    @Test fun phoneCacheAt240MessagesCannotEvictAnUnmatchedQueuedLocalSend() {
        val queued = local.copy(timestamp = 0, status = "queued")
        val old = WireThread("chat", "Alex", messages = listOf(queued))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(directHistory = true,
            messages = List(240) { message("guid-$it", 1L + it) }), connected = false)
        val merged = state.value.threads.getValue("chat").messages
        assertEquals(220, merged.size)
        assertEquals(queued, merged.first())
        assertEquals("guid-239", merged.last().id)
    }

    @Test fun partialUpdatesPreserveExistingHistoryAndItsDirectMarker() {
        val old = WireThread("chat", "Alex", directHistory = true,
            messages = listOf(message("older", 1), message("newer", 2000)))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onThread(old.copy(messages = listOf(message("delta")), directHistory = false), connected = false)
        val merged = state.value.threads.getValue("chat")
        assertEquals(listOf("older", "delta", "newer"), merged.messages.map { it.id })
        assertTrue(merged.directHistory)
    }

    @Test fun sendConfirmationIsPartialAndCannotDeleteNewerCachedBackendHistory() {
        val other = local.copy(id = "local-other", text = "Other", status = "queued")
        val old = WireThread("chat", "Alex", directHistory = true,
            messages = listOf(message("older", 1), local, other, message("newer", 2000)))
        state.value = state.value.copy(threads = mapOf("chat" to old))
        WatchRepository.onActionResult(ActionResult(requestId = "request", action = Protocol.ACTION_SEND, ok = true,
            threadId = "chat", message = local.copy(id = "confirmed-guid")))
        val merged = state.value.threads.getValue("chat")
        assertEquals(setOf("older", "confirmed-guid", "local-other", "newer"), merged.messages.map { it.id }.toSet())
        assertTrue(merged.directHistory)
        assertTrue(state.value.sendingThreads.isEmpty())
        assertTrue(state.value.queuedSends.isEmpty())
    }

    @Test fun canonicalAuthoritativeHistoryAlsoDiscardsNotificationIdsMigratedFromAnAlias() {
        val old = WireThread("old", "Alex", address = "alex@example.com", messages = listOf(
            message(Protocol.messageId("old", 1, "Alex", "Hello"), 1).copy(threadId = "old"), local.copy(status = "queued", threadId = "old")))
        state.value = state.value.copy(threads = mapOf("old" to old), conversations = listOf(old.toSummary()))
        WatchRepository.onThread(old.copy(id = "chat", directHistory = true, messages = listOf(message("backend"))), connected = false)
        assertEquals(setOf("chat"), state.value.threads.keys)
        assertEquals(setOf("backend", local.id), state.value.threads.getValue("chat").messages.map { it.id }.toSet())
        assertTrue(state.value.threads.getValue("chat").messages.all { it.threadId == "chat" })
    }

    @Test fun correlatedThreadErrorClearsOnlyItsMatchingLoad() {
        threadRequests["chat"] = "load"
        threadRequests["other"] = "other-load"
        state.value = state.value.copy(requestingThreads = setOf("chat", "other"))
        WatchRepository.onThreadPayload(ThreadPayload(requestedThreadId = "chat", error = "Chat unavailable"))
        assertEquals(setOf("other"), state.value.requestingThreads)
        assertEquals(mapOf("other" to "other-load"), threadRequests)
        assertEquals("Chat unavailable", state.value.error)
        WatchRepository.failThreadRequest("chat", "load", "Stale timeout")
        assertEquals("Chat unavailable", state.value.error)
    }

    @Test fun correlatedCachedThreadWithRefreshErrorKeepsBodyAndReportsErrorWithoutMarkingFresh() {
        val cached = WireThread("chat", "Group", isGroup = true, messages = listOf(
            message("cached-group", 1, "Failed group body")
        ))
        threadRequests["chat"] = "load"
        threadRequests["other"] = "other-load"
        state.value = state.value.copy(
            threads = mapOf("chat" to cached),
            requestingThreads = setOf("chat", "other"),
            lastSyncAt = 1234L
        )

        WatchRepository.onThreadPayload(ThreadPayload(
            requestedThreadId = "chat",
            thread = cached,
            error = "OpenBubbles history unavailable"
        ))

        assertEquals("Failed group body", state.value.threads.getValue("chat").messages.single().text)
        assertEquals("OpenBubbles history unavailable", state.value.error)
        assertEquals(setOf("other"), state.value.requestingThreads)
        assertEquals(mapOf("other" to "other-load"), threadRequests)
        assertEquals(1234L, state.value.lastSyncAt)
    }

    @Test fun erroredCachedHistoryCannotRemoveANewerFailedGroupMessage() {
        val older = message("older", 1, "Earlier message")
        val failed = message("failed-group", 2, "Missing group message").copy(fromMe = true, status = "failed")
        val current = WireThread("chat", "Group", isGroup = true, directHistory = true,
            updatedAt = 2, messages = listOf(older, failed))
        state.value = state.value.copy(threads = mapOf("chat" to current))

        WatchRepository.onThreadPayload(ThreadPayload(requestedThreadId = "chat",
            thread = current.copy(messages = listOf(older), updatedAt = 1), error = "History lookup failed"))

        assertEquals(listOf("older", "failed-group"), state.value.threads.getValue("chat").messages.map { it.id })
        assertEquals("failed", status("failed-group"))
        assertEquals("History lookup failed", state.value.error)
    }

    @Test fun successfulThreadPayloadClearsAnOlderError() {
        state.value = state.value.copy(error = "Previous refresh failed")

        WatchRepository.onThreadPayload(ThreadPayload(
            requestedThreadId = "chat",
            thread = WireThread("chat", "Alex")
        ))

        assertNull(state.value.error)
    }

    @Test fun lateCorrelatedErrorCannotClearAnotherLoadOrReplaceItsNotice() {
        threadRequests["other"] = "other-load"
        state.value = state.value.copy(requestingThreads = setOf("other"), error = "Keep this")
        WatchRepository.onThreadPayload(ThreadPayload(requestedThreadId = "chat", error = "Stale error"))
        assertEquals(setOf("other"), state.value.requestingThreads)
        assertEquals(mapOf("other" to "other-load"), threadRequests)
        assertEquals("Keep this", state.value.error)
    }

    @Test fun correlatedCanonicalReplyClearsOriginalAndCanonicalLoadsWithoutTouchingOtherChats() {
        val old = WireThread("original", "Alex")
        state.value = state.value.copy(threads = emptyMap(), conversations = listOf(old.toSummary()),
            visibleThreadId = old.id, requestingThreads = setOf(old.id, "chat", "other"))
        threadRequests[old.id] = "original-load"
        threadRequests["chat"] = "canonical-load"
        threadRequests["other"] = "other-load"
        WatchRepository.onThreadPayload(ThreadPayload(requestedThreadId = old.id,
            thread = WireThread("chat", "Alex", directHistory = true, messages = listOf(message("guid")))))
        assertEquals(setOf("other"), state.value.requestingThreads)
        assertEquals(mapOf("other" to "other-load"), threadRequests)
        assertEquals(listOf("chat"), state.value.conversations.map { it.id })
        assertEquals("chat", state.value.visibleThreadId)
        assertEquals("chat", WatchNavigation.openThread.value)
        WatchRepository.failThreadRequest(old.id, "original-load", "Stale timeout")
        assertNull(state.value.error)
    }

    @Test fun legacySuccessfulThreadReplyStillLeavesUnrelatedLoadsPending() {
        threadRequests["chat"] = "load"
        threadRequests["other"] = "other-load"
        state.value = state.value.copy(requestingThreads = setOf("chat", "other"))
        WatchRepository.onThreadPayload(ThreadPayload(thread = WireThread("chat", "Alex")))
        assertEquals(setOf("other"), state.value.requestingThreads)
        assertEquals(mapOf("other" to "other-load"), threadRequests)
    }
}
