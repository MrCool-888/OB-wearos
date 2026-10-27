package com.openbubbles.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot navigation from a notification, tile or complication into a chat.
 * MainActivity feeds the intent extra in; WearApp consumes it and pushes the
 * chat route.
 */
object WatchNavigation {
    const val ACTION_OPEN_CHAT = "com.openbubbles.wear.action.OPEN_CHAT"
    const val EXTRA_THREAD_ID = "com.openbubbles.wear.extra.THREAD_ID"

    private val _openThread = MutableStateFlow<String?>(null)
    val openThread: StateFlow<String?> = _openThread.asStateFlow()

    fun requestOpen(threadId: String?) {
        val value = threadId?.trim()
        if (value.isNullOrEmpty()) return
        _openThread.value = value
    }

    fun consume() {
        _openThread.value = null
    }
}
