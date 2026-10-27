package com.openbubbles.wear.bridge

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import com.openbubbles.wear.protocol.ActionResult
import com.openbubbles.wear.protocol.BridgeError
import com.openbubbles.wear.protocol.HistoryPayload
import com.openbubbles.wear.protocol.HistoryRequest
import com.openbubbles.wear.protocol.MarkReadRequest
import com.openbubbles.wear.protocol.OpenPhoneRequest
import com.openbubbles.wear.protocol.PingPayload
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.ReplyRequest
import com.openbubbles.wear.protocol.SendRequest
import com.openbubbles.wear.protocol.SyncRequest
import com.openbubbles.wear.protocol.ThreadRequest
import com.openbubbles.wear.protocol.Wire

class WearBridgeService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        NotificationCenter.init(applicationContext)
        NotificationCenter.ensureListenerBound()
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        NotificationCenter.init(applicationContext)
        NotificationCenter.sendSyncTo(peer.id)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        super.onCapabilityChanged(capabilityInfo)
        if (capabilityInfo.name != Protocol.CAPABILITY_WATCH) return
        NotificationCenter.init(applicationContext)
        capabilityInfo.nodes.forEach { NotificationCenter.sendSyncTo(it.id) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val nodeId = event.sourceNodeId ?: return
        val path = event.path ?: return
        val data = event.data ?: ByteArray(0)
        NotificationCenter.init(applicationContext)
        try {
            when (path) {
                Protocol.PATH_PING -> handlePing(nodeId, data)
                Protocol.PATH_SYNC_REQUEST -> handleSyncRequest(nodeId, data)
                Protocol.PATH_THREAD_REQUEST -> handleThreadRequest(nodeId, data)
                Protocol.PATH_REPLY -> handleReply(nodeId, data)
                Protocol.PATH_MARK_READ -> handleMarkRead(nodeId, data)
                Protocol.PATH_OPEN_PHONE -> handleOpenPhone(nodeId, data)
                Protocol.PATH_SEND -> handleSend(nodeId, data)
                Protocol.PATH_HISTORY -> handleHistory(nodeId, data)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to handle $path", t)
            val json = data.toString(Charsets.UTF_8)
            val requestId = runCatching { Wire.decode<ReplyRequest>(json)?.requestId }.getOrNull()
                ?: runCatching { Wire.decode<SendRequest>(json)?.requestId }.getOrNull()
                ?: runCatching { Wire.decode<HistoryRequest>(json)?.requestId }.getOrNull()
            WatchLink.sendToNode(
                applicationContext,
                nodeId,
                Protocol.PATH_ACTION_RESULT,
                Wire.encodeBytes(
                    BridgeError(requestId = requestId, error = t.message ?: "Bridge error")
                )
            )
        }
    }

    private fun handlePing(nodeId: String, data: ByteArray) {
        val ping = Wire.decodeBytes<PingPayload>(data)
        if (!ping?.watchName.isNullOrBlank()) {
            BridgePreferences(applicationContext).lastSeenWatchName = ping.watchName
        }
        WatchLink.sendToNode(
            applicationContext,
            nodeId,
            Protocol.PATH_PONG,
            Wire.encodeBytes(NotificationCenter.pong())
        )
    }

    private fun handleSyncRequest(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<SyncRequest>(data)
        if (!request?.watchName.isNullOrBlank()) {
            BridgePreferences(applicationContext).lastSeenWatchName = request.watchName
        }
        NotificationCenter.sendSyncTo(nodeId)
    }

    private fun handleThreadRequest(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<ThreadRequest>(data) ?: return
        WatchLink.sendToNode(
            applicationContext,
            nodeId,
            Protocol.PATH_THREAD,
            Wire.encodeBytes(NotificationCenter.threadPayload(request.threadId))
        )
    }

    private fun handleReply(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<ReplyRequest>(data) ?: return
        val error = NotificationCenter.reply(request.threadId, request.text)
        WatchLink.sendToNode(
            applicationContext,
            nodeId,
            Protocol.PATH_REPLY_RESULT,
            Wire.encodeBytes(
                ActionResult(
                    requestId = request.requestId,
                    action = Protocol.ACTION_REPLY,
                    ok = error == null,
                    threadId = request.threadId,
                    error = error
                )
            )
        )
    }

    private fun handleMarkRead(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<MarkReadRequest>(data) ?: return
        val error = NotificationCenter.markRead(request.threadId)
        WatchLink.sendToNode(
            applicationContext,
            nodeId,
            Protocol.PATH_ACTION_RESULT,
            Wire.encodeBytes(
                ActionResult(
                    requestId = request.requestId,
                    action = Protocol.ACTION_MARK_READ,
                    ok = error == null,
                    threadId = request.threadId,
                    error = error
                )
            )
        )
    }

    private fun handleSend(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<SendRequest>(data) ?: return
        NotificationCenter.send(
            requestId = request.requestId,
            threadId = request.threadId,
            address = request.address,
            title = request.title,
            rawText = request.text
        ) { result ->
            WatchLink.sendToNode(
                applicationContext,
                nodeId,
                Protocol.PATH_ACTION_RESULT,
                Wire.encodeBytes(
                    ActionResult(
                        requestId = request.requestId,
                        action = Protocol.ACTION_SEND,
                        ok = result.error == null,
                        threadId = result.threadId ?: request.threadId,
                        error = result.error,
                        message = result.message
                    )
                )
            )
        }
    }

    private fun handleHistory(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<HistoryRequest>(data) ?: return
        NotificationCenter.history(
            threadId = request.threadId,
            title = request.title,
            address = request.address,
            limit = request.limit
        ) { result ->
            WatchLink.sendToNode(
                applicationContext,
                nodeId,
                Protocol.PATH_HISTORY_RESULT,
                Wire.encodeBytes(
                    HistoryPayload(
                        requestId = request.requestId,
                        threadId = result.threadId,
                        messages = result.messages,
                        hasMore = result.hasMore,
                        error = result.error
                    )
                )
            )
        }
    }

    private fun handleOpenPhone(nodeId: String, data: ByteArray) {
        val request = Wire.decodeBytes<OpenPhoneRequest>(data) ?: return
        val error = NotificationCenter.openPhone(request.threadId)
        WatchLink.sendToNode(
            applicationContext,
            nodeId,
            Protocol.PATH_ACTION_RESULT,
            Wire.encodeBytes(
                ActionResult(
                    requestId = request.requestId,
                    action = Protocol.ACTION_OPEN_PHONE,
                    ok = error == null,
                    threadId = request.threadId,
                    error = error
                )
            )
        )
    }

    private companion object {
        const val TAG = "OBWearBridgeService"
    }
}
