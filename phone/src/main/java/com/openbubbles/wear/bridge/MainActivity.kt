package com.openbubbles.wear.bridge

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
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
    var setupBusy by remember { mutableStateOf(false) }
    var setupNotice by remember { mutableStateOf<String?>(null) }
    var setupNoticeFailed by remember { mutableStateOf(false) }
    var showNotificationHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
            delay(2000L)
        }
    }

    fun refreshState() {
        scope.launch {
            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
        }
    }

    fun runSetup(action: (Context) -> ShizukuSetup.StepResult) {
        if (setupBusy) return
        setupBusy = true
        setupNotice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { action(context) }.getOrElse {
                        ShizukuSetup.StepResult(false, it.message ?: "Setup failed. Use the manual Android settings steps instead.")
                    }
                }
                setupNotice = result.message
                setupNoticeFailed = !result.ok
                state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
            } finally {
                setupBusy = false
            }
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            withContext(Dispatchers.IO) { NotificationCenter.syncNow(context) }
            state = withContext(Dispatchers.IO) { readBridgeUiState(context) }
            setupNotice = if (granted) "Contacts allowed. On the watch open New message → Choose a contact."
                else "Contacts were not allowed. Recent chats and manual number/email entry still work."
            setupNoticeFailed = !granted
        }
    }

    if (showNotificationHelp) {
        NotificationAccessHelpDialog(
            state = state, busy = setupBusy, notice = setupNotice,
            onDismiss = { showNotificationHelp = false },
            onOpenAppInfo = {
                runCatching { context.startActivity(NotificationAccessSetup.appInfoIntent(context)) }
                    .onFailure {
                        setupNotice = "Open Android Settings → Apps → OpenBubbles Wear → ⋮ → Allow restricted settings."
                        setupNoticeFailed = true
                    }
            },
            onOpenNotificationAccess = { openNotificationAccessSettings(context) },
            onUnblockWithShizuku = { runSetup { ShizukuSetup.unblockNotificationAccess(it) } }
        )
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
                    onNotificationBlocked = { showNotificationHelp = true },
                    onOpenOpenBubbles = {
                        NotificationActions.launchOpenBubbles(context, BridgeInfo.openBubblesPackage(context))
                    }
                )
            }
            item {
                ContactsCard(state.contactsAccess,
                    onAllow = { contactsPermission.launch(android.Manifest.permission.READ_CONTACTS) },
                    onSettings = { runCatching { context.startActivity(NotificationAccessSetup.appInfoIntent(context)) } })
            }
            item {
                ShizukuCard(
                    state = state,
                    busy = setupBusy,
                    notice = setupNotice,
                    noticeFailed = setupNoticeFailed,
                    onRequestPermission = {
                        val granted = ShizukuSetup.requestPermission()
                        setupNotice = if (granted) {
                            "Shizuku access is already granted."
                        } else if (ShizukuSetup.isAvailable()) {
                            "Approve the Shizuku prompt, then tap Finish setup."
                        } else {
                            "Shizuku is not running. Start it, then try again."
                        }
                        setupNoticeFailed = !ShizukuSetup.isAvailable()
                        refreshState()
                    },
                    onRunSetup = { runSetup { ShizukuSetup.runAll(it) } },
                    onOpenManager = { ShizukuSetup.openManager(context) },
                    onOpenWebsite = { openWebsite(context) },
                    onDismissNotice = { setupNotice = null }
                )
            }
            item {
                AutomationCard(
                    state = state,
                    onOpenAutomation = { openAutomationSettings(context) }
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Groups without notifications", style = MaterialTheme.typography.titleMedium)
                        Text(GroupShortcutDiscovery.NOTE, style = MaterialTheme.typography.bodySmall)
                        Text("Open OpenBubbles first so it can publish recent named chats. Start Shizuku and grant bridge access above, then import. Reading messages still needs accessibility and an unlocked phone.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !setupBusy, onClick = {
                            runSetup { ctx ->
                                val result = NotificationCenter.discoverGroups(ctx, java.util.UUID.randomUUID().toString())
                                ShizukuSetup.StepResult(result.error == null, result.error
                                    ?: "Imported ${result.groups.size} published groups. Sync the watch. ${result.note.orEmpty()}")
                            }
                        }) { Text("Find published groups with Shizuku") }
                    }
                }
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
                    },
                    onReconnectListener = {
                        NotificationCenter.requestListenerRebind()
                        setupNotice = "Asked Android to reconnect the notification listener."
                        setupNoticeFailed = false
                        refreshState()
                    },
                    onOpenBatterySettings = { openBatterySettings(context) },
                    onCopyDiagnostics = {
                        scope.launch {
                            val report = withContext(Dispatchers.IO) { BridgeDiagnostics.create(context, state) }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("OpenBubbles Wear permissions", report))
                            Toast.makeText(context, "Permission diagnostics copied (no messages or contacts).", Toast.LENGTH_LONG).show()
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
private fun ContactsCard(allowed: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Watch contact picker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusRow(allowed, "Contacts", if (allowed) "Allowed. Browse names, numbers and email addresses on the watch."
                else "Optional. Allow access to the phone address book used by OpenBubbles on Android.")
            Text("When you browse or search on the watch, names and phone/email addresses are copied over the Wear Data Layer. No contact photos, notes, or third-party uploads. OpenBubbles-only private contacts may not be in this address book.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Watch: New message → Choose a contact. Starting a new chat still needs accessibility automation; reading contacts does not.",
                style = MaterialTheme.typography.bodySmall)
            if (!allowed) Button(onClick = onAllow) { Text("Allow contact picker") }
            TextButton(onClick = onSettings) { Text("Contacts permission settings") }
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
    onNotificationBlocked: () -> Unit,
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
                title = "Read OpenBubbles notifications",
                detail = if (state.notificationAccess) {
                    "Granted. The bridge can read chat notifications."
                } else {
                    "Required. Use Notification access below, not the ordinary app-notification permission. If Android blocks it, open the help steps."
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "App info → Notifications controls alerts posted by this phone app, not access to OpenBubbles messages. The bridge does not post phone alerts, so that screen may say Blocked with a grey switch. Use the Notification access button below. Watch alerts are configured on the watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.legacyBridgeInstalled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The old bridge is also installed. This version uses com.openbubbles.wear. Remove only the old com.openbubbles.wear.bridge installation so you do not enable the wrong service. Do not uninstall OpenBubbles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
            if (!state.notificationAccess) {
                TextButton(onClick = onNotificationBlocked) {
                    Text("Notification access blocked?")
                }
            }
        }
    }
}

