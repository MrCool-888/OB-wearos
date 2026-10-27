package com.openbubbles.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.openbubbles.wear.protocol.Protocol
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watch-side data layer client. Requests are sent to whichever phone advertises
 * the bridge capability; if capability discovery has not caught up yet we fall
 * back to the connected nodes.
 */
object PhoneLink {
    private const val TAG = "OBWearPhoneLink"

    fun send(
        context: Context,
        path: String,
        bytes: ByteArray,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        reachableNodes(context) { nodes ->
            if (nodes.isEmpty()) {
                onResult?.invoke(false)
                return@reachableNodes
            }
            val remaining = AtomicInteger(nodes.size)
            val anySent = AtomicBoolean(false)
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, path, bytes)
                    .addOnSuccessListener {
                        anySent.set(true)
                        if (remaining.decrementAndGet() == 0) onResult?.invoke(true)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "sendMessage $path to ${node.id} failed", error)
                        if (remaining.decrementAndGet() == 0) onResult?.invoke(anySent.get())
                    }
            }
        }
    }

    fun checkReachable(context: Context, callback: (Boolean) -> Unit) {
        Wearable.getCapabilityClient(context)
            .getCapability(Protocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                if (info.nodes.isNotEmpty()) {
                    callback(true)
                } else {
                    Wearable.getNodeClient(context).connectedNodes
                        .addOnSuccessListener { nodes -> callback(nodes.isNotEmpty()) }
                        .addOnFailureListener { callback(false) }
                }
            }
            .addOnFailureListener {
                Wearable.getNodeClient(context).connectedNodes
                    .addOnSuccessListener { nodes -> callback(nodes.isNotEmpty()) }
                    .addOnFailureListener { callback(false) }
            }
    }

    private fun reachableNodes(context: Context, callback: (List<Node>) -> Unit) {
        Wearable.getCapabilityClient(context)
            .getCapability(Protocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                if (info.nodes.isNotEmpty()) {
                    callback(info.nodes.toList())
                } else {
                    allConnectedNodes(context, callback)
                }
            }
            .addOnFailureListener {
                allConnectedNodes(context, callback)
            }
    }

    private fun allConnectedNodes(context: Context, callback: (List<Node>) -> Unit) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes -> callback(nodes) }
            .addOnFailureListener {
                callback(emptyList())
            }
    }

    @Suppress("unused")
    fun awaitReachable(context: Context): Boolean = runCatching {
        Tasks.await(
            Wearable.getCapabilityClient(context)
                .getCapability(Protocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
            2,
            TimeUnit.SECONDS
        ).nodes.isNotEmpty()
    }.getOrDefault(false)
}
