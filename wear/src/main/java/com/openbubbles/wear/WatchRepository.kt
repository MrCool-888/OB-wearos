package com.openbubbles.wear

import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.openbubbles.wear.protocol.ActionResult
import com.openbubbles.wear.protocol.BridgeError
import com.openbubbles.wear.protocol.MarkReadRequest
import com.openbubbles.wear.protocol.OpenPhoneRequest
import com.openbubbles.wear.protocol.PingPayload
import com.openbubbles.wear.protocol.PongPayload
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.ReplyRequest
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.SyncRequest
import com.openbubbles.wear.protocol.ThreadPayload
import com.openbubbles.wear.protocol.ThreadRequest
import com.openbubbles.wear.protocol.ThreadSummary
import com.openbubbles.wear.protocol.UpdatePayload
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchState(
    val loading: Boolean = true,
    val conversations: List<ThreadSummary> = emptyList(),
    val threads: Map<String, WireThread> = emptyMap(),
    val phoneName: String = "",
    val bridgeVersion: String = "",
    val notificationAccess: Boolean = false,
    val relayEnabled: Boolean = true,
    val connected: Boolean = false,
    val reachable: Boolean = false,
    val lastSyncAt: Long = 0L,
    val phoneGeneratedAt: Long = 0L,
    val quickReplies: List<String> = Protocol.DEFAULT_QUICK_REPLIES,
    val syncing: Boolean = false,
    val requestingThreads: Set<String> = emptySet(),
    val error: String? = null,
    val info: String? = null
) {
    val phoneOnline: Boolean get() = connected || reachable
}

object WatchRepository {
    private const val PREFS_NAME = "openbubbles_wear"
    private const val KEY_THREADS = "threads"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val KEY_QUICK_REPLIES = "quick_replies"
    private const val KEY_PHONE_NAME = "phone_name"
    private const val KEY_BRIDGE_VERSION = "bridge_version"
    private const val KEY_NOTIFICATION_ACCESS = "notification_access"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_PHONE_GENERATED = "phone_generated"
    private const val KEY_READ_AT = "read_at:"
    private const val LOCAL_PREFIX = "local-"
    private const val MAX_MESSAGES = 80
    private const val MAX_THREADS = 30
    private const val LOCAL_MATCH_WINDOW_MS = 5 * 60_000L
    private const val MAX_REPLY_LENGTH = 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var initialized = false
    private var lastSyncRequestAt = 0L
    private var dataLayerRestoreStarted = false
    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun attach(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threads = prefs.getString(KEY_THREADS, null)
            ?.let { Wire.decode<List<WireThread>>(it) }
            ?.associateBy { it.id }
            ?: emptyMap()
        val conversations = prefs.getString(KEY_CONVERSATIONS, null)
            ?.let { Wire.decode<List<ThreadSummary>>(it) }
            ?: threads.values.map { it.toSummary() }.sortedByDescending { it.lastTimestamp }
        val quickReplies = prefs.getString(KEY_QUICK_REPLIES, null)
            ?.let { Wire.decode<List<String>>(it) }
            ?: Protocol.DEFAULT_QUICK_REPLIES
        _state.value = WatchState(
            loading = false,
            conversations = conversations,
            threads = threads,
            phoneName = prefs.getString(KEY_PHONE_NAME, "").orEmpty(),
            bridgeVersion = prefs.getString(KEY_BRIDGE_VERSION, "").orEmpty(),
            notificationAccess = prefs.getBoolean(KEY_NOTIFICATION_ACCESS, false),
            relayEnabled = prefs.getBoolean(KEY_RELAY_ENABLED, true),
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
            phoneGeneratedAt = prefs.getLong(KEY_PHONE_GENERATED, 0L),
            quickReplies = quickReplies
        )
        initialized = true
        if (threads.isEmpty() || conversations.isEmpty()) {
            restoreFromDataLayer(app, prefs)
        }
        PhoneLink.checkReachable(app) { reachable ->
            _state.update { it.copy(reachable = reachable) }
        }
        ping(app)
    }

