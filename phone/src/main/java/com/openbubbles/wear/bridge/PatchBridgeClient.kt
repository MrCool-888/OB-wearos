package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the certificate-authenticated IPC service injected into OpenBubbles
 * by the Wear patch. The patch is optional; notification actions remain the
 * fallback when absent, but an uncertain direct send must never be replayed.
 *
 * Each request owns a short binding so a dead or reinstalled OpenBubbles cannot
 * leave a stale binder behind.
 */
object PatchBridgeClient {
    private const val TAG = "OBWearPatchClient"
    private const val PACKAGE = "com.openbubbles.messaging"
    private const val SERVICE = "com.openbubbles.wear.patch.CompanionService"
    private const val ACTION = "com.openbubbles.wear.PATCH_BRIDGE"
    private const val EXTRA_JSON = "json"
    private const val MSG_REQUEST = 1
    private const val MSG_REPLY = 2
    private const val API_VERSION = 1
    private const val CACHE_MS = 60_000L
    private const val PROBE_INTERVAL_MS = 15_000L

    data class Reply(
        val ok: Boolean,
        val json: JSONObject?,
        val error: String?,
        val timedOut: Boolean = false
    )

    @Volatile private var available = false
    @Volatile private var checkedAt = 0L
    @Volatile var backendState: String = "Not connected"
        private set
    internal var onAvailabilityChanged: (() -> Unit)? = null

    /** Cached status; triggers a throttled probe when the answer is stale. */
    fun isAvailable(context: Context): Boolean {
        if (!BridgePreferences(context).directBridgeEnabled) {
            available = false
            return false
        }
        val interval = if (backendState == "Ready") CACHE_MS else PROBE_INTERVAL_MS
        if (System.currentTimeMillis() - checkedAt > interval) probe(context)
        return available
    }

    fun probe(context: Context, callback: ((Boolean) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now - checkedAt < PROBE_INTERVAL_MS) {
            callback?.invoke(available)
            return
        }
        checkedAt = now
        request(context, "status", JSONObject(), 6_000) { reply ->
            val found = reply.ok
            val previousState = backendState
            backendState = reply.json?.optString("backendState")?.takeIf { it.isNotBlank() }
                ?: if (found) "Connected" else "Not connected"
            val changed = available != found
            updateAvailability(found)
            if (!changed && previousState != backendState) onAvailabilityChanged?.invoke()
            checkedAt = System.currentTimeMillis()
            if (reply.error != null) {
                Log.d(TAG, "Wear patch unavailable: ${reply.error}")
            }
            callback?.invoke(found)
        }
    }

    fun request(
        context: Context,
        op: String,
        fields: JSONObject = JSONObject(),
        timeoutMs: Long = 8_000,
        callback: (Reply) -> Unit
    ) {
        val body = JSONObject()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, fields.get(key))
        }
        body.put("apiVersion", API_VERSION)
        body.put("op", op)

        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        var connection: ServiceConnection? = null

        fun finish(reply: Reply) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            if (bound.getAndSet(false)) {
                runCatching { app.unbindService(connection!!) }
                    .onFailure { Log.d(TAG, "Unable to unbind from the patch", it) }
            }
            if (reply.json == null) updateAvailability(false)
            callback(reply)
        }

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == MSG_REPLY) {
                val parsed = runCatching {
                    message.data?.getString(EXTRA_JSON)?.let(::JSONObject)
                }.getOrNull()
                val compatible = parsed?.optInt("apiVersion") == API_VERSION
                finish(
                    Reply(
                        ok = compatible && parsed?.optBoolean("ok") == true,
                        json = parsed.takeIf { compatible },
                        error = when {
                            !compatible -> "OpenBubbles returned an invalid or unsupported Wear bridge response."
                            parsed?.optBoolean("ok") == true -> null
                            else -> parsed?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "OpenBubbles could not complete the request."
                        }
                    )
                )
            }
            true
        })

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (finished.get()) return
                if (binder == null) {
                    finish(Reply(false, null, "OpenBubbles returned no bridge."))
                    return
                }
                try {
                    val message = Message.obtain(null, MSG_REQUEST)
                    message.replyTo = replyMessenger
                    message.data = Bundle().apply { putString(EXTRA_JSON, body.toString()) }
                    Messenger(binder).send(message)
                } catch (t: Throwable) {
                    finish(Reply(false, null, t.message ?: "The patch request failed."))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(Reply(false, null, "OpenBubbles closed the bridge."))
            }

            override fun onBindingDied(name: ComponentName?) {
                finish(Reply(false, null, "OpenBubbles restarted the bridge."))
            }

            override fun onNullBinding(name: ComponentName?) {
                finish(Reply(false, null, "OpenBubbles rejected the bridge."))
            }
        }

        // Bind and finish on one looper, including calls originating from Wear's worker thread.
        handler.post {
            val visible = runCatching {
                app.bindService(
                    Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.onFailure { Log.d(TAG, "Unable to bind to the patch", it) }.getOrDefault(false)
            if (!visible) {
                updateAvailability(false)
                finish(
                    Reply(
                        false,
                        null,
                        "The OpenBubbles Wear patch is not installed. Install the patch, then open OpenBubbles once."
                    )
                )
                return@post
            }
            bound.set(true)
            handler.postDelayed(
                {
                    finish(
                        Reply(
                            false,
                            null,
                            "OpenBubbles did not answer. Open it on the phone once, then try again.",
                            timedOut = true
                        )
                    )
                },
                timeoutMs
            )
        }
    }

    private fun updateAvailability(value: Boolean) {
        val changed = available != value
        available = value
        if (changed) onAvailabilityChanged?.invoke()
    }
}