@Composable
private fun NotificationAccessHelpDialog(
    state: BridgeUiState,
    busy: Boolean,
    notice: String?,
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onUnblockWithShizuku: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification access help") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("If App info → Notifications says Blocked and its switch is grey, that is NOT notification-listener access. This bridge does not post phone alerts. First use Open Notification access below; do not change system security settings just to enable ordinary phone alerts.")
                Text("Android 13+ can block notification access for apps installed from APKs. This is called Restricted settings; allowing ordinary notifications does not remove it.")
                Text("1. Open Notification access below and try the bridge's switch once. Dismiss any Restricted settings warning.")
                Text("2. Open App info below. Tap ⋮ at the top right → Allow restricted settings and confirm with your PIN or fingerprint. This option may only appear after step 1.")
                Text("3. Return to Notification access and enable OpenBubbles Wear. This permission is under Special app access, not App info → Notifications.")
                Text("Only continue if you trust this bridge: Android gives a notification listener access to all notifications, including sensitive content. The bridge only relays OpenBubbles notifications.")
                if (state.shizukuPermission && !state.notificationAccess) {
                    Text("Alternatively, Unblock with Shizuku approves restricted settings for this bridge only and grants its notification-listener access. It does not turn on accessibility automation or change OpenBubbles.")
                } else if (!state.notificationAccess) {
                    Text("If the menu is missing, you can optionally start Shizuku using wireless debugging and grant access in the phone bridge's Shizuku card, then return here.")
                }
                Text("A work-profile, parental-control, or device-administrator policy may also block access. This app cannot override those policies.")
                if (state.notificationAccess) Text("Notification access is now granted.", color = MaterialTheme.colorScheme.primary)
                if (notice != null) Text(notice)
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.shizukuPermission && !state.notificationAccess) {
                    Button(onClick = onUnblockWithShizuku, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Checking access…" else "Unblock with Shizuku")
                    }
                }
                TextButton(onClick = onOpenAppInfo, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open App info") }
                TextButton(onClick = onOpenNotificationAccess, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open Notification access") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    )
}

@Composable
private fun ShizukuCard(
    state: BridgeUiState,
    busy: Boolean,
    notice: String?,
    noticeFailed: Boolean,
    onRequestPermission: () -> Unit,
    onRunSetup: () -> Unit,
    onOpenManager: () -> Unit,
    onOpenWebsite: () -> Unit,
    onDismissNotice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Optional Shizuku setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "No root. Every step is also available manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = if (state.shizukuPermission) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Settings
                    },
                    contentDescription = null,
                    tint = if (state.shizukuPermission) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Shizuku can grant this bridge notification access and accessibility, and exempt it from battery optimization without you hunting through settings. It runs commands as the shell user, never as root, and never modifies OpenBubbles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (noticeFailed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (noticeFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissNotice) {
                        Text("Dismiss")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            when {
                !state.shizukuAvailable -> {
                    Text(
                        if (state.shizukuInstalled) {
                            "Shizuku is installed but its service isn't running. Start Shizuku with wireless debugging, then return here."
                        } else {
                            "Shizuku isn't installed. Install and start it if you want one-tap setup; otherwise use the Android settings buttons above."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.shizukuInstalled) {
                            Button(onClick = onOpenManager) {
                                Text("Open Shizuku")
                            }
                        } else {
                            Button(onClick = onOpenWebsite) {
                                Text("Get Shizuku")
                            }
                        }
                    }
                }

                !state.shizukuPermission -> {
                    Text(
                        "Shizuku is running. Grant this app access and Shizuku will show a confirmation prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestPermission) {
                            Text("Grant Shizuku access")
                        }
                        TextButton(onClick = onOpenManager) {
                            Text("Open Shizuku")
                        }
                    }
                }

                else -> {
                    StatusRow(
                        ok = state.notificationAccess,
                        title = "Notification access",
                        detail = if (state.notificationAccess) {
                            "Granted."
                        } else {
                            "Not granted yet."
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusRow(
                        ok = state.automationEnabled,
                        title = "Accessibility automation",
                        detail = state.automationStatus
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusRow(
                        ok = state.batteryUnrestricted,
                        title = "Background access",
                        detail = if (state.batteryUnrestricted) {
                            "Exempt from battery optimization."
                        } else {
                            "Android may pause the bridge in the background."
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.notificationAccess ||
                            !state.automationEnabled ||
                            !state.batteryUnrestricted
                        ) {
                            Button(onClick = onRunSetup, enabled = !busy) {
                                Text(if (busy) "Working…" else "Finish setup")
                            }
                        } else {
                            Text(
                                "All assisted steps are complete.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = onOpenManager) {
                            Text("Open Shizuku")
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Shizuku is independent of OpenBubbles. If it is unavailable, the manual setup above is fully supported.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    imageVector = if (state.automationEnabled) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Settings
                    },
                    contentDescription = null,
                    tint = if (state.automationEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.automationEnabled) {
                    state.automationStatus
                } else {
                    "Turn this on to start chats, send in chats that aren't notifying, and load older messages. Replies to live notifications work without it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.automationEnabled) {
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
    onSync: () -> Unit,
    onReconnectListener: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Status and diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            InfoRow("Phone", state.phoneName)
            InfoRow("Android", "${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
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
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            InfoRow(
                "Listener service",
                when {
                    state.listenerConnected -> "Connected"
                    state.notificationAccess -> "Reconnecting…"
                    else -> "Notification access off"
                }
            )
            InfoRow(
                "Accessibility service",
                when {
                    state.automationAvailable -> "Connected"
                    state.automationEnabled -> "Enabled; not connected"
                    else -> "Off"
                }
            )
            InfoRow(
                "Last OpenBubbles alert",
                if (state.lastNotificationAt > 0L) {
                    formatRelativeTime(state.lastNotificationAt)
                } else {
                    "None seen yet"
                }
            )
            InfoRow(
                "OpenBubbles",
                when {
                    !state.openBubblesInstalled -> "Not installed"
                    state.openBubblesVersion.isBlank() -> "Installed"
                    else -> "v${state.openBubblesVersion}"
                }
            )
            InfoRow(
                "Battery",
                if (state.batteryUnrestricted) "Unrestricted" else "Optimized"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReconnectListener,
                    enabled = state.notificationAccess
                ) {
                    Text("Reconnect listener")
                }
                TextButton(onClick = onOpenBatterySettings) {
                    Text("Battery settings")
                }
            }
            TextButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Copy permission diagnostics")
            }
            Spacer(Modifier.height(4.dp))
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
            when {
                !state.openBubblesInstalled -> "OpenBubbles not detected"
                !state.notificationAccess -> "Enable notification-listener access above"
                else -> "Ready for OpenBubbles notifications"
            },
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

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openWebsite(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuSetup.WEBSITE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openNotificationAccessSettings(context: Context) {
    val detail = NotificationAccessSetup.listenerIntent(context)
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
