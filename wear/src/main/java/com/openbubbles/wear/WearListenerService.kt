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
                    Wire.decodeBytes<SyncPayload>(data)?.let {
                        Log.i(TAG, "Synced " + it.conversations.size + " conversations; protocol " + it.version)
                        WatchRepository.onSync(it)
                    }
                }

                Protocol.PATH_THREAD -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.ThreadPayload>(data)
                        ?.let { WatchRepository.onThreadPayload(it) }
                }

                Protocol.PATH_UPDATE -> {
                    val update = Wire.decodeBytes<com.openbubbles.wear.protocol.UpdatePayload>(data)
                    if (update != null) WatchRepository.onThread(
                        update.thread,
                        alertMessageId = update.alertMessageId,
                        hideAlertPreview = update.hideAlertPreview
                    )
                }

                Protocol.PATH_TEST_NOTIFICATION -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.UpdatePayload>(data)
                        ?.let { WatchRepository.onTestNotification(it) }
                }

                Protocol.PATH_PONG -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.PongPayload>(data)
                        ?.let { WatchRepository.onPong(it) }
                }

                Protocol.PATH_REPLY_RESULT, Protocol.PATH_ACTION_RESULT -> {
                    val result = Wire.decodeBytes<com.openbubbles.wear.protocol.ActionResult>(data)
                    if (result != null) {
                        WatchRepository.onActionResult(result)
                    } else {
                        Wire.decodeBytes<com.openbubbles.wear.protocol.BridgeError>(data)
                            ?.let { WatchRepository.onBridgeError(it) }
                    }
                }

                Protocol.PATH_MEDIA_RESULT -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.MediaPayload>(data)
                        ?.let { WatchRepository.onMediaResult(it) }
                }

                Protocol.PATH_CONTACTS_RESULT -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.ContactsPage>(data)
                        ?.let { WatchRepository.onContactsPage(it) }
                }

                Protocol.PATH_GROUPS_RESULT -> {
                    Wire.decodeBytes<com.openbubbles.wear.protocol.GroupsResult>(data)
                        ?.let { WatchRepository.onGroups(it) }
                }

                Protocol.PATH_PING, Protocol.PATH_SYNC_REQUEST,
                Protocol.PATH_THREAD_REQUEST, Protocol.PATH_REPLY,
                Protocol.PATH_MARK_READ, Protocol.PATH_OPEN_PHONE,
                Protocol.PATH_SEND,
                Protocol.PATH_MEDIA_REQUEST -> Unit

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
                val dataMap = runCatching {
                    DataMapItem.fromDataItem(item).dataMap
                }.getOrNull() ?: continue
                val mediaId = Protocol.mediaIdFromDataPath(path)
                if (mediaId != null) {
                    if (dataMap.containsKey(Protocol.DATA_KEY_ASSET)) {
                        WatchRepository.onMediaDataItem(applicationContext, mediaId, dataMap)
                    }
                    continue
                }
                val json = dataMap.getString(Protocol.DATA_KEY_JSON) ?: continue
                if (path.startsWith(Protocol.DATA_DISMISS_PREFIX)) {
                    Wire.decode<com.openbubbles.wear.protocol.NotificationDismissal>(json)
                        ?.takeIf { it.isValid() && path == Protocol.notificationDismissalDataPath(it.threadId) }
                        ?.let { WatchNotifications.dismiss(applicationContext, it) }
                    continue
                }
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
