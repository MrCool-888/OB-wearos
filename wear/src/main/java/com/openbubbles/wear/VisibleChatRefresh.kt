package com.openbubbles.wear

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Collected only while the UI is resumed; never schedules background polling. */
internal suspend fun refreshVisibleDirectChat(state: StateFlow<WatchState>, refresh: (String) -> Unit) {
    state.map {
        it.visibleThreadId?.takeIf { id ->
            it.connected && it.directBridge && it.relayEnabled && !id.startsWith("new:")
        }
    }.distinctUntilChanged().collectLatest { threadId ->
        if (threadId != null) {
            while (true) {
                refresh(threadId)
                delay(15_000L)
            }
        }
    }
}

/** The caller owns the visible Conversations screen's resumed lifetime. */
internal suspend fun refreshVisibleConversations(state: StateFlow<WatchState>, refresh: () -> Unit) {
    state.map { it.connected && it.directBridge && it.relayEnabled }
        .distinctUntilChanged().collectLatest { ready ->
            if (ready) {
                while (true) {
                    if (!state.value.syncing) refresh()
                    delay(20_000L)
                }
            }
        }
}
