package com.openbubbles.wear

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireThread

/**
 * Receives live messages and durable Data Layer snapshots from the phone bridge.
 * The phone remains the source of truth; everything arriving here is either a
 * snapshot or the result of an action the user requested on the watch.
 */
class WearListenerService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        WatchRepository.attach(applicationContext)
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        WatchRepository.attach(applicationContext)
        WatchRepository.onPeerConnected()
        WatchRepository.sync(applicationContext, manual = false)
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        WatchRepository.onPeerDisconnected()
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        super.onCapabilityChanged(capabilityInfo)
        if (capabilityInfo.name != Protocol.CAPABILITY_PHONE) return
        WatchRepository.attach(applicationContext)
        WatchRepository.sync(applicationContext, manual = false)
    }

    override fun onMessageReceived(event: MessageEvent) {
        WatchRepository.attach(applicationContext)
        val path = event.path ?: return
        val data = event.data ?: return
        try {
            when (path) {
                Protocol.PATH_SYNC -> {
                    Wire.decodeBytes<SyncPayload>(data)?.let { WatchRepository.onSync(it) }
                }

                Protocol.PATH_THREAD -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.ThreadPayload>(data)
                        ?.let { WatchRepository.onThreadPayload(it) }
                }

                Protocol.PATH_UPDATE -> {
                    val update = Wire.decodeBytes<com.openbubbles.wear.protocol.UpdatePayload>(data)
                    if (update != null) WatchRepository.onThread(update.thread)
                }

                Protocol.PATH_PONG -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.PongPayload>(data)
                        ?.let { WatchRepository.onPong(it) }
                }

                Protocol.PATH_REPLY_RESULT, Protocol.PATH_ACTION_RESULT -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.ActionResult>(data)
                        ?.let { WatchRepository.onActionResult(it) }
                }

                Protocol.PATH_HISTORY_RESULT -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.HistoryPayload>(data)
                        ?.let { WatchRepository.onHistory(it) }
                }

                Protocol.PATH_PING, Protocol.PATH_SYNC_REQUEST,
                Protocol.PATH_THREAD_REQUEST, Protocol.PATH_REPLY,
                Protocol.PATH_MARK_READ, Protocol.PATH_OPEN_PHONE,
                Protocol.PATH_SEND, Protocol.PATH_HISTORY -> Unit

                else -> Log.d(TAG, "Ignoring unknown path $path")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to handle $path", t)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        WatchRepository.attach(applicationContext)
        try {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem ?: continue
                val path = item.uri.path ?: continue
                val json = runCatching {
                    DataMapItem.fromDataItem(item)
                        .dataMap
                        .getString(Protocol.DATA_KEY_JSON)
                }.getOrNull() ?: continue
                when {
                    path == Protocol.DATA_SYNC -> {
                        Wire.decode<SyncPayload>(json)?.let {
                            // Cached snapshots never prove that the phone is online.
                            WatchRepository.onSync(it, fromCache = true)
                        }
                    }

                    path.startsWith(Protocol.DATA_THREAD_PREFIX) -> {
                        Wire.decode<WireThread>(json)?.let {
                            WatchRepository.onThread(it, connected = false)
                        }
                    }
                }
            }
        } finally {
            events.release()
        }
    }

    private companion object {
        const val TAG = "OBWearListener"
    }
}
