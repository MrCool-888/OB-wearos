package com.openbubbles.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the tile and complication in sync with the cached repository state.
 *
 * Both surfaces read the same local cache as the app, so a tile tap is instant
 * and does not depend on the phone being awake. Updates are requested only when
 * the visible summary (unread count, latest chat, connection state) changes, to
 * avoid waking the tile host every time a message body is refreshed.
 */
object WatchSurfaces {
    private const val TAG = "OBWearSurfaces"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var attached = false
    private var lastSignature: String? = null

    fun attach(context: Context) {
        if (attached) return
        synchronized(this) {
            if (attached) return
            attached = true
        }
        val app = context.applicationContext
        scope.launch {
            WatchRepository.state.collectLatest { state ->
                val conversations = state.conversationsForDisplay
                val unread = conversations.count { it.unread }
                val latest = conversations.firstOrNull { it.unread }
                    ?: conversations.maxByOrNull { it.lastTimestamp }
                val signature = buildString {
                    append(unread)
                    append('|')
                    append(latest?.id)
                    append('|')
                    append(latest?.lastTimestamp)
                    append('|')
                    append(state.phoneOnline)
                    append('|')
                    append(state.queuedCount)
                    append('|')
                    conversations.filter { it.unread }.take(3).forEach {
                        append(listOf(it.id, it.title, it.lastPreview, it.lastTimestamp))
                    }
                }
                if (signature == lastSignature) return@collectLatest
                lastSignature = signature
                refresh(app)
            }
        }
    }

    fun refresh(context: Context) {
        runCatching {
            TileService.getUpdater(context).requestUpdate(WatchTileService::class.java)
        }.onFailure {
            Log.d(TAG, "Tile update unavailable", it)
        }
        runCatching {
            val component = ComponentName(context, UnreadComplicationService::class.java)
            ComplicationDataSourceUpdateRequester.create(context, component).requestUpdateAll()
        }.onFailure {
            Log.d(TAG, "Complication update unavailable", it)
        }
    }
}
