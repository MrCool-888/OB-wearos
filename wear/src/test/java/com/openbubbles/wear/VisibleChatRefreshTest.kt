package com.openbubbles.wear

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VisibleChatRefreshTest {
    @Test fun refreshesOnlyWhileResumedAndRestartsImmediatelyOnResume() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val state = MutableStateFlow(WatchState(visibleThreadId = "chat", connected = true, directBridge = true))
        val requests = mutableListOf<String>()
        val looper = shadowOf(Looper.getMainLooper())
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshVisibleDirectChat(state) { requests += it }
            }
        }
        try {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            looper.idleFor(Duration.ofSeconds(30))
            assertEquals(emptyList<String>(), requests)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            looper.idle()
            assertEquals(listOf("chat"), requests)
            looper.idleFor(Duration.ofSeconds(15))
            assertEquals(listOf("chat", "chat"), requests)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            looper.idleFor(Duration.ofMinutes(2))
            assertEquals(2, requests.size)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            looper.idle()
            assertEquals(3, requests.size)
        } finally {
            job.cancel()
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            looper.idle()
        }
    }

    @Test fun disconnectHiddenChatPausedRelayAndNewAddressStopRefreshing() {
        val ready = WatchState(visibleThreadId = "chat", connected = true, directBridge = true)
        val state = MutableStateFlow(ready)
        val requests = mutableListOf<String>()
        val looper = shadowOf(Looper.getMainLooper())
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            refreshVisibleDirectChat(state) { requests += it }
        }
        try {
            looper.idle()
            assertEquals(listOf("chat"), requests)
            state.value = ready.copy(info = "Unrelated state update")
            looper.idle()
            assertEquals(1, requests.size)
            for (inactive in listOf(ready.copy(connected = false, reachable = true),
                ready.copy(directBridge = false), ready.copy(relayEnabled = false),
                ready.copy(visibleThreadId = null), ready.copy(visibleThreadId = "new:alex@example.com"))) {
                state.value = inactive
                looper.idleFor(Duration.ofSeconds(45))
                assertEquals(1, requests.size)
            }
            state.value = ready.copy(visibleThreadId = "canonical")
            looper.idle()
            assertEquals(listOf("chat", "canonical"), requests)
            looper.idleFor(Duration.ofSeconds(15))
            assertEquals(listOf("chat", "canonical", "canonical"), requests)
        } finally {
            job.cancel()
            looper.idle()
        }
    }

    @Test fun conversationListRefreshRunsEveryTwentySecondsOnlyWhileResumed() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val state = MutableStateFlow(WatchState(connected = true, directBridge = true))
        var requests = 0
        val looper = shadowOf(Looper.getMainLooper())
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshVisibleConversations(state) { requests++ }
            }
        }
        try {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            looper.idleFor(Duration.ofSeconds(40))
            assertEquals(0, requests)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            looper.idle()
            assertEquals(1, requests)
            looper.idleFor(Duration.ofSeconds(19))
            assertEquals(1, requests)
            looper.idleFor(Duration.ofSeconds(1))
            assertEquals(2, requests)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            looper.idleFor(Duration.ofMinutes(2))
            assertEquals(2, requests)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            looper.idle()
            assertEquals(3, requests)
            job.cancel() // Leaving Conversations cancels this screen-owned job too.
            looper.idleFor(Duration.ofMinutes(2))
            assertEquals(3, requests)
        } finally {
            job.cancel()
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            looper.idle()
        }
    }

    @Test fun conversationListRefreshRequiresLivePatchAndRelayAndDoesNotOverlapASync() {
        val ready = WatchState(connected = true, directBridge = true)
        val state = MutableStateFlow(ready)
        var requests = 0
        val looper = shadowOf(Looper.getMainLooper())
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            refreshVisibleConversations(state) { requests++ }
        }
        try {
            looper.idle()
            assertEquals(1, requests)
            state.value = ready.copy(info = "Unrelated update", syncing = true)
            looper.idleFor(Duration.ofSeconds(40))
            assertEquals(1, requests)
            state.value = ready
            looper.idle()
            assertEquals(1, requests)
            looper.idleFor(Duration.ofSeconds(20))
            assertEquals(2, requests)
            for (inactive in listOf(ready.copy(connected = false, reachable = true),
                ready.copy(directBridge = false), ready.copy(relayEnabled = false))) {
                state.value = inactive
                looper.idleFor(Duration.ofSeconds(40))
                assertEquals(2, requests)
            }
            state.value = ready
            looper.idle()
            assertEquals(3, requests)
        } finally {
            job.cancel()
            looper.idle()
        }
    }
}
