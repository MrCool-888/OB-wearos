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
import com.openbubbles.wear.protocol.ContactsPage
import com.openbubbles.wear.protocol.ContactsRequest
import com.openbubbles.wear.protocol.WatchContact
import com.openbubbles.wear.protocol.MarkReadRequest
import com.openbubbles.wear.protocol.MediaPayload
import com.openbubbles.wear.protocol.MediaRequest
import com.openbubbles.wear.protocol.OpenPhoneRequest
import com.openbubbles.wear.protocol.PingPayload
import com.openbubbles.wear.protocol.PongPayload
import com.openbubbles.wear.protocol.Protocol
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class QueuedSend(
    val requestId: String,
    val threadId: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    /** Time before which an automatic retry should not be attempted. */
    val nextAttemptAt: Long = 0L
)

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
    /** Android is showing a secure keyguard; shown for information only. */
    val phoneLocked: Boolean = false,
    val phoneAwake: Boolean = true,
    val connected: Boolean = false,
    val reachable: Boolean = false,
    val lastSyncAt: Long = 0L,
    val phoneGeneratedAt: Long = 0L,
    val quickReplies: List<String> = Protocol.DEFAULT_QUICK_REPLIES,
    val syncing: Boolean = false,
    val requestingThreads: Set<String> = emptySet(),
    val sendingThreads: Set<String> = emptySet(),
    val requestingMedia: Set<String> = emptySet(),
    val queuedSends: List<QueuedSend> = emptyList(),
    val alertsEnabled: Boolean = true,
    val notificationPermission: Boolean = true,
    val visibleThreadId: String? = null,
    val contacts: List<WatchContact> = emptyList(),
    val contactsQuery: String = "",
    val contactsAccess: Boolean? = null,
    val contactsNextOffset: Int? = null,
    val contactsRequestId: String? = null,
    val contactsRequestOffset: Int = 0,
    val contactsError: String? = null,
    val discoveryRequestId: String? = null,
    val discoveryNotice: String? = null,
    val error: String? = null,
    val info: String? = null
) {
    val phoneOnline: Boolean get() = connected || reachable
    val queuedCount: Int get() = queuedSends.size
    val canPostNotifications: Boolean get() = alertsEnabled && notificationPermission
    val contactsLoading: Boolean get() = contactsRequestId != null
    val groups: List<ThreadSummary> get() = conversations.filter { it.isGroup }
    val directChats: List<ThreadSummary> get() = conversations.filterNot { it.isGroup }

    fun queuedFor(threadId: String): Boolean = queuedSends.any { it.threadId == threadId }
}

object WatchRepository {
    private const val TAG = "OBWearRepo"
    private const val PREFS_NAME = "openbubbles_wear"
    private const val CONTACTS_PREFS_NAME = "openbubbles_wear_contacts"
    private const val KEY_THREADS = "threads"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val KEY_QUICK_REPLIES = "quick_replies"
    private const val KEY_PHONE_NAME = "phone_name"
    private const val KEY_BRIDGE_VERSION = "bridge_version"
    private const val KEY_PROTOCOL_VERSION = "protocol_version"
    private const val KEY_NOTIFICATION_ACCESS = "notification_access"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_PHONE_GENERATED = "phone_generated"
    private const val KEY_READ_AT = "read_at:"
    private const val KEY_MEDIA_PREFIX = "media:"
    private const val KEY_QUEUE = "send_queue"
    private const val KEY_ALERTS_ENABLED = "alerts_enabled"
    private const val KEY_NOTIFY_BASELINE = "notify_baseline"
    private const val KEY_NOTIFIED_AT = "notified_at:"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_CONTACTS_QUERY = "contacts_query"
    private const val KEY_CONTACTS_ACCESS = "contacts_access"
    private const val KEY_CONTACTS_NEXT = "contacts_next"
    private const val LOCAL_PREFIX = "local-"
    private const val AUTO_PREFIX = "auto-"
    /** Legacy local ID for a chat started on an older watch build. */
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
    private const val MAX_QUEUE = 100
    private const val QUEUE_DISPATCH_TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var initialized = false
    private var lastSyncRequestAt = 0L
    private var dataLayerRestoreStarted = false
    @Volatile
    private var queueFlushing = false
    private val pendingSends = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun attach(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val contactPrefs = app.getSharedPreferences(CONTACTS_PREFS_NAME, Context.MODE_PRIVATE)
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
        val queued = loadQueue(prefs)
        val reconciledThreads = reconcileQueuedMessages(threads, queued)
        ensureNotificationBaseline(prefs, reconciledThreads, conversations)
        _state.value = WatchState(
            loading = false,
            conversations = conversations,
            threads = reconciledThreads,
            media = media,
            phoneName = prefs.getString(KEY_PHONE_NAME, "").orEmpty(),
            bridgeVersion = prefs.getString(KEY_BRIDGE_VERSION, "").orEmpty(),
            protocolVersion = prefs.getInt(KEY_PROTOCOL_VERSION, 0),
            notificationAccess = prefs.getBoolean(KEY_NOTIFICATION_ACCESS, false),
            relayEnabled = prefs.getBoolean(KEY_RELAY_ENABLED, true),
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
            phoneGeneratedAt = prefs.getLong(KEY_PHONE_GENERATED, 0L),
            quickReplies = quickReplies,
            queuedSends = queued,
            alertsEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true),
            notificationPermission = WatchNotifications.areSystemNotificationsAllowed(app),
            contacts = contactPrefs.getString(KEY_CONTACTS, null)?.let { Wire.decode<List<WatchContact>>(it) }.orEmpty(),
            contactsQuery = contactPrefs.getString(KEY_CONTACTS_QUERY, "").orEmpty(),
            contactsAccess = if (contactPrefs.contains(KEY_CONTACTS_ACCESS)) contactPrefs.getBoolean(KEY_CONTACTS_ACCESS, false) else null,
            contactsNextOffset = contactPrefs.getInt(KEY_CONTACTS_NEXT, -1).takeIf { it >= 0 }
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
        if (queued.isNotEmpty()) {
            scope.launch {
                delay(1_000L)
                flushQueue()
            }
        }
    }

