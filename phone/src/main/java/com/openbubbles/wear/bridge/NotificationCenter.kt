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
import com.openbubbles.wear.protocol.WireThread
import java.util.concurrent.ConcurrentHashMap

object NotificationCenter {
    private const val TAG = "OBWearCenter"

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: BridgePreferences
    private lateinit var store: BridgeStore

    private val liveByKey = ConcurrentHashMap<String, Live>()
    private val liveByThread = ConcurrentHashMap<String, Live>()
    @Volatile private var service: NotificationRelayService? = null

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { publishSync() }

    private data class Live(
        val threadId: String,
        val sbn: StatusBarNotification,
        val parsed: NotificationParser.Parsed
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = BridgePreferences(appContext)
            store = BridgeStore(appContext)
            initialized = true
        }
    }

    fun attachService(listener: NotificationRelayService) {
        init(listener)
        service = listener
    }

    fun detachService(listener: NotificationRelayService) {
        if (service !== listener) return
        Log.d(TAG, "listener detached; clearing live state")
        service = null
        liveByKey.clear()
        liveByThread.clear()
        scheduleSync()
    }

    fun ensureListenerBound() {
        if (!initialized) return
        if (service != null) return
        if (!BridgeInfo.hasNotificationAccess(appContext)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(appContext, NotificationRelayService::class.java)
            )
        }.onFailure {
            Log.w(TAG, "Unable to request notification listener rebind", it)
        }
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!initialized) return
        Log.d(TAG, "received ${sbn.packageName} key=${sbn.key}")
        if (!BridgeInfo.isOpenBubblesPackage(sbn.packageName)) return
        Log.d(TAG, "notification ${sbn.packageName} key=${sbn.key} shortcut=${sbn.notification?.shortcutId}")
        val parsed = NotificationParser.parse(appContext, sbn) ?: return

        val live = Live(parsed.thread.id, sbn, parsed)
        val previous = liveByThread[live.threadId]
        if (previous != null && previous.sbn.key != sbn.key) {
            liveByKey.remove(previous.sbn.key)
        }
        liveByKey[sbn.key] = live
        liveByThread[live.threadId] = live

        val latestTimestamp = parsed.thread.messages
            .lastOrNull { !it.fromMe }
            ?.timestamp
            ?: 0L
        val unread = latestTimestamp > 0L && latestTimestamp > prefs.readAt(live.threadId)
        val incoming = parsed.thread.copy(unread = unread)
        val stored = store.upsertIncoming(incoming)
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
            prefs.lastPublishAt = System.currentTimeMillis()
        }
    }

    fun sendSyncTo(nodeId: String) {
        if (!initialized) return
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
        trackedConversations = if (initialized) store.snapshot().size else 0,
        quickReplies = if (initialized) prefs.quickReplies() else Protocol.DEFAULT_QUICK_REPLIES
    )

    fun reply(threadId: String, text: String): String? {
        if (!initialized) return "The bridge is still starting."
        if (!prefs.relayEnabled) return "Relay is paused on the phone."
        if (text.isBlank()) return "The reply is empty."
        val live = liveByThread[threadId]
            ?: return "This chat is not currently notifying. Open it in OpenBubbles on the phone to refresh it."
        val action = NotificationActions.replyAction(live.sbn.notification)
            ?: return "OpenBubbles did not provide a reply action for this chat."
        if (!NotificationActions.sendReply(appContext, action, text.trim())) {
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

    fun openPhone(threadId: String?): String? {
        if (!initialized) return "The bridge is still starting."
        val live = threadId?.let { liveByThread[it] }
        if (live != null && NotificationActions.openContentIntent(appContext, live.sbn.notification)) {
            return null
        }
        val packageName = live?.sbn?.packageName ?: BridgeInfo.openBubblesPackage(appContext)
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
        liveByKey.clear()
        liveByThread.clear()
        prefs.clearCachedMessages()
    }

    private fun buildSync(threads: List<WireThread>): SyncPayload = SyncPayload(
        generatedAt = System.currentTimeMillis(),
        phoneName = BridgeInfo.phoneName(),
        bridgeVersion = BridgeInfo.BRIDGE_VERSION,
        notificationAccess = BridgeInfo.hasNotificationAccess(appContext),
        relayEnabled = prefs.relayEnabled,
        conversations = threads.map { it.toSummary() }
    )

    private fun decorate(thread: WireThread): WireThread {
        val live = liveByThread[thread.id]
        return thread.copy(
            notificationActive = live != null,
            canReply = live?.parsed?.hasReply == true,
            canMarkRead = live?.parsed?.hasMarkRead == true
        )
    }

    private fun publishAfterAction(threadId: String) {
        val updated = store.get(threadId)?.let(::decorate) ?: return
        publishThreadUpdate(updated)
        scheduleSync()
    }

    private fun publishThreadUpdate(thread: WireThread) {
        if (!initialized || !prefs.relayEnabled) return
        WatchLink.putThread(appContext, thread)
        WatchLink.broadcast(
            appContext,
            Protocol.PATH_UPDATE,
            Wire.encodeBytes(UpdatePayload(thread = thread))
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
        WatchLink.broadcast(
            appContext,
            Protocol.PATH_SYNC,
            Wire.encodeBytes(payload)
        )
        prefs.lastPublishAt = System.currentTimeMillis()
    }
}
