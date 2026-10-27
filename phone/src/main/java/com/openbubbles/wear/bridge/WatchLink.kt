package com.openbubbles.wear.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireThread

/**
 * Sends live updates to every reachable watch and mirrors snapshots into the
 * Data Layer so a watch can show cached conversations while the phone is away.
 */
object WatchLink {
    private const val TAG = "OBWearLink"
    private const val PREFS_NAME = "watch_link"
    private const val KEY_URIS = "data_uris"
    private val uriLock = Any()

    fun sendToNode(context: Context, nodeId: String, path: String, bytes: ByteArray) {
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, path, bytes)
            .addOnFailureListener { error ->
                Log.w(TAG, "Message $path to $nodeId failed", error)
            }
    }

    fun broadcast(context: Context, path: String, bytes: ByteArray) {
        Wearable.getCapabilityClient(context)
            .getCapability(Protocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                if (info.nodes.isEmpty()) {
                    sendToAllConnected(context, path, bytes)
                } else {
                    info.nodes.forEach { node ->
                        sendToNode(context, node.id, path, bytes)
                    }
                }
            }
            .addOnFailureListener {
                sendToAllConnected(context, path, bytes)
            }
    }

    private fun sendToAllConnected(context: Context, path: String, bytes: ByteArray) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    sendToNode(context, node.id, path, bytes)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Unable to enumerate connected nodes", error)
            }
    }

    fun putSync(context: Context, payload: SyncPayload) {
        putJson(context, Protocol.DATA_SYNC, Wire.encode(payload), recordUri = false)
    }

    fun putThread(context: Context, thread: WireThread) {
        val transport = Wire.threadForTransport(thread)
        putJson(
            context,
            Protocol.threadDataPath(thread.id),
            Wire.encode(transport),
            recordUri = true
        )
    }

    private fun putJson(context: Context, path: String, json: String, recordUri: Boolean) {
        val request = PutDataMapRequest.create(path)
        request.dataMap.putString(Protocol.DATA_KEY_JSON, json)
        request.setUrgent()
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest())
            .addOnSuccessListener { item ->
                if (recordUri) recordUri(context, path, item.uri.toString())
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Data item $path failed", error)
            }
    }

    fun publishAll(context: Context, threads: List<WireThread>, sync: SyncPayload) {
        putSync(context, sync)
        threads.forEach { putThread(context, it) }
        pruneThreads(context, threads.map { it.id }.toSet())
    }

    /** Deletes leftovers for conversations that have fallen out of the local cache. */
    fun pruneThreads(context: Context, keepThreadIds: Set<String>) {
        val keepPaths = keepThreadIds.map { Protocol.threadDataPath(it) }.toSet()
        val uris = readUris(context)
        val stale = uris.keys.filter { path ->
            path.startsWith(Protocol.DATA_THREAD_PREFIX) && path !in keepPaths
        }
        if (stale.isEmpty()) return
        stale.forEach { path ->
            val uri = uris.remove(path)
            if (uri != null) {
                Wearable.getDataClient(context)
                    .deleteDataItems(Uri.parse(uri))
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Unable to delete stale data item $path", error)
                    }
            }
        }
        writeUris(context, uris)
    }

    private fun recordUri(context: Context, path: String, uri: String) {
        synchronized(uriLock) {
            val uris = readUris(context)
            uris[path] = uri
            writeUris(context, uris)
        }
    }

    private fun readUris(context: Context): MutableMap<String, String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URIS, null)
            ?: return mutableMapOf()
        return Wire.decode<Map<String, String>>(raw)?.toMutableMap() ?: mutableMapOf()
    }

    private fun writeUris(context: Context, uris: Map<String, String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URIS, Wire.encode(uris))
            .apply()
    }
}
