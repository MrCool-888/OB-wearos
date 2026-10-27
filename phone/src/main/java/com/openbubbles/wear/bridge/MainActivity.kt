package com.openbubbles.wear.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                BridgeScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationCenter.init(this)
        NotificationCenter.ensureListenerBound()
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6FCC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    surface = Color(0xFFFAF9FD),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurface = Color(0xFF1A1B1F),
    onSurfaceVariant = Color(0xFF44474E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF174A86),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    surface = Color(0xFF121316),
    surfaceVariant = Color(0xFF44474E),
    onSurface = Color(0xFFE2E2E6),
    onSurfaceVariant = Color(0xFFC4C6D0)
)

@Composable
private fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BridgeUiState()) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
            delay(2000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenBubbles Wear", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Phone bridge",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeroCard(state)
            }
            item {
                SetupCard(
                    state = state,
                    onOpenNotificationAccess = { openNotificationAccessSettings(context) },
                    onOpenOpenBubbles = {
                        NotificationActions.launchOpenBubbles(context, BridgeInfo.openBubblesPackage(context))
                    }
                )
            }
            item {
                AutomationCard(
                    state = state,
                    onOpenAutomation = { openAutomationSettings(context) }
                )
            }
            item {
                RelayCard(
                    enabled = state.relayEnabled,
                    onToggle = { enabled ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                NotificationCenter.setRelayEnabled(context, enabled)
                            }
                            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
                        }
                    }
                )
            }
            item {
                SyncCard(
                    state = state,
                    refreshing = refreshing,
                    onSync = {
                        refreshing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                NotificationCenter.syncNow(context)
                            }
                            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
                            refreshing = false
                        }
                    }
                )
            }
            item {
                LimitationsCard()
            }
            item {
                Footer(state)
            }
        }
    }
}

@Composable
private fun HeroCard(state: BridgeUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Your watch, your chats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "A notification companion for OpenBubbles",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.notificationAccess) {
                    "The bridge can read OpenBubbles notifications and relay them to your watch."
                } else {
                    "Grant notification access to let this bridge see OpenBubbles messages."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SetupCard(
    state: BridgeUiState,
    onOpenNotificationAccess: () -> Unit,
    onOpenOpenBubbles: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            StatusRow(
                ok = state.openBubblesInstalled,
                title = "OpenBubbles installed",
                detail = if (state.openBubblesInstalled) {
                    "Found on this phone."
                } else {
                    "Install OpenBubbles 2.0 or newer on this phone."
                }
            )
            Spacer(Modifier.height(10.dp))
            StatusRow(
                ok = state.notificationAccess,
                title = "Notification access",
                detail = if (state.notificationAccess) {
                    "Granted. The bridge can read chat notifications."
                } else {
                    "Required. Android settings will open on the next screen."
                }
            )
            Spacer(Modifier.height(10.dp))
            StatusRow(
                ok = state.watchesConnected > 0,
                title = "Watch connected",
                detail = if (state.watchesConnected > 0) {
                    "Connected over the Wear OS data layer."
                } else {
                    "Open the Wear OS app and pair your watch."
                },
                warning = state.watchesConnected == 0
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.notificationAccess) {
                    Button(onClick = onOpenNotificationAccess) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Notification access")
                    }
                } else {
                    TextButton(onClick = onOpenNotificationAccess) {
                        Text("Notification settings")
                    }
                }
                if (state.openBubblesInstalled) {
                    TextButton(onClick = onOpenOpenBubbles) {
                        Text("Open app")
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationCard(
    state: BridgeUiState,
    onOpenAutomation: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Send without a notification",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Optional accessibility service",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = if (state.automationAvailable) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Settings
                    },
                    contentDescription = null,
                    tint = if (state.automationAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.automationAvailable) {
                    "On. The watch can start new chats and send in chats whose notification is gone. It can also load older messages."
                } else {
                    "Turn this on to start chats, send in chats that aren't notifying, and load older messages. Replies to live notifications work without it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.automationAvailable) {
                    TextButton(onClick = onOpenAutomation) {
                        Text("Automation settings")
                    }
                } else {
                    Button(onClick = onOpenAutomation) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Enable automation")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "It only operates OpenBubbles' own screens. Messages are still sent and encrypted by OpenBubbles, and no account data is copied.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RelayCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Relay to watch",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (enabled) {
                            "New notifications are forwarded to paired watches."
                        } else {
                            "Paused. Cached chats stay on the watch, but nothing new is sent."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun SyncCard(
    state: BridgeUiState,
    refreshing: Boolean,
    onSync: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            InfoRow("Phone", state.phoneName)
            InfoRow("Tracked chats", state.trackedConversations.toString())
            InfoRow("Cached photos", state.cachedMedia.toString())
            InfoRow(
                "Watch",
                when {
                    state.watchesConnected > 1 -> "${state.watchesConnected} connected"
                    state.watchesConnected == 1 -> "Connected"
                    else -> "Not connected"
                }
            )
            InfoRow("Last sync", formatRelativeTime(state.lastPublishAt))
            if (state.lastWatchName.isNotBlank()) {
                InfoRow("Watch name", state.lastWatchName)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Bridge version ${state.bridgeVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSync, enabled = !refreshing) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (refreshing) "Syncing…" else "Sync now")
                }
            }
        }
    }
}

@Composable
private fun LimitationsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "How it works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Bullet("Conversations appear after OpenBubbles posts a notification for them.")
            Bullet("With automation on, the watch can also start chats and open a chat whose notification is gone.")
            Bullet("Muted chats and hidden alerts may not appear until you send or receive in them.")
            Bullet("Photos and GIFs are copied into bridge storage from notifications, then sent to the watch when you tap them. Hide text previews in OpenBubbles prevents images from reaching the bridge.")
            Bullet("Replies, automation sends and mark-read all go through OpenBubbles, so your normal account and encryption are preserved.")
        }
    }
}

@Composable
private fun Footer(state: BridgeUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            if (state.openBubblesInstalled) "Ready for OpenBubbles notifications" else "OpenBubbles not detected",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    detail: String,
    warning: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        val tint = when {
            ok -> MaterialTheme.colorScheme.primary
            warning -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.error
        }
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun openAutomationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private fun openNotificationAccessSettings(context: Context) {
    val component = ComponentName(context, NotificationRelayService::class.java)
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    if (runCatching { context.startActivity(detail) }.isSuccess) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        diff < 7L * 86_400_000L -> "${diff / 86_400_000L} d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(timestamp))
    }
}
