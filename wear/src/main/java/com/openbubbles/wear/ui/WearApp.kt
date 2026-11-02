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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.openbubbles.wear.WatchNavigation
import com.openbubbles.wear.WatchNotifications
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.refreshVisibleConversations
import com.openbubbles.wear.refreshVisibleDirectChat
import kotlinx.coroutines.delay

const val WATCH_APP_VERSION = "3.0.1"

sealed interface WearScreen {
    data object Conversations : WearScreen
    data class Chat(val threadId: String) : WearScreen
    data class Composer(val threadId: String) : WearScreen
    data class Media(val threadId: String, val mediaId: String) : WearScreen
    data object FindChat : WearScreen
    data object Contacts : WearScreen
    data object Settings : WearScreen
}

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Color(0xFFA9C7FF),
            primaryDim = Color(0xFF78A8F2),
            primaryContainer = Color(0xFF143D73),
            onPrimary = Color(0xFF06234D),
            onPrimaryContainer = Color(0xFFEAF2FF),
            secondary = Color(0xFFB8C2D1),
            secondaryDim = Color(0xFF9AA5B4),
            secondaryContainer = Color(0xFF303946),
            onSecondary = Color(0xFF202833),
            onSecondaryContainer = Color(0xFFE0E6EF),
            tertiary = Color(0xFFF0C36A),
            tertiaryDim = Color(0xFFD3A94F),
            tertiaryContainer = Color(0xFF493600),
            onTertiary = Color(0xFF332400),
            onTertiaryContainer = Color(0xFFFFE6A9),
            surfaceContainerLow = Color(0xFF11151B),
            surfaceContainer = Color(0xFF171B22),
            surfaceContainerHigh = Color(0xFF242A34),
            onSurface = Color(0xFFE8EAF0),
            onSurfaceVariant = Color(0xFFC0C8D4),
            outline = Color(0xFF8993A1),
            outlineVariant = Color(0xFF4C5664),
            background = Color(0xFF090B10),
            onBackground = Color(0xFFE8EAF0),
            error = Color(0xFFFFB4AB),
            errorDim = Color(0xFFD77D76),
            errorContainer = Color(0xFF690005),
            onError = Color(0xFF690005),
            onErrorContainer = Color(0xFFFFDAD6)
        ),
        content = content
    )
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
    val showingConversations = current == WearScreen.Conversations
    LaunchedEffect(lifecycleOwner, showingConversations) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (showingConversations) {
                refreshVisibleConversations(WatchRepository.state) { WatchRepository.sync(context, manual = false) }
            } else {
                refreshVisibleDirectChat(WatchRepository.state, WatchRepository::requestThread)
            }
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
                onFindChat = { push(WearScreen.FindChat) },
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

            WearScreen.FindChat -> FindChatScreen(
                state = state,
                onBack = { pop() },
                onOpenChat = { threadId -> push(WearScreen.Chat(threadId)) },
                onOpenContacts = { push(WearScreen.Contacts) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

            WearScreen.Contacts -> ContactPickerScreen(state = state, onBack = { pop() },
                onOpenChat = { threadId -> push(WearScreen.Chat(threadId)) })

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