    fun onAppResumed() {
        if (!initialized) return
        // Probe the phone and refresh if this app has been away for a while.
        val context = appContext ?: return
        PhoneLink.checkReachable(context) { reachable ->
            _state.update { it.copy(reachable = reachable) }
        }
        ping(context)
        if (System.currentTimeMillis() - _state.value.lastSyncAt > 15_000L) {
            sync(context, manual = false)
        }
    }

    fun onSync(payload: SyncPayload, fromCache: Boolean = false) {
        if (!initialized) return
        if (payload.generatedAt > 0L && payload.generatedAt < _state.value.phoneGeneratedAt) return
        applySync(payload, connected = !fromCache)
    }

    private fun applySync(payload: SyncPayload, connected: Boolean) {
        val summaries = payload.conversations.sortedByDescending { it.lastTimestamp }
        val byId = summaries.associateBy { it.id }
        val existingThreads = _state.value.threads.mapValues { (id, thread) ->
            val summary = byId[id] ?: return@mapValues thread
            val readAt = readAt(id)
            thread.copy(
                title = summary.title,
                isGroup = summary.isGroup,
                unread = summary.unread && summary.lastTimestamp > readAt,
                notificationActive = summary.notificationActive,
                canReply = summary.canReply,
                canMarkRead = summary.canMarkRead,
                participants = summary.participants
            )
        }
        _state.update {
            it.copy(
                loading = false,
                conversations = summaries,
                threads = existingThreads,
                phoneName = payload.phoneName,
                bridgeVersion = payload.bridgeVersion,
                notificationAccess = payload.notificationAccess,
                relayEnabled = payload.relayEnabled,
                connected = connected || it.connected,
                reachable = connected || it.reachable,
                lastSyncAt = if (connected) System.currentTimeMillis() else payload.generatedAt,
                phoneGeneratedAt = maxOf(it.phoneGeneratedAt, payload.generatedAt),
                syncing = false,
                error = null
            )
        }
        persist()
    }

    fun onThreadPayload(payload: ThreadPayload) {
        if (!initialized) return
        val thread = payload.thread
        if (thread == null) {
            _state.update {
                it.copy(
                    requestingThreads = emptySet(),
                    error = payload.error ?: "Unable to load conversation."
                )
            }
            return
        }
        onThread(thread, connected = true)
    }

    fun onThread(thread: WireThread, connected: Boolean = true) {
        if (!initialized) return
        if (!connected) {
            val old = _state.value.threads[thread.id]
            if (old != null && thread.updatedAt < old.updatedAt) return
        }
        val merged = mergeThread(thread)
        val threads = _state.value.threads + (merged.id to merged)
        val conversations = (_state.value.conversations.filterNot { it.id == merged.id } + merged.toSummary())
            .sortedByDescending { it.lastTimestamp }
        _state.update {
            it.copy(
                loading = false,
                threads = capThreads(threads),
                conversations = conversations,
                connected = connected || it.connected,
                reachable = connected || it.reachable,
                lastSyncAt = if (connected) System.currentTimeMillis() else it.lastSyncAt,
                requestingThreads = it.requestingThreads - merged.id,
                syncing = if (connected) false else it.syncing,
                error = null
            )
        }
        persist()
    }

