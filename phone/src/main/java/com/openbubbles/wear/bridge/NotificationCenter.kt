package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.PongPayload
import com.openbubbles.wear.protocol.SyncPayload
import com.openbubbles.wear.protocol.ThreadPayload
import com.openbubbles.wear.protocol.UpdatePayload
import com.openbubbles.wear.protocol.Wire
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NotificationCenter {
    private const val TAG = "OBWearCenter"
    private const val MAX_SEND_LENGTH = 800
    private const val AUTO_DISCOVERY_INTERVAL_MS = 10 * 60_000L

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: BridgePreferences
    private lateinit var store: BridgeStore
    private lateinit var contentIntents: PendingIntentStore
    private lateinit var replyActions: ReplyActionStore
    private lateinit var mediaCache: MediaCache

    private val liveByKey = ConcurrentHashMap<String, Live>()
    private val liveByThread = ConcurrentHashMap<String, Live>()
    @Volatile private var service: NotificationRelayService? = null
    @Volatile private var listenerBound = false
    private val discoveryLock = Any()
    @Volatile private var discoveryRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { publishSync() }

    private data class Live(
        val threadId: String,
        val sbn: StatusBarNotification,
        val parsed: NotificationParser.Parsed
    )

    data class SendResult(
        val threadId: String?,
        val error: String?,
        val message: WireMessage? = null,
        val retryable: Boolean = false
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = BridgePreferences(appContext)
            store = BridgeStore(appContext)
            contentIntents = PendingIntentStore(appContext)
            replyActions = ReplyActionStore(appContext)
            mediaCache = MediaCache(appContext)
            initialized = true
        }
    }

    fun attachService(listener: NotificationRelayService) {
        init(listener)
        service = listener
    }

    fun onListenerConnected(listener: NotificationRelayService) {
        attachService(listener)
        listenerBound = true
        maybeAutoDiscoverChats(appContext)
    }

    fun detachService(listener: NotificationRelayService) {
        if (service !== listener) return
        Log.d(TAG, "listener detached; clearing live state")
        service = null
        listenerBound = false
        liveByKey.clear()
        liveByThread.clear()
        scheduleSync()
    }

    fun ensureListenerBound() {
        if (!initialized) return
        if (listenerBound) return
        if (!BridgeInfo.hasNotificationAccess(appContext)) return
        requestListenerRebind()
    }

    /**
     * Asks Android to rebind the listener even if our cached service reference
     * looks alive. Used by diagnostics and after Shizuku grants access.
     */
    fun requestListenerRebind() {
        if (!initialized) return
        if (!BridgeInfo.hasNotificationAccess(appContext)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(appContext, NotificationRelayService::class.java)
            )
        }.onFailure {
            Log.w(TAG, "Unable to request notification listener rebind", it)
        }
    }

    fun listenerConnected(): Boolean = listenerBound

    fun lastNotificationAt(context: Context): Long {
        init(context)
        return prefs.lastNotificationAt
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!initialized) return
        Log.d(TAG, "received ${sbn.packageName} key=${sbn.key}")
        if (!BridgeInfo.isOpenBubblesPackage(sbn.packageName)) return
        prefs.lastNotificationAt = System.currentTimeMillis()
        Log.d(TAG, "notification ${sbn.packageName} key=${sbn.key} shortcut=${sbn.notification?.shortcutId}")
        val parsed = NotificationParser.parse(appContext, sbn) ?: return

        val live = Live(parsed.thread.id, sbn, parsed)
        val previous = liveByThread[live.threadId]
        if (previous != null && previous.sbn.key != sbn.key) {
            liveByKey.remove(previous.sbn.key)
        }
        liveByKey[sbn.key] = live
        liveByThread[live.threadId] = live
        contentIntents.put(live.threadId, sbn.notification?.contentIntent)
        replyActions.put(
            live.threadId,
            NotificationActions.savedReply(
                sbn.notification?.let(NotificationActions::replyAction)
            )
        )

        val latestTimestamp = parsed.thread.messages
            .lastOrNull { !it.fromMe }
            ?.timestamp
            ?: 0L
        val unread = latestTimestamp > 0L && latestTimestamp > prefs.readAt(live.threadId)
        val existing = store.get(parsed.thread.id)
        val parsedWithMedia = runCatching {
            mediaCache.ingest(parsed.thread, parsed.mediaSources, previous = existing)
        }.onFailure {
            Log.w(TAG, "Unable to cache media for ${parsed.thread.id}", it)
        }.getOrDefault(parsed.thread)
        val incoming = parsedWithMedia.copy(unread = unread)
        val incomingAddress = Protocol.normalizeAddress(incoming.address)
        val temporary = if (!incoming.isGroup && incomingAddress != null) {
            store.snapshot().firstOrNull { existing ->
                existing.id != incoming.id &&
                    Protocol.normalizeAddress(existing.address) == incomingAddress
            }
        } else {
            null
        }
        val stored = store.upsertIncoming(incoming, mergeFromId = temporary?.id)
        val decorated = decorate(stored)
        publishThreadUpdate(decorated)
        scheduleSync()
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!initialized) return
        Log.d(TAG, "removed ${sbn.packageName} key=${sbn.key} postTime=${sbn.postTime}")
        val live = liveByKey.remove(sbn.key)
        val threadId = live?.threadId
            ?: runCatching {
                NotificationParser.parse(appContext, sbn)?.thread?.id
            }.getOrNull()
            ?: return
        if (live != null && liveByThread[threadId]?.sbn?.key == sbn.key) {
            liveByThread.remove(threadId)
        }
        store.markRemoved(threadId)
        val updated = store.get(threadId)?.let(::decorate) ?: return
        publishThreadUpdate(updated)
        scheduleSync()
    }

    /**
     * Rebuilds live state from the notification service. Called when the listener
     * reconnects, including after a phone reboot.
     */
    fun rescan(listener: NotificationListenerService) {
        init(listener)
        if (!BridgeInfo.hasNotificationAccess(appContext)) return
        val active = runCatching { listener.activeNotifications }.getOrNull() ?: return
        val activeKeys = active.map { it.key }.toSet()

        liveByKey.keys
            .filter { it !in activeKeys }
            .forEach { key ->
                val live = liveByKey.remove(key) ?: return@forEach
                if (liveByThread[live.threadId]?.sbn?.key == key) {
                    liveByThread.remove(live.threadId)
                }
            }

        active.forEach { sbn -> onNotificationPosted(sbn) }

        val activeThreads = active.mapNotNull { sbn ->
            runCatching { NotificationParser.parse(appContext, sbn)?.thread?.id }.getOrNull()
        }.toSet()

        store.snapshot()
            .filter { thread ->
                thread.id !in activeThreads &&
                    (thread.notificationActive || thread.canReply || thread.canMarkRead)
            }
            .forEach { thread ->
                store.markRemoved(thread.id)
                val updated = store.get(thread.id)?.let(::decorate) ?: return@forEach
                publishThreadUpdate(updated)
            }
        scheduleSync()
    }

    fun syncNow(context: Context) {
        init(context)
        maybeAutoDiscoverChats(context)
        service?.let { rescan(it) }
        val threads = store.snapshot().map(::decorate)
        val payload = buildSync(threads)
        if (prefs.relayEnabled) {
            WatchLink.publishAll(appContext, threads, payload)
            WatchLink.broadcast(
                appContext,
                Protocol.PATH_SYNC,
                Wire.encodeBytes(payload)
            )
            pruneMedia(threads)
            prefs.lastPublishAt = System.currentTimeMillis()
        }
    }

    fun sendSyncTo(nodeId: String) {
        if (!initialized) return
        maybeAutoDiscoverChats(appContext)
        val threads = store.snapshot().map(::decorate)
        val payload = buildSync(threads)
        WatchLink.sendToNode(
            appContext,
            nodeId,
            Protocol.PATH_SYNC,
            Wire.encodeBytes(payload)
        )
        if (prefs.relayEnabled) {
            WatchLink.putSync(appContext, payload)
            threads.forEach { WatchLink.putThread(appContext, it) }
            WatchLink.pruneThreads(appContext, threads.map { it.id }.toSet())
            pruneMedia(threads)
            prefs.lastPublishAt = System.currentTimeMillis()
        }
    }

    fun threadFor(threadId: String): WireThread? =
        if (!initialized) null else store.get(threadId)?.let(::decorate)

    fun threadPayload(threadId: String): ThreadPayload {
        val thread = threadFor(threadId)
        return if (thread == null) {
            ThreadPayload(error = "Conversation not found. Open it in OpenBubbles on the phone to refresh it.")
        } else {
            ThreadPayload(thread = thread)
        }
    }

    fun pong(): PongPayload = PongPayload(
        phoneName = BridgeInfo.phoneName(),
        bridgeVersion = BridgeInfo.BRIDGE_VERSION,
        notificationAccess = initialized && BridgeInfo.hasNotificationAccess(appContext),
        relayEnabled = initialized && prefs.relayEnabled,
        phoneLocked = initialized && BridgeInfo.isPhoneLocked(appContext),
        phoneAwake = initialized && BridgeInfo.isPhoneAwake(appContext),
        trackedConversations = if (initialized) store.snapshot().size else 0,
        quickReplies = if (initialized) prefs.quickReplies() else Protocol.DEFAULT_QUICK_REPLIES,
        contactsAccess = initialized && ContactDirectory.hasAccess(appContext)
    )

    fun reply(threadId: String, text: String): String? {
        if (!initialized) return "The bridge is still starting."
        if (!prefs.relayEnabled) return "Relay is paused on the phone."
        if (text.isBlank()) return "The reply is empty."
        val action = liveByThread[threadId]
            ?.let { NotificationActions.replyAction(it.sbn.notification) }
        val saved = if (action == null) replyActions.get(threadId) else null
        if (action == null && saved == null) {
            return "OpenBubbles has no reply action for this chat. Open it in OpenBubbles on the phone to refresh it."
        }
        val sent = when {
            action != null -> NotificationActions.sendReply(appContext, action, text.trim())
            saved != null -> NotificationActions.sendReply(appContext, saved, text.trim())
            else -> false
        }
        if (!sent) {
            return "OpenBubbles rejected the reply. Open the conversation on the phone and try again."
        }
        prefs.markReadAt(threadId)
        store.markRead(threadId)
        publishAfterAction(threadId)
        return null
    }

    fun markRead(threadId: String): String? {
        if (!initialized) return "The bridge is still starting."
        if (!prefs.relayEnabled) return "Relay is paused on the phone."
        val live = liveByThread[threadId]
            ?: return "This chat is not currently notifying. Open it in OpenBubbles on the phone to refresh it."
        val action = NotificationActions.markReadAction(live.sbn.notification)
            ?: return "OpenBubbles did not provide a mark-read action for this chat."
        if (!NotificationActions.sendMarkRead(appContext, action)) {
            return "OpenBubbles rejected the mark-read action."
        }
        prefs.markReadAt(threadId)
        store.markRead(threadId)
        publishAfterAction(threadId)
        return null
    }

    /**
     * Sends through OpenBubbles' own notification reply action: the live
     * notification first, then the action saved from an earlier notification.
     * The phone can stay locked; OpenBubbles performs the send. The callback
     * runs on the caller's thread.
     */
    fun send(
        requestId: String,
        threadId: String?,
        rawText: String,
        callback: (SendResult) -> Unit
    ) {
        if (!initialized) {
            callback(SendResult(threadId, "The bridge is still starting."))
            return
        }
        if (!prefs.relayEnabled) {
            callback(SendResult(threadId, "Relay is paused on the phone."))
            return
        }
        val text = rawText.trim().take(MAX_SEND_LENGTH)
        if (text.isBlank()) {
            callback(SendResult(threadId, "The message is empty."))
            return
        }
        val targetId = threadId
        if (targetId == null) {
            callback(SendResult(null, "Open this chat in OpenBubbles on the phone first."))
            return
        }

        val live = liveByThread[targetId]
        val action = live?.let { NotificationActions.replyAction(it.sbn.notification) }
        if (action != null) {
            if (!NotificationActions.sendReply(appContext, action, text)) {
                callback(
                    SendResult(
                        targetId,
                        "OpenBubbles rejected the reply. Open the conversation on the phone and try again."
                    )
                )
                return
            }
            prefs.markReadAt(targetId)
            store.markRead(targetId)
            recordSent(targetId, requestId, text)
            publishAfterAction(targetId)
            callback(SendResult(targetId, null))
            return
        }

        // The notification may be gone but its PendingIntent usually still
        // works, so a saved action sends without opening or unlocking anything.
        val saved = replyActions.get(targetId)
        if (saved != null && NotificationActions.sendReply(appContext, saved, text)) {
            prefs.markReadAt(targetId)
            store.markRead(targetId)
            recordSent(targetId, requestId, text)
            publishAfterAction(targetId)
            callback(SendResult(targetId, null))
            return
        }

        callback(
            SendResult(
                targetId,
                "OpenBubbles has no reply action for this chat yet. Open it in OpenBubbles on the phone, then try again."
            )
        )
    }

    /**
     * Records an optimistic outgoing message. Its "auto-" ID lets the store
     * replace it when the same message arrives from a real notification.
     */
    private fun recordSent(threadId: String, requestId: String, text: String): WireMessage {
        val now = System.currentTimeMillis()
        val message = WireMessage(
            id = BridgeStore.OPTIMISTIC_MESSAGE_PREFIX + requestId,
            threadId = threadId,
            text = text,
            timestamp = now,
            fromMe = true,
            status = "sent"
        )
        if (store.get(threadId) != null) {
            store.mergeHistory(threadId, listOf(message))
        }
        return message
    }

    fun openPhone(threadId: String?): String? {
        if (!initialized) return "The bridge is still starting."
        val live = threadId?.let { liveByThread[it] }
        if (live != null && NotificationActions.openContentIntent(appContext, live.sbn.notification)) {
            return null
        }
        val savedIntent = threadId?.let { contentIntents.get(it) }
        if (savedIntent != null && runCatching { savedIntent.send() }.isSuccess) {
            return null
        }
        val packageName = live?.sbn?.packageName ?: BridgeInfo.openBubblesPackage(appContext)
        if (threadId != null && Protocol.isChatGuid(threadId) && packageName != null) {
            val launcher = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "OpenBubbles cannot be opened."
            return if (runCatching { appContext.startActivity(ConversationLaunch.existingGuid(launcher, threadId)) }.isSuccess)
                null else "Android did not allow opening this OpenBubbles conversation."
        }
        if (NotificationActions.launchOpenBubbles(appContext, packageName)) return null
        return "OpenBubbles is not installed or cannot be opened."
    }

    fun setRelayEnabled(context: Context, enabled: Boolean) {
        init(context)
        prefs.relayEnabled = enabled
        if (enabled) {
            syncNow(context)
        } else {
            // Tell the watch that the relay is paused without dropping its cache.
            val threads = store.snapshot().map(::decorate)
            val payload = buildSync(threads)
            WatchLink.putSync(appContext, payload)
            WatchLink.broadcast(
                appContext,
                Protocol.PATH_SYNC,
                Wire.encodeBytes(payload)
            )
        }
    }

    fun relayEnabled(context: Context): Boolean {
        init(context)
        return prefs.relayEnabled
    }

    fun lastPublishAt(context: Context): Long {
        init(context)
        return prefs.lastPublishAt
    }

    fun trackedCount(context: Context): Int {
        init(context)
        return store.snapshot().size
    }

    fun clearCache(context: Context) {
        init(context)
        store.clear()
        contentIntents.clear()
        replyActions.clear()
        mediaCache.clear()
        WatchLink.clearMedia(appContext)
        liveByKey.clear()
        liveByThread.clear()
        prefs.clearCachedMessages()
    }

    /**
     * Manual discovery from the watch or the phone app. Shortcuts and
     * notification channels are both read, so groups and one-to-one chats are
     * imported without waiting for a notification.
     */
    fun discoverChats(context: Context, requestId: String): com.openbubbles.wear.protocol.GroupsResult {
        init(context)
        if (!prefs.relayEnabled) {
            return com.openbubbles.wear.protocol.GroupsResult(
                requestId = requestId,
                error = "Relay is paused on the phone."
            )
        }
        val result = PublishedChatDiscovery.discover(context)
        if (result.error != null) {
            return com.openbubbles.wear.protocol.GroupsResult(requestId = requestId, error = result.error)
        }
        prefs.lastChatDiscoveryAt = System.currentTimeMillis()
        val imported = result.chats.map { store.importPublishedChat(it) }
        // The store keeps a bounded number of history-free placeholders. Only
        // report the ones it kept, or tapping a listed chat answers with
        // "Conversation not found".
        val retained = store.snapshot().map { it.id }.toSet()
        val visible = imported.filter { it.id in retained }.map(::decorate)
        scheduleSync()
        return com.openbubbles.wear.protocol.GroupsResult(
            requestId = requestId,
            groups = visible.filter { it.isGroup }.map { it.toSummary() },
            chats = visible.filterNot { it.isGroup }.map { it.toSummary() },
            note = result.note
        )
    }

    /**
     * Refreshes the published chat directory in the background. Shizuku calls
     * are slow, so this never runs on the caller's thread and is throttled.
     */
    fun maybeAutoDiscoverChats(context: Context) {
        init(context)
        if (!prefs.relayEnabled) return
        if (!ShizukuSetup.hasPermission()) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastChatDiscoveryAt < AUTO_DISCOVERY_INTERVAL_MS) return
        synchronized(discoveryLock) {
            if (discoveryRunning) return
            if (now - prefs.lastChatDiscoveryAt < AUTO_DISCOVERY_INTERVAL_MS) return
            discoveryRunning = true
            prefs.lastChatDiscoveryAt = now
        }
        Thread({
            try {
                val result = PublishedChatDiscovery.discover(appContext)
                if (result.chats.isNotEmpty()) {
                    result.chats.forEach { store.importPublishedChat(it) }
                    scheduleSync()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Automatic chat discovery failed", t)
            } finally {
                synchronized(discoveryLock) { discoveryRunning = false }
            }
        }, "OBWearChatDiscovery").start()
    }

    fun mediaCount(context: Context): Int {
        init(context)
        return mediaCache.count()
    }

    /**
     * Publishes one cached attachment as a Data Layer asset. The watch imports
     * it from the resulting data-item event.
     */
    fun media(mediaId: String, callback: (MediaInfo?, String?) -> Unit) {
        if (!initialized) {
            callback(null, "The bridge is still starting.")
            return
        }
        if (!prefs.relayEnabled) {
            callback(null, "Relay is paused on the phone.")
            return
        }
        val info = mediaCache.info(mediaId)
        if (info == null) {
            callback(null, "This photo is no longer cached on the phone.")
            return
        }
        WatchLink.publishMedia(appContext, info) { error ->
            if (error == null) {
                callback(info, null)
            } else {
                callback(null, error)
            }
        }
    }

    private fun buildSync(threads: List<WireThread>): SyncPayload = SyncPayload(
        generatedAt = System.currentTimeMillis(),
        phoneName = BridgeInfo.phoneName(),
        bridgeVersion = BridgeInfo.BRIDGE_VERSION,
        notificationAccess = BridgeInfo.hasNotificationAccess(appContext),
        relayEnabled = prefs.relayEnabled,
        phoneLocked = BridgeInfo.isPhoneLocked(appContext),
        phoneAwake = BridgeInfo.isPhoneAwake(appContext),
        conversations = threads.map { it.toSummary() },
        contactsAccess = ContactDirectory.hasAccess(appContext)
    )

    private fun decorate(thread: WireThread): WireThread {
        val live = liveByThread[thread.id]
        val canReply = live?.parsed?.hasReply == true || replyActions.contains(thread.id)
        return thread.copy(
            notificationActive = live != null,
            canReply = canReply,
            canMarkRead = live?.parsed?.hasMarkRead == true,
            canSend = canReply
        )
    }

    private fun publishAfterAction(threadId: String) {
        val updated = store.get(threadId)?.let(::decorate) ?: return
        publishThreadUpdate(updated)
        scheduleSync()
    }

    private fun pruneMedia(threads: List<WireThread>) {
        val mediaIds = threads
            .flatMap { thread -> thread.messages.mapNotNull { it.mediaId } }
            .toSet()
        mediaCache.prune(mediaIds)
        WatchLink.pruneMedia(appContext, mediaIds)
    }

    private fun publishThreadUpdate(thread: WireThread) {
        if (!initialized || !prefs.relayEnabled) return
        WatchLink.putThread(appContext, thread)
        WatchLink.broadcast(
            appContext,
            Protocol.PATH_UPDATE,
            Wire.encodeBytes(UpdatePayload(thread = Wire.threadForTransport(thread)))
        )
    }

    private fun scheduleSync() {
        if (!initialized) return
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, 400L)
    }

    private fun publishSync() {
        if (!initialized || !prefs.relayEnabled) return
        val threads = store.snapshot().map(::decorate)
        val payload = buildSync(threads)
        WatchLink.putSync(appContext, payload)
        WatchLink.pruneThreads(appContext, threads.map { it.id }.toSet())
        pruneMedia(threads)
        WatchLink.broadcast(
            appContext,
            Protocol.PATH_SYNC,
            Wire.encodeBytes(payload)
        )
        prefs.lastPublishAt = System.currentTimeMillis()
    }
}
