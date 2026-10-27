package com.openbubbles.wear

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.openbubbles.wear.protocol.ActionResult
import com.openbubbles.wear.protocol.BridgeError
import com.openbubbles.wear.protocol.HistoryPayload
import com.openbubbles.wear.protocol.HistoryRequest
import com.openbubbles.wear.protocol.MarkReadRequest
import com.openbubbles.wear.protocol.MediaPayload
import com.openbubbles.wear.protocol.MediaRequest
import com.openbubbles.wear.protocol.OpenPhoneRequest
import com.openbubbles.wear.protocol.PingPayload
import com.openbubbles.wear.protocol.PongPayload
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.ReplyRequest
import com.openbubbles.wear.protocol.SendRequest
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.SyncRequest
import com.openbubbles.wear.protocol.ThreadPayload
import com.openbubbles.wear.protocol.ThreadRequest
import com.openbubbles.wear.protocol.ThreadSummary
import com.openbubbles.wear.protocol.UpdatePayload
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.io.File
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

data class WatchMedia(
    val id: String,
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val createdAt: Long = 0L
) {
    val isGif: Boolean get() = mimeType == "image/gif"
}

data class WatchState(
    val loading: Boolean = true,
    val conversations: List<ThreadSummary> = emptyList(),
    val threads: Map<String, WireThread> = emptyMap(),
    val media: Map<String, WatchMedia> = emptyMap(),
    val phoneName: String = "",
    val bridgeVersion: String = "",
    val protocolVersion: Int = 0,
    val notificationAccess: Boolean = false,
    val relayEnabled: Boolean = true,
    val automationAvailable: Boolean = false,
    val connected: Boolean = false,
    val reachable: Boolean = false,
    val lastSyncAt: Long = 0L,
    val phoneGeneratedAt: Long = 0L,
    val quickReplies: List<String> = Protocol.DEFAULT_QUICK_REPLIES,
    val syncing: Boolean = false,
    val requestingThreads: Set<String> = emptySet(),
    val sendingThreads: Set<String> = emptySet(),
    val loadingHistory: Set<String> = emptySet(),
    val historyExhausted: Set<String> = emptySet(),
    val requestingMedia: Set<String> = emptySet(),
    val error: String? = null,
    val info: String? = null
) {
    val phoneOnline: Boolean get() = connected || reachable
}