    fun onAppResumed() {
        if (!initialized) return
        // Probe the phone and refresh if this app has been away for a while.
        val context = appContext ?: return
        _state.update {
            it.copy(notificationPermission = WatchNotifications.areSystemNotificationsAllowed(context))
        }
        PhoneLink.checkReachable(context) { reachable ->
            _state.update { it.copy(reachable = reachable) }
        }
        ping(context)
        flushQueue()
        if (System.currentTimeMillis() - _state.value.lastSyncAt > 15_000L) {
            sync(context, manual = false)
        }
    }

    fun onSync(payload: SyncPayload, fromCache: Boolean = false) {
        if (!initialized) return
        if (payload.generatedAt > 0L && payload.generatedAt < _state.value.phoneGeneratedAt) return
        applySync(payload, connected = !fromCache)
        if (!fromCache) flushQueue()
    }

    @Synchronized
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
        // A chat started from an address on an older watch build gets a real
        // shortcut-based ID once OpenBubbles posts its first notification.
        // Drop the temporary copy.
        val realIdsByAddress = summaries
            .filterNot { it.id.startsWith(NEW_THREAD_PREFIX) }
            .mapNotNull { summary ->
                Protocol.normalizeAddress(summary.address)?.let { it to summary.id }
            }
            .toMap()
        val existingThreads = updatedThreads.filterNot { (id, thread) ->
            if (!id.startsWith(NEW_THREAD_PREFIX)) return@filterNot false
            if (_state.value.queuedFor(id) || pendingSends.containsValue(id)) return@filterNot false
            val address = Protocol.normalizeAddress(thread.address) ?: return@filterNot false
            realIdsByAddress.containsKey(address)
        }
        val localSummaries = existingThreads.values.filter { thread ->
            thread.id !in byId && (_state.value.queuedFor(thread.id) || pendingSends.containsValue(thread.id))
        }.map { it.toSummary() }
        _state.update { current ->
            val unlocked = current.phoneLocked && !payload.phoneLocked
            current.copy(
                loading = false,
                conversations = (summaries + localSummaries).sortedByDescending { it.lastTimestamp },
                threads = existingThreads,
                phoneName = payload.phoneName,
                bridgeVersion = payload.bridgeVersion,
                protocolVersion = payload.version,
                notificationAccess = payload.notificationAccess,
                relayEnabled = payload.relayEnabled,
                phoneLocked = payload.phoneLocked,
                phoneAwake = payload.phoneAwake,
                contactsAccess = payload.contactsAccess ?: current.contactsAccess,
                contacts = if (payload.contactsAccess == false) emptyList() else current.contacts,
                contactsNextOffset = if (payload.contactsAccess == false) null else current.contactsNextOffset,
                contactsRequestId = if (payload.contactsAccess == false) null else current.contactsRequestId,
                connected = connected || current.connected,
                reachable = connected || current.reachable,
                lastSyncAt = if (connected) System.currentTimeMillis() else payload.generatedAt,
                phoneGeneratedAt = maxOf(current.phoneGeneratedAt, payload.generatedAt),
                syncing = false,
                error = null,
                info = when {
                    !current.phoneLocked && payload.phoneLocked ->
                        "Phone locked. Replies still send through OpenBubbles."

                    unlocked -> "Phone unlocked."
                    else -> current.info
                }
            )
        }
        persist()
        if (payload.contactsAccess == false) persistContacts()
        if (connected) {
            val context = appContext
            if (context != null) {
                summaries.filterNot { it.unread }.forEach { summary ->
                    WatchNotifications.cancel(context, summary.id)
                }
            }
            flushQueue()
        }
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