    fun onActionResult(result: ActionResult) {
        if (!initialized) return
        val preview = result.threadId
        when (result.action) {
            Protocol.ACTION_REPLY -> {
                val localId = LOCAL_PREFIX + result.requestId
                updateLocalMessage(
                    threadId = preview,
                    messageId = localId,
                    status = if (result.ok) "sent" else "failed"
                )
                if (!result.ok) {
                    _state.update {
                        it.copy(error = result.error ?: "The reply was not sent.")
                    }
                } else {
                    markReadLocally(preview)
                }
            }

            Protocol.ACTION_MARK_READ -> {
                if (result.ok) {
                    markReadLocally(preview)
                    _state.update { it.copy(info = "Marked as read") }
                } else {
                    _state.update {
                        it.copy(error = result.error ?: "Could not mark the chat as read.")
                    }
                }
            }

            Protocol.ACTION_OPEN_PHONE -> {
                if (result.ok) {
                    _state.update { it.copy(info = "Opened on phone") }
                } else {
                    _state.update {
                        it.copy(error = result.error ?: "Could not open the chat on the phone.")
                    }
                }
            }

            else -> if (!result.ok) {
                _state.update { it.copy(error = result.error ?: "Phone action failed.") }
            }
        }
        persist()
    }

    fun onPong(pong: PongPayload) {
        if (!initialized) return
        _state.update {
            it.copy(
                connected = true,
                reachable = true,
                phoneName = pong.phoneName,
                bridgeVersion = pong.bridgeVersion,
                notificationAccess = pong.notificationAccess,
                relayEnabled = pong.relayEnabled,
                quickReplies = pong.quickReplies.ifEmpty { Protocol.DEFAULT_QUICK_REPLIES }
            )
        }
        persist()
    }

    fun onBridgeError(error: BridgeError) {
        _state.update { it.copy(error = error.error) }
    }

    fun onPeerConnected() {
        if (!initialized) return
        _state.update { it.copy(reachable = true) }
    }

    fun onPeerDisconnected() {
        if (!initialized) return
        _state.update { it.copy(connected = false, reachable = false) }
    }

    fun ping(context: Context) {
        if (!initialized) return
        PhoneLink.send(
            context,
            Protocol.PATH_PING,
            Wire.encodeBytes(PingPayload(watchName = watchName()))
        )
    }