object WatchRepository {
    private const val TAG = "OBWearRepo"
    private const val PREFS_NAME = "openbubbles_wear"
    private const val KEY_THREADS = "threads"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val KEY_QUICK_REPLIES = "quick_replies"
    private const val KEY_PHONE_NAME = "phone_name"
    private const val KEY_BRIDGE_VERSION = "bridge_version"
    private const val KEY_PROTOCOL_VERSION = "protocol_version"
    private const val KEY_NOTIFICATION_ACCESS = "notification_access"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    private const val KEY_AUTOMATION_AVAILABLE = "automation_available"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_PHONE_GENERATED = "phone_generated"
    private const val KEY_READ_AT = "read_at:"
    private const val KEY_MEDIA_PREFIX = "media:"
    private const val LOCAL_PREFIX = "local-"
    private const val AUTO_PREFIX = "auto-"
    private const val NEW_THREAD_PREFIX = "new:"
    private const val MEDIA_DIR = "watch_media"
    private const val MAX_MESSAGES = 220
    private const val MAX_THREADS = 40
    private const val MAX_MEDIA_FILES = 60
    private const val MAX_MEDIA_BYTES = 40L * 1024 * 1024
    private const val MEDIA_TIMEOUT_MS = 45_000L
    private const val LOCAL_MATCH_WINDOW_MS = 5 * 60_000L
    private const val AUTO_MATCH_WINDOW_MS = 2 * 60_000L
    private const val MAX_REPLY_LENGTH = 800
    private const val HISTORY_PAGE_SIZE = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var initialized = false
    private var lastSyncRequestAt = 0L
    private var dataLayerRestoreStarted = false
    private val pendingSends = java.util.concurrent.ConcurrentHashMap<String, String>()
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
        val media = loadMedia(prefs, app)
        _state.value = WatchState(
            loading = false,
            conversations = conversations,
            threads = threads,
            media = media,
            phoneName = prefs.getString(KEY_PHONE_NAME, "").orEmpty(),
            bridgeVersion = prefs.getString(KEY_BRIDGE_VERSION, "").orEmpty(),
            protocolVersion = prefs.getInt(KEY_PROTOCOL_VERSION, 0),
            notificationAccess = prefs.getBoolean(KEY_NOTIFICATION_ACCESS, false),
            relayEnabled = prefs.getBoolean(KEY_RELAY_ENABLED, true),
            automationAvailable = prefs.getBoolean(KEY_AUTOMATION_AVAILABLE, false),
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
            phoneGeneratedAt = prefs.getLong(KEY_PHONE_GENERATED, 0L),
            quickReplies = quickReplies
        )
        initialized = true
        scope.launch {
            val trimmed = trimMedia(app, _state.value.media)
            if (trimmed.size != _state.value.media.size) {
                persistMedia(app, trimmed)
                _state.update { it.copy(media = trimmed) }
            }
        }
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
        val updatedThreads = _state.value.threads.mapValues { (id, thread) ->
            val summary = byId[id] ?: return@mapValues thread
            val readAt = readAt(id)
            thread.copy(
                title = summary.title,
                isGroup = summary.isGroup,
                unread = summary.unread && summary.lastTimestamp > readAt,
                notificationActive = summary.notificationActive,
                canReply = summary.canReply,
                canMarkRead = summary.canMarkRead,
                canSend = summary.canSend,
                address = summary.address ?: thread.address,
                participants = summary.participants
            )
        }
        // A chat started from an address gets a real shortcut-based ID once
        // OpenBubbles posts its first notification. Drop the temporary copy.
        val realIdsByAddress = summaries
            .filterNot { it.id.startsWith(NEW_THREAD_PREFIX) }
            .mapNotNull { summary ->
                Protocol.normalizeAddress(summary.address)?.let { it to summary.id }
            }
            .toMap()
        val existingThreads = updatedThreads.filterNot { (id, thread) ->
            if (!id.startsWith(NEW_THREAD_PREFIX)) return@filterNot false
            val address = Protocol.normalizeAddress(thread.address) ?: return@filterNot false
            realIdsByAddress.containsKey(address)
        }
        _state.update {
            it.copy(
                loading = false,
                conversations = summaries,
                threads = existingThreads,
                phoneName = payload.phoneName,
                bridgeVersion = payload.bridgeVersion,
                protocolVersion = payload.version,
                notificationAccess = payload.notificationAccess,
                relayEnabled = payload.relayEnabled,
                automationAvailable = payload.automationAvailable,
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

        val address = if (thread.isGroup) null else Protocol.normalizeAddress(thread.address)
        val alias = if (address != null) {
            _state.value.threads.values.firstOrNull { existing ->
                existing.id != thread.id &&
                    Protocol.normalizeAddress(existing.address) == address
            }
        } else {
            null
        }
        val adopted = if (alias != null) {
            val combinedById = LinkedHashMap<String, WireMessage>()
            (alias.messages + thread.messages).forEach { message ->
                val previous = combinedById[message.id]
                combinedById[message.id] = previous?.let(message::mergeMediaFrom) ?: message
            }
            val combined = combinedById.values
                .sortedBy { it.timestamp }
                .takeLast(MAX_MESSAGES)
            thread.copy(
                messages = combined,
                address = thread.address ?: alias.address,
                unread = thread.unread || alias.unread
            )
        } else {
            thread
        }

        val merged = mergeThread(adopted)
        val threads = (_state.value.threads + (merged.id to merged)).toMutableMap()
        if (alias != null) threads.remove(alias.id)
        val conversations = (
            _state.value.conversations.filterNot { it.id == merged.id || it.id == alias?.id } +
                merged.toSummary()
            ).sortedByDescending { it.lastTimestamp }
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
                pendingSends.remove(result.requestId)
                val localId = LOCAL_PREFIX + result.requestId
                updateLocalMessage(
                    threadId = preview,
                    messageId = localId,
                    status = if (result.ok) "sent" else "failed"
                )
                _state.update {
                    it.copy(sendingThreads = it.sendingThreads - preview.orEmpty())
                }
                if (!result.ok) {
                    _state.update {
                        it.copy(error = result.error ?: "The reply was not sent.")
                    }
                } else {
                    markReadLocally(preview)
                }
            }

            Protocol.ACTION_SEND -> {
                val target = pendingSends.remove(result.requestId)
                    ?: result.threadId
                    ?: preview
                val localId = LOCAL_PREFIX + result.requestId
                updateLocalMessage(
                    threadId = target,
                    messageId = localId,
                    status = if (result.ok) "sent" else "failed"
                )
                _state.update {
                    it.copy(
                        sendingThreads = it.sendingThreads - target.orEmpty() -
                            preview.orEmpty(),
                        error = if (result.ok) null
                        else result.error ?: "The message was not sent."
                    )
                }
                if (result.ok && target != null) {
                    markReadLocally(target)
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
                automationAvailable = pong.automationAvailable,
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
        val state = _state.value
        if (!state.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return
        }
        if (threadId in state.sendingThreads) return
        val thread = state.threads[threadId]
        val summary = state.conversations.firstOrNull { it.id == threadId }
        val canReply = (thread?.canReply ?: summary?.canReply) == true
        val canAutomate = (thread?.canSend ?: summary?.canSend) == true
        if (!canReply && !canAutomate) {
            _state.update {
                it.copy(
                    error = "This chat can't send right now. Enable automation in the bridge app or open the chat in OpenBubbles."
                )
            }
            return
        }
        val title = thread?.title ?: summary?.title
        val address = thread?.address ?: summary?.address

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
        val current = thread
        if (current != null) {
            _state.update { it.copy(error = null, info = null) }
            val updated = current.copy(
                messages = (current.messages + local).sortedBy { it.timestamp },
                unread = false
            )
            replaceThread(updated)
        }
        _state.update { it.copy(sendingThreads = it.sendingThreads + threadId) }
        markReadAt(threadId, now)
        persist()
        pendingSends[requestId] = threadId

        val path: String
        val payload: ByteArray
        if (canReply) {
            path = Protocol.PATH_REPLY
            payload = Wire.encodeBytes(
                ReplyRequest(requestId = requestId, threadId = threadId, text = text)
            )
        } else {
            path = Protocol.PATH_SEND
            payload = Wire.encodeBytes(
                SendRequest(
                    requestId = requestId,
                    threadId = threadId,
                    address = address,
                    title = title,
                    text = text
                )
            )
        }
        PhoneLink.send(context, path, payload) { sent ->
            if (!sent) {
                pendingSends.remove(requestId)
                updateLocalMessage(threadId, local.id, "failed")
                _state.update {
                    it.copy(
                        sendingThreads = it.sendingThreads - threadId,
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. The message was not sent."
                    )
                }
            }
        }
        scope.launch {
            delay(if (canReply) 15_000L else 100_000L)
            if (canReply) {
                pendingSends.remove(requestId)
                val pending = _state.value.threads[threadId]
                    ?.messages
                    ?.firstOrNull { it.id == local.id }
                    ?.status == "sending"
                if (!pending) return@launch
            } else if (pendingSends.remove(requestId) == null) {
                return@launch
            }
            updateLocalMessage(threadId, local.id, "failed")
            _state.update {
                it.copy(
                    sendingThreads = it.sendingThreads - threadId,
                    error = if (canReply) {
                        "The phone did not confirm the reply."
                    } else {
                        "The phone did not confirm the automated message. Check that OpenBubbles is signed in and automation is on."
                    }
                )
            }
        }
    }

    /**
     * Adds a temporary conversation for a brand-new address. The phone uses the
     * same "new:<address>" ID so the optimistic watch thread and the automation
     * result refer to one conversation.
     */
    fun startNewChat(address: String, title: String? = null): String? {
        if (!initialized) return null
        val normalized = Protocol.normalizeAddress(address)
        if (normalized == null) {
            _state.update {
                it.copy(error = "Enter a phone number or email address, like +15551234567 or name@example.com.")
            }
            return null
        }
        val existing = _state.value.threads.values.firstOrNull { thread ->
            !thread.isGroup && Protocol.normalizeAddress(thread.address) == normalized
        }
        if (existing != null) return existing.id
        val existingSummary = _state.value.conversations.firstOrNull { summary ->
            !summary.isGroup && Protocol.normalizeAddress(summary.address) == normalized
        }
        if (existingSummary != null) return existingSummary.id
        if (!_state.value.automationAvailable) {
            _state.update {
                it.copy(
                    error = "Enable accessibility automation in the bridge app to start a brand-new chat."
                )
            }
            return null
        }
        val threadId = Protocol.newThreadId(normalized)
        val displayTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: normalized
        val thread = WireThread(
            id = threadId,
            title = displayTitle,
            address = normalized,
            canSend = true,
            updatedAt = System.currentTimeMillis()
        )
        _state.update {
            it.copy(
                error = null,
                info = "New chat with $displayTitle",
                threads = capThreads(it.threads + (threadId to thread)),
                conversations = (
                    it.conversations.filterNot { summary -> summary.id == threadId } +
                        thread.toSummary()
                    ).sortedByDescending { summary -> summary.lastTimestamp }
            )
        }
        persist()
        return threadId
    }

    fun loadOlder(threadId: String) {
        if (!initialized) return
        val state = _state.value
        if (threadId in state.loadingHistory) return
        if (!state.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return
        }
        if (!state.automationAvailable) {
            _state.update {
                it.copy(error = "Older messages need accessibility automation. Enable it in the bridge app on the phone.")
            }
            return
        }
        val context = appContext ?: return
        val thread = state.threads[threadId]
        val summary = state.conversations.firstOrNull { it.id == threadId }
        val requestId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                loadingHistory = it.loadingHistory + threadId,
                error = null,
                info = null
            )
        }
        PhoneLink.send(
            context,
            Protocol.PATH_HISTORY,
            Wire.encodeBytes(
                HistoryRequest(
                    requestId = requestId,
                    threadId = threadId,
                    title = thread?.title ?: summary?.title,
                    address = thread?.address ?: summary?.address,
                    limit = HISTORY_PAGE_SIZE
                )
            )
        ) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        loadingHistory = it.loadingHistory - threadId,
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. Could not load older messages."
                    )
                }
            }
        }
        scope.launch {
            delay(125_000L)
            if (threadId in _state.value.loadingHistory) {
                _state.update {
                    it.copy(
                        loadingHistory = it.loadingHistory - threadId,
                        error = "The phone took too long to load older messages."
                    )
                }
            }
        }
    }

    fun onHistory(payload: HistoryPayload) {
        if (!initialized) return
        val threadId = payload.threadId
        val state = _state.value
        val current = state.threads[threadId]
            ?: state.conversations.firstOrNull { it.id == threadId }?.let { summary ->
                WireThread(
                    id = summary.id,
                    title = summary.title,
                    isGroup = summary.isGroup,
                    participants = summary.participants,
                    address = summary.address,
                    canSend = summary.canSend
                )
            }
        if (payload.error != null) {
            _state.update {
                it.copy(
                    loadingHistory = it.loadingHistory - threadId,
                    error = payload.error
                )
            }
            return
        }
        if (current == null || payload.messages.isEmpty()) {
            _state.update {
                it.copy(
                    loadingHistory = it.loadingHistory - threadId,
                    historyExhausted = if (payload.hasMore) it.historyExhausted
                    else it.historyExhausted + threadId,
                    info = "No older messages were found."
                )
            }
            return
        }
        val mergedById = LinkedHashMap<String, WireMessage>()
        (current.messages + payload.messages).forEach { message ->
            val previous = mergedById[message.id]
            mergedById[message.id] = previous?.let(message::mergeMediaFrom) ?: message
        }
        val mergedMessages = mergedById.values
            .sortedBy { it.timestamp }
            .takeLast(MAX_MESSAGES)
        val updated = current.copy(
            messages = mergedMessages,
            updatedAt = maxOf(current.updatedAt, mergedMessages.lastOrNull()?.timestamp ?: 0L)
        )
        replaceThread(updated)
        _state.update {
            it.copy(
                loadingHistory = it.loadingHistory - threadId,
                historyExhausted = if (payload.hasMore) it.historyExhausted - threadId
                else it.historyExhausted + threadId,
                info = null
            )
        }
        persist()
    }

    /**
     * Asks the phone to republish one cached attachment over the Data Layer.
     * The actual bytes arrive as a data-item asset; the message reply only
     * carries success or a user-facing error.
     */
    fun requestMedia(mediaId: String) {
        if (!initialized) return
        val state = _state.value
        if (state.media.containsKey(mediaId)) return
        if (mediaId in state.requestingMedia) return
        if (!state.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return
        }
        if (state.protocolVersion in 1 until Protocol.VERSION) {
            _state.update {
                it.copy(
                    error = "Update the phone bridge to 1.2.0 or newer to view photos."
                )
            }
            return
        }
        val context = appContext ?: return
        _state.update {
            it.copy(requestingMedia = it.requestingMedia + mediaId, error = null)
        }
        PhoneLink.send(
            context,
            Protocol.PATH_MEDIA_REQUEST,
            Wire.encodeBytes(
                MediaRequest(
                    requestId = UUID.randomUUID().toString(),
                    mediaId = mediaId
                )
            )
        ) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        requestingMedia = it.requestingMedia - mediaId,
                        connected = false,
                        reachable = false,
                        error = "Phone isn't reachable. This photo isn't cached on the watch yet."
                    )
                }
            }
        }
        scope.launch {
            delay(MEDIA_TIMEOUT_MS)
            if (mediaId in _state.value.requestingMedia && _state.value.media[mediaId] == null) {
                _state.update {
                    it.copy(
                        requestingMedia = it.requestingMedia - mediaId,
                        error = "The photo took too long to arrive from the phone."
                    )
                }
            }
        }
    }

    fun onMediaResult(payload: MediaPayload) {
        if (!initialized) return
        if (payload.ok) {
            // Keep the loading state until the asset arrives through the Data
            // Layer. The request timeout covers a missing data item.
            return
        }
        _state.update {
            it.copy(
                requestingMedia = it.requestingMedia - payload.mediaId,
                error = payload.error ?: "The photo could not be loaded from the phone."
            )
        }
    }

    /**
     * Reads one media asset off the Data Layer. Called on a listener callback,
     * so the blocking file descriptor read happens on the repository's IO
     * dispatcher rather than the main thread.
     */
    fun onMediaDataItem(context: Context, mediaId: String, dataMap: DataMap) {
        if (!initialized) return
        val asset = dataMap.getAsset(Protocol.DATA_KEY_ASSET) ?: return
        val mimeType = Protocol.normalizeMimeType(dataMap.getString(Protocol.DATA_KEY_MIME))
            ?: "image/webp"
        val width = dataMap.getInt(Protocol.DATA_KEY_WIDTH)
        val height = dataMap.getInt(Protocol.DATA_KEY_HEIGHT)
        val declaredSize = dataMap.getLong(Protocol.DATA_KEY_SIZE)
        scope.launch {
            val file = writeMediaAsset(context, mediaId, asset)
            if (file == null) {
                _state.update {
                    it.copy(
                        requestingMedia = it.requestingMedia - mediaId,
                        error = "The photo could not be saved on the watch."
                    )
                }
                return@launch
            }
            val media = WatchMedia(
                id = mediaId,
                path = file.absolutePath,
                mimeType = mimeType,
                width = width,
                height = height,
                size = if (declaredSize > 0L) declaredSize else file.length(),
                createdAt = System.currentTimeMillis()
            )
            val mediaMap = trimMedia(context, _state.value.media + (mediaId to media))
            persistMedia(context, mediaMap)
            _state.update {
                it.copy(
                    media = mediaMap,
                    requestingMedia = it.requestingMedia - mediaId,
                    error = null
                )
            }
        }
    }

    /**
     * Debug-only helper used by the watch seed receiver: registers a file that
     * was already written by the caller. Never used by the production flow.
     */
    fun importLocalMedia(
        id: String,
        mimeType: String,
        width: Int,
        height: Int,
        bytes: ByteArray
    ): WatchMedia? {
        if (!initialized) return null
        val context = appContext ?: return null
        val directory = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
        val file = File(directory, id)
        val written = runCatching { file.writeBytes(bytes) }.isSuccess
        if (!written) return null
        val media = WatchMedia(
            id = id,
            path = file.absolutePath,
            mimeType = mimeType,
            width = width,
            height = height,
            size = bytes.size.toLong(),
            createdAt = System.currentTimeMillis()
        )
        val mediaMap = trimMedia(context, _state.value.media + (id to media))
        persistMedia(context, mediaMap)
        _state.update {
            it.copy(
                media = mediaMap,
                requestingMedia = it.requestingMedia - id
            )
        }
        return media
    }

    private fun writeMediaAsset(context: Context, mediaId: String, asset: Asset): File? =
        runCatching {
            val response = Tasks.await(
                Wearable.getDataClient(context).getFdForAsset(asset),
                25,
                TimeUnit.SECONDS
            )
            val input = response.inputStream
                ?: throw IllegalStateException("No asset stream")
            input.use { stream ->
                val directory = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
                val target = File(directory, mediaId)
                val temp = File(directory, "$mediaId.tmp")
                temp.outputStream().use { output -> stream.copyTo(output) }
                if (temp.length() <= 0L) {
                    temp.delete()
                    throw IllegalStateException("Empty asset")
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target
            }
        }.onFailure {
            Log.w(TAG, "Unable to import media $mediaId", it)
        }.getOrNull()

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
            if (message.fromMe && !message.id.startsWith(AUTO_PREFIX)) {
                // The phone records an optimistic "auto-" send until the real
                // message appears. Drop that placeholder when the real one
                // arrives, otherwise the watch would show both.
                val optimistic = remote.values.firstOrNull { candidate ->
                    candidate.id.startsWith(AUTO_PREFIX) &&
                        candidate.fromMe &&
                        candidate.text == message.text &&
                        abs(candidate.timestamp - message.timestamp) <=
                        AUTO_MATCH_WINDOW_MS
                }
                if (optimistic != null) remote.remove(optimistic.id)
            }
            val previous = remote[message.id]
            remote[message.id] = previous?.let(message::mergeMediaFrom) ?: message
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

    private fun loadMedia(
        prefs: android.content.SharedPreferences,
        context: Context
    ): Map<String, WatchMedia> {
        val directory = File(context.filesDir, MEDIA_DIR)
        if (!directory.isDirectory) return emptyMap()
        val loaded = mutableMapOf<String, WatchMedia>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(KEY_MEDIA_PREFIX) || value !is String) return@forEach
            val id = key.removePrefix(KEY_MEDIA_PREFIX)
            val media = decodeMediaRecord(directory, id, value) ?: return@forEach
            loaded[id] = media
        }
        val staleKeys = prefs.all.keys.filter { key ->
            key.startsWith(KEY_MEDIA_PREFIX) &&
                key.removePrefix(KEY_MEDIA_PREFIX) !in loaded
        }
        if (staleKeys.isNotEmpty()) {
            val editor = prefs.edit()
            staleKeys.forEach(editor::remove)
            editor.apply()
        }
        return loaded
    }

    private fun trimMedia(
        context: Context,
        media: Map<String, WatchMedia>
    ): Map<String, WatchMedia> {
        val ordered = media.values.sortedByDescending { it.createdAt }
        val kept = LinkedHashMap<String, WatchMedia>()
        var total = 0L
        for (item in ordered) {
            val file = File(item.path)
            if (!file.isFile || file.length() <= 0L) continue
            if (kept.size >= MAX_MEDIA_FILES) break
            if (kept.isNotEmpty() && total + file.length() > MAX_MEDIA_BYTES) break
            kept[item.id] = item
            total += file.length()
        }
        val keptFiles = kept.values.map { File(it.path).name }.toSet()
        File(context.filesDir, MEDIA_DIR)
            .listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && it.name !in keptFiles }
            ?.forEach { it.delete() }
        return kept
    }

    private fun persistMedia(context: Context, media: Map<String, WatchMedia>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_MEDIA_PREFIX) }
            .forEach(editor::remove)
        media.values.forEach { item ->
            editor.putString(KEY_MEDIA_PREFIX + item.id, encodeMediaRecord(item))
        }
        editor.apply()
    }

    private fun encodeMediaRecord(media: WatchMedia): String = listOf(
        media.mimeType,
        media.width.toString(),
        media.height.toString(),
        media.size.toString(),
        media.createdAt.toString(),
        File(media.path).name
    ).joinToString("\t")

    private fun decodeMediaRecord(
        directory: File,
        id: String,
        raw: String
    ): WatchMedia? {
        val parts = raw.split('\t')
        if (parts.size != 6) return null
        return runCatching {
            val file = File(directory, parts[5])
            if (!file.isFile || file.length() <= 0L) {
                null
            } else {
                WatchMedia(
                    id = id,
                    path = file.absolutePath,
                    mimeType = parts[0],
                    width = parts[1].toInt(),
                    height = parts[2].toInt(),
                    size = parts[3].toLong(),
                    createdAt = parts[4].toLong()
                )
            }
        }.getOrNull()
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
            .putInt(KEY_PROTOCOL_VERSION, state.protocolVersion)
            .putBoolean(KEY_NOTIFICATION_ACCESS, state.notificationAccess)
            .putBoolean(KEY_RELAY_ENABLED, state.relayEnabled)
            .putBoolean(KEY_AUTOMATION_AVAILABLE, state.automationAvailable)
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
