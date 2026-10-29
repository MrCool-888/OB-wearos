package com.openbubbles.wear.bridge

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.openbubbles.wear.protocol.GroupsResult
import com.openbubbles.wear.protocol.ThreadPayload
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PatchBridgeIntegrationTest {
    private lateinit var context: Context
    private val requests = mutableListOf<JSONObject>()
    private var respond: (JSONObject) -> String? = { """{"apiVersion":1,"ok":true}""" }
    private val id = "chat_iMessage;-;+15551234567"

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
            val body = JSONObject(request.data.getString("json")!!)
            requests += body
            respond(body)?.let { json ->
                request.replyTo.send(Message.obtain(null, 2).apply {
                    data = Bundle().apply { putString("json", json) }
                })
            }
            true
        })
        shadowOf(context as Application).setComponentNameAndServiceForBindService(
            ComponentName("com.openbubbles.messaging", "com.openbubbles.wear.patch.CompanionService"),
            messenger.binder
        )
        PatchBridgeClient::class.java.getDeclaredField("available").apply { isAccessible = true }
            .setBoolean(null, true)
        PatchBridgeClient::class.java.getDeclaredField("patchInstalled").apply { isAccessible = true }
            .setBoolean(null, true)
        PatchBridgeClient::class.java.getDeclaredField("bridgeEnabled").apply { isAccessible = true }
            .setBoolean(null, true)
        PatchBridgeClient::class.java.getDeclaredField("checkedAt").apply { isAccessible = true }
            .setLong(null, System.currentTimeMillis())
        NotificationCenter::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(null, false)
        BridgePreferences(context).lastChatDiscoveryAt = Long.MAX_VALUE
        NotificationCenter.init(context)
        NotificationCenter.clearCache(context)
        BridgePreferences(context).relayEnabled = true
        ShadowLooper.idleMainLooper()
        requests.clear()
    }

    private fun store(): BridgeStore = NotificationCenter::class.java.getDeclaredField("store")
        .apply { isAccessible = true }.get(NotificationCenter) as BridgeStore

    @After fun cleanup() {
        PatchBridgeClient::class.java.getDeclaredField("available").apply { isAccessible = true }
            .setBoolean(null, false)
        PatchBridgeClient::class.java.getDeclaredField("patchInstalled").apply { isAccessible = true }
            .setBoolean(null, false)
        PatchBridgeClient::class.java.getDeclaredField("bridgeEnabled").apply { isAccessible = true }
            .setBoolean(null, true)
        PatchBridgeClient::class.java.getDeclaredField("checkedAt").apply { isAccessible = true }
            .setLong(null, 0L)
        PatchBridgeClient::class.java.getDeclaredField("backendState").apply { isAccessible = true }
            .set(null, "Not connected")
        BridgePreferences(context).relayEnabled = true
        BridgePreferences(context).lastChatDiscoveryAt = 0L
        NotificationCenter.clearCache(context)
        NotificationCenter::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    @Test fun incompatibleAndMalformedRepliesFailClosed() {
        for (json in listOf("not json", """{"apiVersion":2,"ok":true}""", """{"ok":true}""")) {
            respond = { json }
            var reply: PatchBridgeClient.Reply? = null
            PatchBridgeClient.request(context, "status") { reply = it }
            ShadowLooper.idleMainLooper()
            assertFalse(reply!!.ok)
            assertNull(reply!!.json)
            assertNotNull(reply!!.error)
        }
    }

    @Test fun requestFieldsCannotOverrideOperationOrApiVersion() {
        PatchBridgeClient.request(context, "status", JSONObject().put("op", "send").put("apiVersion", 99)) {}
        ShadowLooper.idleMainLooper()
        assertEquals("status", requests.single().getString("op"))
        assertEquals(1, requests.single().getInt("apiVersion"))
    }

    @Test fun timeoutCompletesOnceAndDoesNotReplayTheSend() {
        respond = { null }
        val replies = mutableListOf<PatchBridgeClient.Reply>()
        PatchBridgeClient.request(context, "send", timeoutMs = 100) { replies += it }
        ShadowLooper.idleMainLooper()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(1, replies.size)
        assertTrue(replies.single().timedOut)
        assertEquals(listOf("send"), requests.map { it.getString("op") })
    }

    @Test fun directDiscoveryReturnsPageEvenWhenPhoneCacheTrimsOlderPlaceholders() {
        val chats = JSONArray()
        repeat(100) { index ->
            chats.put(JSONObject().put("id", "iMessage;-;person$index@example.com")
                .put("title", "Person $index").put("address", "person$index@example.com")
                .put("lastTimestamp", 10_000L - index).put("lastPreview", "Preview $index")
                .put("participants", JSONArray().put("person$index@example.com")).put("unread", true))
        }
        respond = { JSONObject().put("apiVersion", 1).put("ok", true).put("chats", chats).toString() }
        var result: GroupsResult? = null
        NotificationCenter.discoverChatsDirect("directory") { result = it }
        ShadowLooper.idleMainLooper()
        assertNull(result!!.error)
        assertEquals(100, result!!.chats.size)
        assertEquals("Person 0", result!!.chats.first().title)
        assertEquals("Person 99", result!!.chats.last().title)
        assertEquals(BridgeStore.MAX_PLACEHOLDERS, store().snapshot().count { it.messages.isEmpty() })
        val summary = NotificationCenter.threadFor(result!!.chats.first().id)!!.toSummary()
        assertTrue(summary.unread)
        assertTrue(summary.canReply)
        assertEquals(10_000L, summary.lastTimestamp)
        assertEquals("Preview 0", summary.lastPreview)
        assertEquals(listOf("person0@example.com"), summary.participants)
    }

    @Test fun directDiscoveryRequestsAndReturnsAdvancingPages() {
        val ids = (0 until 95).map { "iMessage;-;page$it@example.com" }
        respond = { request ->
            val offset = request.optInt("offset")
            val chats = JSONArray()
            ids.drop(offset).take(40).forEachIndexed { index, id ->
                chats.put(JSONObject().put("id", id).put("title", "Page ${offset + index}")
                    .put("address", id.substringAfter(";-;")).put("lastTimestamp", 10_000L - offset - index))
            }
            val next = (offset + chats.length()).takeIf { it < ids.size }
            JSONObject().put("apiVersion", 1).put("ok", true).put("offset", offset)
                .put("chats", chats).also { if (next != null) it.put("nextOffset", next) }.toString()
        }

        val returned = mutableListOf<GroupsResult>()
        fun requestPage(offset: Int) {
            NotificationCenter.discoverChatsDirect("paged", offset) { returned += it }
            ShadowLooper.idleMainLooper()
        }
        requestPage(0)
        assertEquals(40, returned.single().chats.size)
        assertEquals(40, returned.single().nextOffset)
        requestPage(returned.last().nextOffset!!)
        assertEquals(40, returned.last().chats.size)
        assertEquals(80, returned.last().nextOffset)
        requestPage(returned.last().nextOffset!!)
        assertEquals(15, returned.last().chats.size)
        assertNull(returned.last().nextOffset)
        assertEquals(listOf(0, 40, 80), requests.map { it.getInt("offset") })
        assertEquals(95, returned.flatMap { it.chats }.map { it.id }.distinct().size)
    }

    @Test fun historyMigratesTheAliasAndRewritesEveryMessageThreadId() {
        val alias = "chat_contact:42"
        store().upsertIncoming(WireThread(id = alias, title = "Alex", address = "+15551234567",
            messages = listOf(WireMessage("old", alias, "Older", 1))))
        respond = { """{"apiVersion":1,"ok":true,"thread":{"id":"iMessage;-;+15551234567",
            "address":"+15551234567","messages":[{"id":"new","text":"Recent","timestamp":2}]}}""" }
        var payload: ThreadPayload? = null
        NotificationCenter.threadPayload(alias) { payload = it }
        ShadowLooper.idleMainLooper()
        assertEquals(id, payload!!.thread!!.id)
        assertEquals(listOf("old", "new"), payload!!.thread!!.messages.map { it.id })
        assertTrue(payload!!.thread!!.messages.all { it.threadId == id })
        assertNull(NotificationCenter.threadFor(alias))
    }

    @Test fun directSendIncludesRequestIdAndCachedRecipientAndReturnsTheBackendMessage() {
        store().upsertIncoming(WireThread(id = "new:+15551234567", title = "Alex", address = "+15551234567"))
        respond = { """{"apiVersion":1,"ok":true,"threadId":"iMessage;-;+15551234567",
            "thread":{"id":"iMessage;-;+15551234567","address":"+15551234567",
            "messages":[{"id":"real","text":"Hello","timestamp":5,"fromMe":true}]},
            "message":{"id":"real","text":"Hello","timestamp":5,"fromMe":true}}""" }
        var result: NotificationCenter.SendResult? = null
        NotificationCenter.send("request-id", "new:+15551234567", "Hello") { result = it }
        ShadowLooper.idleMainLooper()
        assertNull(result!!.error)
        assertEquals(id, result!!.threadId)
        assertEquals("real", result!!.message!!.id)
        assertEquals("request-id", requests.single().getString("requestId"))
        assertEquals("+15551234567", requests.single().getString("address"))
        assertNull(NotificationCenter.threadFor("new:+15551234567"))
    }

    @Test fun refreshedFailedGroupMessageKeepsItsBodyAndStatusOnTheWire() {
        val groupId = "chat_" + "abcdef01".repeat(9)
        val rawId = groupId.removePrefix("chat_")
        store().upsertIncoming(WireThread(id = groupId, title = "Group", isGroup = true,
            messages = listOf(WireMessage("old", groupId, "Earlier message", 1))))
        respond = { """{"apiVersion":1,"ok":true,"thread":{"id":"$rawId","title":"Group",
            "isGroup":true,"messages":[{"id":"failed-group","text":"Group message",
            "timestamp":2,"fromMe":true,"status":"failed"}]}}""" }
        var payload: ThreadPayload? = null
        NotificationCenter.threadPayload(groupId) { payload = it }
        ShadowLooper.idleMainLooper()
        assertNull(payload!!.error)
        val decoded = com.openbubbles.wear.protocol.Wire.decode<ThreadPayload>(
            com.openbubbles.wear.protocol.Wire.encode(payload!!))!!
        val failed = decoded.thread!!.messages.single { it.id == "failed-group" }
        assertEquals("Group message", failed.text)
        assertEquals("failed", failed.status)
        assertEquals(groupId, failed.threadId)
        assertEquals("failed", NotificationCenter.threadFor(groupId)!!.messages.last().status)
        assertEquals(listOf("thread"), requests.map { it.getString("op") })
    }

    @Test fun failedGroupRefreshReturnsCachedBodyAndTheActualLookupError() {
        val groupId = "chat_iMessage;+;group-id"
        store().upsertIncoming(WireThread(id = groupId, title = "Group", isGroup = true,
            messages = listOf(WireMessage("cached", groupId, "Earlier group message", 1))))
        respond = { """{"apiVersion":1,"ok":false,"error":"Conversation not found."}""" }
        var payload: ThreadPayload? = null
        NotificationCenter.threadPayload(groupId) { payload = it }
        ShadowLooper.idleMainLooper()
        assertEquals("Earlier group message", payload!!.thread!!.messages.single().text)
        assertEquals("Conversation not found.", payload!!.error)
        assertEquals(listOf("thread"), requests.map { it.getString("op") })
    }

    @Test fun malformedSuccessfulHistoryCannotSilentlyReturnTheCache() {
        store().upsertIncoming(WireThread(id = id, title = "Alex", messages = listOf(
            WireMessage("cached", id, "Cached message", 1))))
        for (body in listOf("{}", """{"id":"iMessage;-;+15551234567"}""",
            """{"id":"iMessage;-;+15551234567","messages":"invalid"}""")) {
            respond = { """{"apiVersion":1,"ok":true,"thread":$body}""" }
            var payload: ThreadPayload? = null
            NotificationCenter.threadPayload(id) { payload = it }
            ShadowLooper.idleMainLooper()
            assertEquals("Cached message", payload!!.thread!!.messages.single().text)
            assertEquals("OpenBubbles returned no message history. Cached messages may be incomplete.", payload!!.error)
        }
    }

    @Test fun directSendAndRefreshPreserveAnAsynchronousGroupFailure() {
        val groupId = "chat_iMessage;+;group-id"
        val rawId = groupId.removePrefix("chat_")
        var status = "sending"
        respond = { JSONObject().put("apiVersion", 1).put("ok", true)
            .put("threadId", rawId).put("message", JSONObject().put("id", "backend-group")
                .put("text", "Group message").put("timestamp", 2).put("fromMe", true).put("status", status))
            .put("thread", JSONObject().put("id", rawId).put("isGroup", true)
                .put("messages", JSONArray().put(JSONObject().put("id", "backend-group")
                    .put("text", "Group message").put("timestamp", 2).put("fromMe", true).put("status", status))))
            .toString() }
        var result: NotificationCenter.SendResult? = null
        NotificationCenter.send("group-request", groupId, "Group message") { result = it }
        ShadowLooper.idleMainLooper()
        assertNull(result!!.error)
        assertEquals("sending", result!!.message!!.status)
        status = "failed"
        var refreshed: ThreadPayload? = null
        NotificationCenter.threadPayload(groupId) { refreshed = it }
        ShadowLooper.idleMainLooper()
        assertEquals("failed", refreshed!!.thread!!.messages.single().status)
        assertEquals(listOf("send", "thread"), requests.map { it.getString("op") })
    }

    @Test fun failedSendIsNotRetriedAndDoesNotRecordASentMessage() {
        respond = { """{"apiVersion":1,"ok":false,"error":"Backend unavailable"}""" }
        var result: NotificationCenter.SendResult? = null
        NotificationCenter.send("request-id", id, "Hello") { result = it }
        ShadowLooper.idleMainLooper()
        assertEquals("Backend unavailable", result!!.error)
        assertFalse(result!!.retryable)
        assertEquals(1, requests.size)
        assertNull(NotificationCenter.threadFor(id))
    }

    @Test fun pausedRelayDoesNotQueryPrivateHistory() {
        BridgePreferences(context).relayEnabled = false
        var payload: ThreadPayload? = null
        NotificationCenter.threadPayload(id) { payload = it }
        ShadowLooper.idleMainLooper()
        assertEquals("Relay is paused on the phone.", payload!!.error)
        assertTrue(requests.isEmpty())
    }

    @Test fun disabledStatusKeepsPatchInstalledButMarksBridgeUnavailable() {
        respond = { """{"apiVersion":1,"ok":true,"enabled":false,"backendState":"Ready"}""" }
        var available: Boolean? = null
        PatchBridgeClient.probe(context, force = true) { available = it }
        ShadowLooper.idleMainLooper()
        assertFalse(available!!)
        assertTrue(PatchBridgeClient.patchInstalled)
        assertFalse(PatchBridgeClient.bridgeEnabled)
        assertFalse(PatchBridgeClient.isAvailable(context))
    }

    @Test fun forcedProbeRefreshesDisabledStatusEvenAtTheSameClockTime() {
        respond = { """{"apiVersion":1,"ok":true,"enabled":false,"backendState":"Ready"}""" }
        PatchBridgeClient.probe(context, force = true) {}
        ShadowLooper.idleMainLooper()
        PatchBridgeClient::class.java.getDeclaredField("checkedAt").apply { isAccessible = true }
            .setLong(null, System.currentTimeMillis())

        respond = { """{"apiVersion":1,"ok":true,"enabled":true,"backendState":"Ready"}""" }
        var available: Boolean? = null
        PatchBridgeClient.probe(context, force = true) { available = it }
        ShadowLooper.idleMainLooper()
        assertTrue(available!!)
        assertTrue(PatchBridgeClient.patchInstalled)
        assertTrue(PatchBridgeClient.bridgeEnabled)
        assertTrue(PatchBridgeClient.isAvailable(context))
        assertEquals(listOf("status", "status"), requests.map { it.getString("op") })
    }

    @Test fun missingEnabledStatusFieldKeepsOlderPatchCompatible() {
        respond = { """{"apiVersion":1,"ok":true,"backendState":"Ready"}""" }
        var available: Boolean? = null
        PatchBridgeClient.probe(context, force = true) { available = it }
        ShadowLooper.idleMainLooper()
        assertTrue(available!!)
        assertTrue(PatchBridgeClient.patchInstalled)
        assertTrue(PatchBridgeClient.bridgeEnabled)
        assertTrue(PatchBridgeClient.isAvailable(context))
    }

    @Test fun directDiscoveryExplainsKnownDisabledPatch() {
        PatchBridgeClient::class.java.getDeclaredField("available").apply { isAccessible = true }
            .setBoolean(null, false)
        PatchBridgeClient::class.java.getDeclaredField("patchInstalled").apply { isAccessible = true }
            .setBoolean(null, true)
        PatchBridgeClient::class.java.getDeclaredField("bridgeEnabled").apply { isAccessible = true }
            .setBoolean(null, false)
        var result: GroupsResult? = null
        NotificationCenter.discoverChatsDirect("disabled") { result = it }
        assertTrue(result!!.error!!.contains("disabled in OpenBubbles"))
        assertTrue(requests.isEmpty())
    }

    @Test fun backendHistoryIsMarkedAuthoritativeAndSendIsNotRetriedAfterTimeout() {
        respond = { """{"apiVersion":1,"ok":true,"thread":{"id":"iMessage;-;+15551234567",
            "messages":[{"id":"real","text":"History","timestamp":10}]}}""" }
        var payload: ThreadPayload? = null
        NotificationCenter.threadPayload(id) { payload = it }
        ShadowLooper.idleMainLooper()
        assertTrue(payload!!.thread!!.directHistory)
        requests.clear()
        respond = { null }
        var sent: NotificationCenter.SendResult? = null
        NotificationCenter.send("timeout-request", id, "Hello") { sent = it }
        ShadowLooper.idleMainLooper()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertNotNull(sent!!.error)
        assertFalse(sent!!.retryable)
        assertEquals(1, requests.count { it.getString("op") == "send" })
    }

    @Test fun compactJournalReplayStillCreatesACanonicalCachedConversation() {
        respond = { """{"apiVersion":1,"ok":true,"threadId":"iMessage;-;+15551234567",
            "message":{"id":"real","text":"Hello","timestamp":5,"fromMe":true}}""" }
        var result: NotificationCenter.SendResult? = null
        NotificationCenter.send("replay", "new:+15551234567", "Hello", "+15551234567") { result = it }
        ShadowLooper.idleMainLooper()
        assertNull(result!!.error)
        assertEquals("real", NotificationCenter.threadFor(id)!!.messages.single().id)
        assertEquals("+15551234567", NotificationCenter.threadFor(id)!!.address)
    }
}