    @Synchronized
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

        val merged = mergeThread(adopted).let {
            if (_state.value.visibleThreadId == it.id) {
                markReadAt(it.id, System.currentTimeMillis())
                it.copy(unread = false)
            } else it
        }
        val threads = (_state.value.threads + (merged.id to merged)).toMutableMap()
        if (alias != null) threads.remove(alias.id)
        if (alias != null) {
            pendingSends.replaceAll { _, id -> if (id == alias.id) merged.id else id }
        }
        val conversations = (
            _state.value.conversations.filterNot { it.id == merged.id || it.id == alias?.id } +
                merged.toSummary()
            ).sortedByDescending { it.lastTimestamp }
        _state.update {
            it.copy(
                loading = false,
                threads = capThreads(threads),
                conversations = conversations,
                queuedSends = it.queuedSends.map { queued ->
                    if (queued.threadId == alias?.id) queued.copy(threadId = merged.id) else queued
                },
                sendingThreads = if (alias != null && alias.id in it.sendingThreads) {
                    it.sendingThreads - alias.id + merged.id
                } else it.sendingThreads,
                connected = connected || it.connected,
                reachable = connected || it.reachable,
                lastSyncAt = if (connected) System.currentTimeMillis() else it.lastSyncAt,
                requestingThreads = it.requestingThreads - merged.id,
                syncing = if (connected) false else it.syncing,
                error = null
            )
        }
        persist()
        appContext?.let { context ->
            WatchNotifications.syncThread(
                context = context,
                thread = merged,
                state = _state.value,
                allowNotify = connected
            )
        }
    }

    @Synchronized
    fun onActionResult(result: ActionResult) {
        if (!initialized) return
        removeQueuedSend(result.requestId)
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
                    it.copy(
                        sendingThreads = it.sendingThreads - preview.orEmpty(),
                        error = if (result.ok) null
                        else result.error ?: "The reply was not sent."
                    )
                }
                if (result.ok) markReadLocally(preview)
            }

            Protocol.ACTION_SEND -> {
                val pendingTarget = pendingSends.remove(result.requestId)
                val target = _state.value.threads.values.firstOrNull { thread ->
                    thread.messages.any { it.id == LOCAL_PREFIX + result.requestId }
                }?.id ?: pendingTarget
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
        flushQueue()
    }

    fun onPong(pong: PongPayload) {
        if (!initialized) return
        _state.update { current ->
            val unlocked = current.phoneLocked && !pong.phoneLocked
            current.copy(
                connected = true,
                reachable = true,
                phoneName = pong.phoneName,
                bridgeVersion = pong.bridgeVersion,
                notificationAccess = pong.notificationAccess,
                relayEnabled = pong.relayEnabled,
                phoneLocked = pong.phoneLocked,
                phoneAwake = pong.phoneAwake,
                contactsAccess = pong.contactsAccess ?: current.contactsAccess,
                contacts = if (pong.contactsAccess == false) emptyList() else current.contacts,
                contactsNextOffset = if (pong.contactsAccess == false) null else current.contactsNextOffset,
                contactsRequestId = if (pong.contactsAccess == false) null else current.contactsRequestId,
                quickReplies = pong.quickReplies.ifEmpty { Protocol.DEFAULT_QUICK_REPLIES },
                info = if (unlocked) "Phone unlocked." else current.info
            )
        }
        persist()
        if (pong.contactsAccess == false) persistContacts()
        flushQueue()
    }

    fun onBridgeError(error: BridgeError) {
        _state.update { it.copy(error = error.error) }
    }

    @Synchronized
    fun requestContacts(query: String = "", more: Boolean = false) {
        if (!initialized) return
        val context = appContext ?: return
        val state = _state.value
        if (!state.phoneOnline || !state.relayEnabled) {
            _state.update { it.copy(contactsError = if (!state.relayEnabled) "Relay is paused on the phone." else "Phone offline. Showing saved contacts.") }
            return
        }
        val search = query.trim().take(80)
        val offset = if (more && search == state.contactsQuery) state.contactsNextOffset ?: return else 0
        val id = UUID.randomUUID().toString()
        _state.update { it.copy(contactsQuery = search, contactsRequestId = id, contactsRequestOffset = offset,
            contacts = if (offset == 0 && search != it.contactsQuery) emptyList() else it.contacts,
            contactsNextOffset = if (offset == 0) null else it.contactsNextOffset, contactsError = null) }
        PhoneLink.send(context, Protocol.PATH_CONTACTS_REQUEST,
            Wire.encodeBytes(ContactsRequest(requestId = id, query = search, offset = offset))) { sent ->
            if (!sent) _state.update { if (it.contactsRequestId != id) it else it.copy(contactsRequestId = null,
                contactsError = "Phone unreachable. Showing saved contacts.") }
        }
        scope.launch {
            delay(20_000L)
            _state.update { if (it.contactsRequestId != id) it else it.copy(contactsRequestId = null,
                contactsError = "No contact list received. Install the matching phone bridge, allow Contacts there, then retry.") }
        }
    }

    @Synchronized
    fun onContactsPage(page: ContactsPage) {
        val current = _state.value
        if (page.requestId != current.contactsRequestId || page.query != current.contactsQuery ||
            page.offset != current.contactsRequestOffset) return
        _state.update { state ->
            val contacts = when {
                !page.contactsAccess -> emptyList()
                page.error != null -> state.contacts
                page.offset == 0 -> page.contacts
                else -> (state.contacts + page.contacts).distinctBy { it.id }
            }.take(5000)
            state.copy(contacts = contacts, contactsAccess = page.contactsAccess, contactsRequestId = null,
                contactsNextOffset = when {
                    !page.contactsAccess -> null
                    page.error != null -> state.contactsNextOffset
                    else -> page.nextOffset?.takeIf { it > page.offset && it <= 5000 }
                }, contactsError = page.error)
        }
        persistContacts()
    }

    fun onPeerConnected() {
        if (!initialized) return
        _state.update { it.copy(reachable = true) }
        flushQueue()
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

    @Synchronized
    fun sendReply(threadId: String, rawText: String): Boolean {
        if (!initialized) return false
        val text = rawText.trim().take(MAX_REPLY_LENGTH)
        if (text.isEmpty()) return false
        val context = appContext ?: return false
        val state = _state.value
        if (!state.relayEnabled) {
            _state.update { it.copy(error = "Relay is paused on the phone.") }
            return false
        }
        if (threadId in state.sendingThreads) return false
        if (state.queuedCount >= MAX_QUEUE) {
            _state.update { it.copy(error = "The outbox is full. Reconnect the phone before sending more.") }
            return false
        }
        val thread = state.threads[threadId]
        val summary = state.conversations.firstOrNull { it.id == threadId }
        val canReply = (thread?.canReply ?: summary?.canReply) == true
        if (!canReply) {
            _state.update {
                it.copy(
                    error = "This chat has no OpenBubbles reply action yet. Open it in OpenBubbles on the phone, then try again."
                )
            }
            return false
        }

        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val local = WireMessage(
            id = LOCAL_PREFIX + requestId,
            threadId = threadId,
            text = text,
            timestamp = now,
            fromMe = true,
            status = "queued"
        )
        val current = thread ?: summary?.let { item ->
            WireThread(
                id = item.id,
                title = item.title,
                isGroup = item.isGroup,
                participants = item.participants,
                unread = item.unread,
                canReply = item.canReply,
                canMarkRead = item.canMarkRead,
                canSend = item.canSend,
                address = item.address,
                updatedAt = item.lastTimestamp
            )
        }
        if (current != null) {
            _state.update { it.copy(error = null, info = null) }
            val updated = current.copy(
                messages = (current.messages + local).sortedBy { it.timestamp },
                unread = false
            )
            replaceThread(updated)
        } else {
            _state.update { it.copy(error = null, info = null) }
        }

        markReadAt(threadId, now)
        // Every send is journaled before it leaves the watch. Once the Data
        // Layer accepts it the entry is removed; if transport fails it stays
        // here and is replayed when the phone reconnects. The phone dedupes
        // by requestId so a replay cannot create a second message.
        enqueueSend(
            QueuedSend(
                requestId = requestId,
                threadId = threadId,
                text = text,
                createdAt = now,
                nextAttemptAt = 0L
            )
        )
        if (!persist(synchronous = true)) {
            removeQueuedSend(requestId)
            updateLocalMessage(threadId, local.id, "failed")
            _state.update {
                it.copy(sendingThreads = it.sendingThreads - threadId,
                    error = "Could not save the message. Free some watch storage and try again.")
            }
            return false
        }
        WatchNotifications.cancel(context, threadId)
        WatchNotifications.markNotified(context, threadId, now)

        _state.update { it.copy(info = if (it.phoneOnline) "Message saved to the outbox." else "Phone offline. The message will send when it reconnects.") }
        flushQueue()
        return true
    }

    @Synchronized
    internal fun onSendDispatched(requestId: String, threadId: String, localId: String, sent: Boolean) {
        // The action result can arrive before the transport callback.
        val target = pendingSends[requestId] ?: return
        if (sent) {
            removeQueuedSend(requestId)
            updateLocalMessage(target, localId, "sending")
            _state.update { it.copy(sendingThreads = it.sendingThreads + target) }
            startSendTimeout(requestId, localId)
        } else {
            pendingSends.remove(requestId)
            updateLocalMessage(target, localId, "queued")
            _state.update {
                it.copy(
                    sendingThreads = it.sendingThreads - threadId - target,
                    connected = false,
                    reachable = false,
                    error = null,
                    info = "Phone offline. The message is queued and will send when it reconnects."
                )
            }
            persist()
        }
    }

    private fun startSendTimeout(
        requestId: String,
        localMessageId: String
    ) {
        scope.launch {
            delay(100_000L)
            synchronized(this@WatchRepository) {
                val target = pendingSends.remove(requestId) ?: return@launch
                updateLocalMessage(target, localMessageId, "failed")
                _state.update {
                    it.copy(
                        sendingThreads = it.sendingThreads - target,
                        error = "The phone did not confirm the message. Check OpenBubbles before trying again to avoid a duplicate."
                    )
                }
                flushQueue()
            }
        }
    }

    /**
     * Replays journaled sends after a reconnect. Entries are removed only when
     * the Data Layer accepts them. Acceptance is not delivery confirmation:
     * an unconfirmed accepted send fails rather than being retried blindly.
     */
    @Synchronized
    fun flushQueue(manual: Boolean = false) {
        if (!initialized) return
        val context = appContext ?: return
        val queued = _state.value.queuedSends
        if (queued.isEmpty()) {
            if (manual) _state.update { it.copy(info = "The outbox is empty.") }
            return
        }
        if (!_state.value.phoneOnline && !manual) return
        if (!_state.value.relayEnabled) return
        if (queueFlushing) {
            if (manual) _state.update { it.copy(info = "Already sending queued messages.") }
            return
        }
        queueFlushing = true
        scope.launch {
            var sentAny = false
            var lastError: String? = null
            try {
                for (item in _state.value.queuedSends.toList()) {
                    if (!manual && item.nextAttemptAt > System.currentTimeMillis()) continue
                    val claimed = synchronized(this@WatchRepository) {
                        if (!_state.value.relayEnabled ||
                            _state.value.queuedSends.none { it.requestId == item.requestId } ||
                            pendingSends.isNotEmpty()
                        ) false else {
                            pendingSends[item.requestId] = item.threadId
                            true
                        }
                    }
                    if (!claimed) continue
                    val state = _state.value
                    val thread = state.threads[item.threadId]
                    val summary = state.conversations.firstOrNull { it.id == item.threadId }
                    val canReply = thread?.canReply == true || summary?.canReply == true
                    if (!canReply) {
                        pendingSends.remove(item.requestId)
                        lastError =
                            "A queued message is waiting for its chat to become available on the phone."
                        continue
                    }
                    val payload = Wire.encodeBytes(
                        SendRequest(
                            requestId = item.requestId,
                            threadId = item.threadId,
                            text = item.text
                        )
                    )
                    val accepted = dispatchAwait(context, Protocol.PATH_SEND, payload)
                    synchronized(this@WatchRepository) {
                        val alreadyCompleted = !pendingSends.containsKey(item.requestId)
                        onSendDispatched(item.requestId, item.threadId, LOCAL_PREFIX + item.requestId, accepted)
                        if (accepted || alreadyCompleted) sentAny = true
                        else lastError = "Phone still offline. Queued messages will send when it reconnects."
                    }
                    if (!accepted) break
                }
            } finally {
                queueFlushing = false
                persist()
            }
            if (sentAny) {
                _state.update {
                    it.copy(
                        info = lastError ?: "Queued messages handed to the phone."
                    )
                }
            } else if (lastError != null) {
                _state.update {
                    it.copy(
                        error = if (manual) lastError else it.error,
                        info = if (manual) null else it.info
                    )
                }
            } else if (manual) {
                _state.update { it.copy(info = "Nothing could be sent yet.") }
            }
            // A fast action result may have arrived while queueFlushing was
            // still true. Give the next message in that chat a chance to run.
            if (sentAny) flushQueue()
        }
    }

    private suspend fun dispatchAwait(
        context: Context,
        path: String,
        payload: ByteArray
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        PhoneLink.send(context, path, payload) { sent -> result.complete(sent) }
        return withTimeoutOrNull(QUEUE_DISPATCH_TIMEOUT_MS) { result.await() } ?: false
    }

    private fun enqueueSend(item: QueuedSend) {
        _state.update { state ->
            val without = state.queuedSends.filterNot { it.requestId == item.requestId }
            state.copy(queuedSends = without + item)
        }
    }

    private fun removeQueuedSend(requestId: String) {
        val existing = _state.value.queuedSends
        if (existing.none { it.requestId == requestId }) return
        _state.update { state ->
            state.copy(queuedSends = state.queuedSends.filterNot { it.requestId == requestId })
        }
        persist()
    }

    /**
     * Finds an existing cached or published chat by phone number or email.
     * Brand-new chats are not supported without OpenBubbles notification
     * actions, so an unknown address is reported instead of created.
     */
    fun findChatByAddress(address: String): String? {
        if (!initialized) return null
        val normalized = Protocol.normalizeAddress(address)
        if (normalized == null) {
            _state.update {
                it.copy(error = "Enter a phone number or email address, like +15551234567 or name@example.com.")
            }
            return null
        }
        val thread = _state.value.threads.values.firstOrNull { candidate ->
            !candidate.isGroup && Protocol.normalizeAddress(candidate.address) == normalized
        }
        val summary = _state.value.conversations.firstOrNull { candidate ->
            !candidate.isGroup && Protocol.normalizeAddress(candidate.address) == normalized
        }
        val id = thread?.id ?: summary?.id
        if (id == null) {
            _state.update {
                it.copy(
                    error = "No saved chat with this contact yet. Send or receive a message in OpenBubbles, then open it on the phone to refresh it."
                )
            }
            return null
        }
        _state.update { it.copy(error = null, info = null) }
        return id
    }

    @Synchronized
    fun discoverChats() {
        val context = appContext ?: return
        val state = _state.value
        if (!state.phoneOnline || !state.relayEnabled) {
            _state.update { it.copy(discoveryNotice = "Connect the phone and enable the relay to find chats without notifications.") }
            return
        }
        if (state.discoveryRequestId != null) return
        val id = UUID.randomUUID().toString()
        _state.update { it.copy(discoveryRequestId = id, discoveryNotice = null) }
        PhoneLink.send(context, Protocol.PATH_GROUPS_REQUEST,
            Wire.encodeBytes(com.openbubbles.wear.protocol.GroupsRequest(requestId = id))) { sent ->
            if (!sent) _state.update { if (it.discoveryRequestId != id) it else it.copy(discoveryRequestId = null,
                discoveryNotice = "Phone unreachable. Start Shizuku and grant the bridge access on the phone, then retry.") }
        }
        scope.launch {
            delay(30_000L)
            _state.update { if (it.discoveryRequestId != id) it else it.copy(discoveryRequestId = null,
                discoveryNotice = "No chat list received. Install matching apps and check Shizuku on the phone.") }
        }
    }

    @Synchronized
    fun onGroups(result: com.openbubbles.wear.protocol.GroupsResult) {
        if (result.requestId != _state.value.discoveryRequestId) return
        _state.update { state ->
            val discovered = (result.groups + result.chats)
                .filter { Protocol.isChatGuid(it.id) }
                .distinctBy { it.id }
            val byAddress = state.conversations
                .filterNot { it.isGroup }
                .mapNotNull { summary -> Protocol.normalizeAddress(summary.address)?.let { it to summary } }
                .toMap()
            val merged = discovered.map { found ->
                val foundAddress = Protocol.normalizeAddress(found.address)
                val old = state.conversations.firstOrNull { it.id == found.id }
                    ?: foundAddress?.let { byAddress[it] }
                if (old == null) found else found.copy(
                    title = old.title.ifBlank { found.title },
                    isGroup = found.isGroup || old.isGroup,
                    unread = old.unread,
                    notificationActive = old.notificationActive,
                    canReply = old.canReply,
                    canMarkRead = old.canMarkRead,
                    canSend = found.canSend || old.canSend,
                    lastTimestamp = old.lastTimestamp,
                    lastPreview = old.lastPreview,
                    messageCount = old.messageCount,
                    participants = found.participants.ifEmpty { old.participants },
                    address = found.address ?: old.address
                )
            }
            val mergedIds = merged.map { it.id }.toSet()
            val mergedAddresses = merged
                .filterNot { it.isGroup }
                .mapNotNull { Protocol.normalizeAddress(it.address) }
                .toSet()
            state.copy(
                discoveryRequestId = null,
                discoveryNotice = result.error ?: result.note,
                conversations = if (result.error != null) state.conversations else {
                    (state.conversations.filterNot { old ->
                        old.id in mergedIds ||
                            (!old.isGroup &&
                                Protocol.normalizeAddress(old.address)?.let { it in mergedAddresses } == true)
                    } + merged).sortedByDescending { it.lastTimestamp }
                }
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
        markReadLocally(threadId)
        val request = MarkReadRequest(requestId = UUID.randomUUID().toString(), threadId = threadId)
        PhoneLink.send(context, Protocol.PATH_MARK_READ, Wire.encodeBytes(request)) { sent ->
            if (!sent) {
                _state.update {
                    it.copy(
                        connected = false,
                        reachable = false,
                        error = "Phone offline. The chat was marked read on the watch."
                    )
                }
            }
        }
    }

    fun setVisibleThread(threadId: String?) {
        if (!initialized) return
        if (_state.value.visibleThreadId == threadId) return
        _state.update { it.copy(visibleThreadId = threadId) }
        val context = appContext ?: return
        if (threadId != null) {
            markReadLocally(threadId)
            latestInboundTimestamp(threadId)?.let {
                WatchNotifications.markNotified(context, threadId, it)
            }
        }
    }

    fun setAlertsEnabled(context: Context, enabled: Boolean) {
        if (!initialized) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALERTS_ENABLED, enabled)
            .apply()
        _state.update {
            it.copy(
                alertsEnabled = enabled,
                info = if (enabled) "Watch notifications are on." else "Watch notifications are off."
            )
        }
        if (!enabled) WatchNotifications.cancelAll(context)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                notificationPermission = granted,
                info = if (granted) {
                    "Watch notifications are allowed."
                } else {
                    "Notifications are blocked. Allow them in watch Settings."
                },
                error = null
            )
        }
        if (!granted) appContext?.let(WatchNotifications::cancelAll)
    }

    fun refreshNotificationState() {
        val context = appContext ?: return
        _state.update {
            it.copy(notificationPermission = WatchNotifications.areSystemNotificationsAllowed(context))
        }
    }

    private fun latestInboundTimestamp(threadId: String): Long? {
        val thread = _state.value.threads[threadId] ?: return null
        return thread.messages.lastOrNull { !it.fromMe }?.timestamp
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
        val context = appContext
        if (context != null) {
            WatchNotifications.cancel(context, threadId)
            latestInboundTimestamp(threadId)?.let {
                WatchNotifications.markNotified(context, threadId, it)
            }
        }
        val thread = _state.value.threads[threadId]
        if (thread != null) replaceThread(thread.copy(unread = false))
        else _state.update { state ->
            state.copy(conversations = state.conversations.map {
                if (it.id == threadId) it.copy(unread = false) else it
            })
        }
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
        incoming.messages.filterNot { it.id.startsWith(LOCAL_PREFIX) }.forEach { message ->
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

        val locals = (old?.messages.orEmpty() + incoming.messages)
            .filter { it.id.startsWith(LOCAL_PREFIX) }
            .distinctBy { it.id }
        val unmatched = mergedRemote.filter { it.fromMe }.toMutableList()
        val keptLocals = mutableListOf<WireMessage>()
        locals.forEach { local ->
            val match = unmatched.firstOrNull { message ->
                message.id == AUTO_PREFIX + local.id.removePrefix(LOCAL_PREFIX) ||
                    (local.status != "queued" && message.text == local.text &&
                        abs(message.timestamp - local.timestamp) <= LOCAL_MATCH_WINDOW_MS)
            }
            if (match != null) {
                unmatched.remove(match)
            } else {
                keptLocals += local
            }
        }

        val allMessages = (mergedRemote + keptLocals.map { it.copy(threadId = incoming.id) })
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

    private fun loadQueue(prefs: android.content.SharedPreferences): List<QueuedSend> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val requestId = item.optString("requestId").takeIf { it.isNotBlank() }
                        ?: continue
                    val threadId = item.optString("threadId").takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        QueuedSend(
                            requestId = requestId,
                            threadId = threadId,
                            text = item.optString("text"),
                            createdAt = item.optLong("createdAt", 0L),
                            attempts = item.optInt("attempts", 0),
                            nextAttemptAt = item.optLong("nextAttemptAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList()).takeLast(MAX_QUEUE)
    }

    private fun encodeQueue(queue: List<QueuedSend>): String {
        val array = JSONArray()
        queue.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("requestId", item.requestId)
                    put("threadId", item.threadId)
                    put("text", item.text)
                    put("createdAt", item.createdAt)
                    put("attempts", item.attempts)
                    put("nextAttemptAt", item.nextAttemptAt)
                }
            )
        }
        return array.toString()
    }

    /**
     * A local message that is no longer present in the journal can never be
     * delivered, so surface it as failed instead of showing "Sending…" forever.
     */
    private fun reconcileQueuedMessages(
        threads: Map<String, WireThread>,
        queue: List<QueuedSend>
    ): Map<String, WireThread> {
        val queuedIds = queue.map { it.requestId }.toSet()
        return threads.mapValues { (_, thread) ->
            thread.copy(
                messages = thread.messages.map { message ->
                    if (!message.id.startsWith(LOCAL_PREFIX)) {
                        message
                    } else {
                        val requestId = message.id.removePrefix(LOCAL_PREFIX)
                        when {
                            requestId in queuedIds -> message.copy(status = "queued")
                            message.status == "sending" || message.status == "queued" ->
                                message.copy(status = "failed")

                            else -> message
                        }
                    }
                }
            )
        }
    }

    /**
     * Do not replay every old chat as a watch notification the first time the
     * 1.3.0 app starts. New messages after this baseline still notify.
     */
    private fun ensureNotificationBaseline(
        prefs: android.content.SharedPreferences,
        threads: Map<String, WireThread>,
        conversations: List<ThreadSummary>
    ) {
        if (prefs.getBoolean(KEY_NOTIFY_BASELINE, false)) return
        val editor = prefs.edit()
        val summaryTimes = conversations.associate { it.id to it.lastTimestamp }
        threads.values.forEach { thread ->
            val latest = thread.messages.lastOrNull { !it.fromMe }?.timestamp
                ?: summaryTimes[thread.id]
                ?: 0L
            if (latest > 0L) editor.putLong(KEY_NOTIFIED_AT + thread.id, latest)
        }
        conversations.forEach { summary ->
            if (summary.lastTimestamp > 0L) {
                val key = KEY_NOTIFIED_AT + summary.id
                if (!prefs.contains(key)) editor.putLong(key, summary.lastTimestamp)
            }
        }
        editor.putBoolean(KEY_NOTIFY_BASELINE, true).apply()
    }

    @Synchronized
    private fun persist(synchronous: Boolean = false): Boolean {
        val context = appContext ?: return false
        val state = _state.value
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THREADS, Wire.encode(state.threads.values.toList()))
            .putString(KEY_CONVERSATIONS, Wire.encode(state.conversations))
            .putString(KEY_QUICK_REPLIES, Wire.encode(state.quickReplies))
            .putString(KEY_PHONE_NAME, state.phoneName)
            .putString(KEY_BRIDGE_VERSION, state.bridgeVersion)
            .putInt(KEY_PROTOCOL_VERSION, state.protocolVersion)
            .putBoolean(KEY_NOTIFICATION_ACCESS, state.notificationAccess)
            .putBoolean(KEY_RELAY_ENABLED, state.relayEnabled)
            .putLong(KEY_LAST_SYNC, state.lastSyncAt)
            .putLong(KEY_PHONE_GENERATED, state.phoneGeneratedAt)
            .putBoolean(KEY_ALERTS_ENABLED, state.alertsEnabled)
            .putString(KEY_QUEUE, encodeQueue(state.queuedSends))
        return if (synchronous) editor.commit() else { editor.apply(); true }
    }

    @Synchronized
    private fun persistContacts() {
        val context = appContext ?: return
        val state = _state.value
        val editor = context.getSharedPreferences(CONTACTS_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONTACTS, Wire.encode(state.contacts))
            .putString(KEY_CONTACTS_QUERY, state.contactsQuery)
            .putInt(KEY_CONTACTS_NEXT, state.contactsNextOffset ?: -1)
        state.contactsAccess?.let { editor.putBoolean(KEY_CONTACTS_ACCESS, it) }
        editor.apply()
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
