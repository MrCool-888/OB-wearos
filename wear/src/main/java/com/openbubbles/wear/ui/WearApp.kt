package com.openbubbles.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material3.MaterialTheme
import com.openbubbles.wear.WatchNavigation
import com.openbubbles.wear.WatchNotifications
import com.openbubbles.wear.WatchRepository
import kotlinx.coroutines.delay

const val WATCH_APP_VERSION = "1.6.0"

sealed interface WearScreen {
    data object Conversations : WearScreen
    data class Chat(val threadId: String) : WearScreen
    data class Composer(val threadId: String) : WearScreen
    data class Media(val threadId: String, val mediaId: String) : WearScreen
    data object NewMessage : WearScreen
    data object Contacts : WearScreen
    data object Settings : WearScreen
}

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val state by WatchRepository.state.collectAsStateWithLifecycle()
    var stack by remember { mutableStateOf(listOf<WearScreen>(WearScreen.Conversations)) }
    val current = stack.last()
    val requestedThread by WatchNavigation.openThread.collectAsStateWithLifecycle()

    fun push(screen: WearScreen) {
        stack = stack + screen
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        WatchRepository.onNotificationPermissionResult(granted)
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                WatchRepository.onNotificationPermissionResult(true)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            WatchRepository.onNotificationPermissionResult(
                WatchNotifications.areSystemNotificationsAllowed(context)
            )
        }
    }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(requestedThread) {
        val threadId = requestedThread ?: return@LaunchedEffect
        stack = listOf(WearScreen.Conversations, WearScreen.Chat(threadId))
        WatchRepository.markReadLocally(threadId)
        val canMarkRead = state.threads[threadId]?.canMarkRead == true ||
            state.conversations.firstOrNull { it.id == threadId }?.canMarkRead == true
        if (canMarkRead) WatchRepository.markRead(threadId)
        WatchNavigation.consume()
    }

    val visibleThread = when (val screen = current) {
        is WearScreen.Chat -> screen.threadId
        is WearScreen.Composer -> screen.threadId
        is WearScreen.Media -> screen.threadId
        else -> null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, visibleThread) {
        val lifecycle = lifecycleOwner.lifecycle
        fun updateVisibility() {
            WatchRepository.setVisibleThread(
                visibleThread.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            )
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            WatchRepository.setVisibleThread(null)
        }
    }

    LaunchedEffect(state.info) {
        if (state.info != null) {
            delay(3_000L)
            WatchRepository.clearNotices()
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(10_000L)
            WatchRepository.clearNotices()
        }
    }

    WearTheme {
        when (val screen = current) {
            WearScreen.Conversations -> ConversationListScreen(
                state = state,
                onOpenThread = { threadId -> push(WearScreen.Chat(threadId)) },
                onOpenSettings = { push(WearScreen.Settings) },
                onNewMessage = { push(WearScreen.NewMessage) },
                onSync = { WatchRepository.sync(context) },
                onOpenPhone = { WatchRepository.openOnPhone(null) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

            is WearScreen.Chat -> ChatScreen(
                state = state,
                threadId = screen.threadId,
                onBack = { pop() },
                onReply = { push(WearScreen.Composer(screen.threadId)) },
                onOpenPhone = { WatchRepository.openOnPhone(screen.threadId) },
                onLoadOlder = { WatchRepository.loadOlder(screen.threadId) },
                onOpenMedia = { mediaId ->
                    push(WearScreen.Media(screen.threadId, mediaId))
                },
                onSendQueued = { WatchRepository.flushQueue(manual = true) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

            is WearScreen.Media -> MediaViewerScreen(
                state = state,
                mediaId = screen.mediaId,
                onBack = { pop() }
            )

            WearScreen.NewMessage -> NewMessageScreen(
                state = state,
                onBack = { pop() },
                onOpenChat = { threadId -> push(WearScreen.Chat(threadId)) },
                onStartChat = { threadId -> push(WearScreen.Chat(threadId)) },
                onOpenContacts = { push(WearScreen.Contacts) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

            WearScreen.Contacts -> ContactPickerScreen(state = state, onBack = { pop() },
                onStartChat = { threadId -> push(WearScreen.Chat(threadId)) })

            is WearScreen.Composer -> ComposerScreen(
                state = state,
                threadId = screen.threadId,
                onBack = { pop() },
                onSent = { pop() },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

            WearScreen.Settings -> SettingsScreen(
                state = state,
                onBack = { pop() },
                onSync = { WatchRepository.sync(context) },
                onOpenPhone = { WatchRepository.openOnPhone(null) },
                onRequestNotificationPermission = { requestNotificationPermission() },
                onOpenNotificationSettings = { openNotificationSettings() },
                onToggleAlerts = { WatchRepository.setAlertsEnabled(context, !state.alertsEnabled) },
                onSendQueued = { WatchRepository.flushQueue(manual = true) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )
        }
    }
}
