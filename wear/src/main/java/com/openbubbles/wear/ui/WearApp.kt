package com.openbubbles.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import com.openbubbles.wear.WatchRepository
import kotlinx.coroutines.delay

const val WATCH_APP_VERSION = "1.2.0"

sealed interface WearScreen {
    data object Conversations : WearScreen
    data class Chat(val threadId: String) : WearScreen
    data class Composer(val threadId: String) : WearScreen
    data class Media(val threadId: String, val mediaId: String) : WearScreen
    data object NewMessage : WearScreen
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

    fun push(screen: WearScreen) {
        stack = stack + screen
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

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
                onStartChat = { threadId -> push(WearScreen.Composer(threadId)) },
                onDismissNotice = { WatchRepository.clearNotices() }
            )

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
                onDismissNotice = { WatchRepository.clearNotices() }
            )
        }
    }
}