    fun sync(context: Context, manual: Boolean = true) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        if (now - lastSyncRequestAt < 3_000L) return
        lastSyncRequestAt = now
        _state.update { it.copy(syncing = true, error = null, info = null) }
        ping(context)
        val request = SyncRequest(watchName = watchName())
        PhoneLink.send(context, Protocol.PATH_SYNC_REQUEST, Wire.encodeBytes(request)) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        syncing = false,
                        connected = false,
                        reachable = false,
                        error = if (manual) {
                            "Phone offline. Open the bridge app, then sync again."
                        } else {
                            it.error
                        }
                    )
                }
            }
        }
        scope.launch {
            delay(12_000L)
            if (_state.value.lastSyncAt < now && _state.value.syncing) {
                _state.update {
                    it.copy(
                        syncing = false,
                        error = if (manual) {
                            "No response from the phone. Make sure the bridge app is installed and open."
                        } else {
                            it.error
                        }
                    )
                }
            }
        }
    }

    fun requestThread(threadId: String) {
        if (!initialized) return
        if (_state.value.requestingThreads.contains(threadId)) return
        val context = appContext ?: return
        _state.update {
            it.copy(requestingThreads = it.requestingThreads + threadId, error = null)
        }
        PhoneLink.send(
            context,
            Protocol.PATH_THREAD_REQUEST,
            Wire.encodeBytes(ThreadRequest(threadId = threadId))
        ) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        requestingThreads = it.requestingThreads - threadId,
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. The cached conversation is shown if available."
                    )
                }
            }
        }
        scope.launch {
            delay(12_000L)
            if (_state.value.threads[threadId] == null) {
                _state.update {
                    it.copy(
                        requestingThreads = it.requestingThreads - threadId,
                        error = "Could not load this conversation from the phone."
                    )
                }
            } else {
                _state.update { it.copy(requestingThreads = it.requestingThreads - threadId) }
            }
        }
    }

    fun sendReply(threadId: String, rawText: String) {
        if (!initialized) return
        val text = rawText.trim().take(MAX_REPLY_LENGTH)
        if (text.isEmpty()) return
        val context = appContext ?: return
        if (!_state.value.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return
        }
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val local = WireMessage(
            id = LOCAL_PREFIX + requestId,
            threadId = threadId,
            text = text,
            timestamp = now,
            fromMe = true,
            status = "sending"
        )
        val current = _state.value.threads[threadId]
        if (current != null) {
            _state.update { it.copy(error = null, info = null) }
            val updated = current.copy(
                messages = (current.messages + local).sortedBy { it.timestamp },
                unread = false
            )
            replaceThread(updated)
        }
        markReadAt(threadId, now)
        persist()
        PhoneLink.send(
            context,
            Protocol.PATH_REPLY,
            Wire.encodeBytes(ReplyRequest(requestId = requestId, threadId = threadId, text = text))
        ) { sent ->
            if (!sent) {
                updateLocalMessage(threadId, local.id, "failed")
                _state.update {
                    it.copy(
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. The reply was not sent."
                    )
                }
            }
        }
        scope.launch {
            delay(15_000L)
            val thread = _state.value.threads[threadId] ?: return@launch
            val message = thread.messages.firstOrNull { it.id == local.id }
            if (message?.status == "sending") {
                updateLocalMessage(threadId, local.id, "failed")
                _state.update {
                    it.copy(error = "The phone did not confirm the reply.")
                }
            }
        }
    }

    fun markRead(threadId: String) {
        if (!initialized) return
        val context = appContext ?: return
        if (!_state.value.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return
        }
        val request = MarkReadRequest(requestId = UUID.randomUUID().toString(), threadId = threadId)
        PhoneLink.send(context, Protocol.PATH_MARK_READ, Wire.encodeBytes(request)) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. Could not mark the chat as read."
                    )
                }
            }
        }
    }

    fun openOnPhone(threadId: String?) {
        if (!initialized) return
        val context = appContext ?: return
        val request = OpenPhoneRequest(requestId = UUID.randomUUID().toString(), threadId = threadId)
        PhoneLink.send(context, Protocol.PATH_OPEN_PHONE, Wire.encodeBytes(request)) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. Could not open the chat."
                    )
                }
            }
        }
    }

    fun clearNotices() {
        _state.update { it.copy(error = null, info = null) }
    }

    fun markReadLocally(threadId: String?) {
        if (threadId == null) return
        val now = System.currentTimeMillis()
        markReadAt(threadId, now)
        val thread = _state.value.threads[threadId] ?: return
        replaceThread(thread.copy(unread = false))
        persist()
    }

    private fun updateLocalMessage(threadId: String?, messageId: String, status: String) {
        if (threadId == null) return
        val thread = _state.value.threads[threadId] ?: return
        val messages = thread.messages.map { message ->
            if (message.id == messageId) message.copy(status = status) else message
        }
        replaceThread(thread.copy(messages = messages))
        persist()
    }

    private fun replaceThread(thread: WireThread) {
        val threads = _state.value.threads + (thread.id to thread)
        val conversations = (_state.value.conversations.filterNot { it.id == thread.id } + thread.toSummary())
            .sortedByDescending { it.lastTimestamp }
        _state.update {
            it.copy(
                threads = capThreads(threads),
                conversations = conversations
            )
        }
    }

    private fun mergeThread(incoming: WireThread): WireThread {
        val old = _state.value.threads[incoming.id]
        val remote = LinkedHashMap<String, WireMessage>()
        old?.messages.orEmpty()
            .filterNot { it.id.startsWith(LOCAL_PREFIX) }
            .forEach { remote[it.id] = it }
        incoming.messages.forEach { message ->
            val previous = remote[message.id]
            remote[message.id] = if (previous?.attachment == true && !message.attachment) {
                message.copy(attachment = true)
            } else {
                message
            }
        }
        val mergedRemote = remote.values.sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)

        val locals = old?.messages.orEmpty()
            .filter { it.id.startsWith(LOCAL_PREFIX) }
            .toMutableList()
        val unmatched = mergedRemote.filter { it.fromMe }.toMutableList()
        val keptLocals = mutableListOf<WireMessage>()
        locals.forEach { local ->
            val match = unmatched.firstOrNull { message ->
                message.text == local.text &&
                    abs(message.timestamp - local.timestamp) <= LOCAL_MATCH_WINDOW_MS
            }
            if (match != null) {
                unmatched.remove(match)
            } else {
                keptLocals += local
            }
        }

        val allMessages = (mergedRemote + keptLocals)
            .sortedBy { it.timestamp }
            .takeLast(MAX_MESSAGES)
        val readAt = readAt(incoming.id)
        val unread = incoming.unread && incoming.lastActivity() > readAt
        return incoming.copy(messages = allMessages, unread = unread)
    }

    private fun capThreads(threads: Map<String, WireThread>): Map<String, WireThread> =
        threads.values
            .sortedByDescending { it.lastActivity() }
            .take(MAX_THREADS)
            .associateBy { it.id }

    private fun readAt(threadId: String): Long {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return 0L
        return prefs.getLong(KEY_READ_AT + threadId, 0L)
    }

    private fun markReadAt(threadId: String, timestamp: Long) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putLong(KEY_READ_AT + threadId, timestamp).apply()
    }

    private fun persist() {
        val context = appContext ?: return
        val state = _state.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THREADS, Wire.encode(state.threads.values.toList()))
            .putString(KEY_CONVERSATIONS, Wire.encode(state.conversations))
            .putString(KEY_QUICK_REPLIES, Wire.encode(state.quickReplies))
            .putString(KEY_PHONE_NAME, state.phoneName)
            .putString(KEY_BRIDGE_VERSION, state.bridgeVersion)
            .putBoolean(KEY_NOTIFICATION_ACCESS, state.notificationAccess)
            .putBoolean(KEY_RELAY_ENABLED, state.relayEnabled)
            .putLong(KEY_LAST_SYNC, state.lastSyncAt)
            .putLong(KEY_PHONE_GENERATED, state.phoneGeneratedAt)
            .apply()
    }

    private fun restoreFromDataLayer(context: Context, prefs: android.content.SharedPreferences) {
        if (dataLayerRestoreStarted) return
        dataLayerRestoreStarted = true
        appContext = context
        scope.launch {
            val buffer = runCatching {
                Tasks.await(Wearable.getDataClient(context).dataItems, 12, TimeUnit.SECONDS)
            }.getOrNull() ?: return@launch
            try {
                var cachedSync: SyncPayload? = null
                val cachedThreads = mutableListOf<WireThread>()
                for (item in buffer) {
                    val path = item.uri.path ?: continue
                    val json = DataMapItem.fromDataItem(item)
                        .dataMap
                        .getString(Protocol.DATA_KEY_JSON)
                        ?: continue
                    when {
                        path == Protocol.DATA_SYNC -> {
                            cachedSync = Wire.decode<SyncPayload>(json)
                        }
                        path.startsWith(Protocol.DATA_THREAD_PREFIX) -> {
                            Wire.decode<WireThread>(json)?.let { cachedThreads += it }
                        }
                    }
                }
                if (_state.value.threads.isEmpty() && cachedThreads.isNotEmpty()) {
                    cachedThreads.forEach { cached ->
                        val thread = mergeThread(cached)
                        _state.update {
                            it.copy(threads = capThreads(it.threads + (thread.id to thread)))
                        }
                    }
                }
                val sync = cachedSync
                if (sync != null && _state.value.lastSyncAt == 0L) {
                    applySync(sync, connected = false)
                }
                persist()
            } finally {
                buffer.release()
            }
        }
    }

    private fun watchName(): String {
        val model = Build.MODEL.orEmpty().trim()
        return model.ifEmpty { "Wear OS watch" }
    }
}
