package net.megaproxy487

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.megaproxy487.vpn.ConnectionStatsReader
import net.megaproxy487.vpn.NativeConnectionStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.SshHostKeyPromptState
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import net.megaproxy487.vpn.VpnTransportProtocol
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.vpn.AlwaysOnVpnStatus
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.openAndroidVpnSettings
import net.megaproxy487.vpn.OTHER_ALWAYS_ON_VPN_MESSAGE
import net.megaproxy487.model.ProfileColors
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.ui.theme.MegaProxyTheme

class MainActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BatteryOptimizationReminder.maybeRequest(this)
        setContent { MegaProxyTheme { MainScreen(this) } }
    }

    override fun onResume() {
        super.onResume()
        ProxyVpnService.refreshStatus(this)
        val status = readAlwaysOnVpnStatus(this)
        val store = ConfigStore(this)
        val profileId = if (status.enabled) store.alwaysOnProfileId() else store.connectionProfile().id
        VpnRuntimeState.updateSystem(status.enabled, status.lockdown, profileId)
    }
}

@Composable
private fun ProfileTypeBadge(type: ProxyType, foreground: Color) {
    Surface(
        color = foreground.copy(alpha = 0.14f),
        contentColor = foreground,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.5f)),
    ) {
        Text(
            when (type) {
                ProxyType.HTTPS -> "HTTPS"
                ProxyType.SSH -> "SSH"
                ProxyType.SSH_JUMP -> "SSH + Jump"
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: Activity) {
    val connection by VpnRuntimeState.connection
    val runtimeAlwaysOn by VpnRuntimeState.alwaysOn
    val runtimeLockdown by VpnRuntimeState.lockdown
    val runtimeProfileId by VpnRuntimeState.connectionProfileId
    val networkWarning by VpnRuntimeState.networkWarning
    val transportProtocol by VpnRuntimeState.transportProtocol
    val pendingHostKey by SshHostKeyPromptState.pending
    val store = remember { ConfigStore(activity) }
    var error by remember { mutableStateOf<String?>(null) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(store.sortedProfiles()) }
    var activeProfileId by remember { mutableStateOf(store.activeProfileId()) }
    var connectionProfileId by remember { mutableStateOf(store.connectionProfile().id) }
    var connectionStats by remember { mutableStateOf<DisplayedConnectionStats?>(null) }
    var systemVpnStatus by remember { mutableStateOf(readAlwaysOnVpnStatus(activity)) }
    var vpnPermissionRequestedAt by remember { mutableStateOf(0L) }
    var showCrashReport by remember { mutableStateOf(CrashHandler.hasPendingReport()) }
    var showAlwaysOnConflict by remember { mutableStateOf(false) }
    var pendingReconnect by remember { mutableStateOf(store.hasPendingReconnect()) }
    var globalSettings by remember { mutableStateOf(store.globalConnectionSettings()) }
    val alwaysOn = runtimeAlwaysOn || systemVpnStatus.enabled
    val lockdown = runtimeLockdown || systemVpnStatus.lockdown
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val refreshed = withContext(ConfigIoDispatcher) {
                        val status = readAlwaysOnVpnStatus(activity)
                        RefreshedMainConfig(
                            status = status,
                            profiles = store.sortedProfiles(),
                            activeProfileId = store.activeProfileId(),
                            connectionProfileId = if (status.enabled) store.alwaysOnProfileId() else store.connectionProfile().id,
                            pendingReconnect = store.hasPendingReconnect(),
                            globalSettings = store.globalConnectionSettings(),
                        )
                    }
                    systemVpnStatus = refreshed.status
                    profiles = refreshed.profiles
                    activeProfileId = refreshed.activeProfileId
                    connectionProfileId = refreshed.connectionProfileId
                    pendingReconnect = refreshed.pendingReconnect
                    globalSettings = refreshed.globalSettings
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(connection) {
        pendingReconnect = store.hasPendingReconnect()
    }
    LaunchedEffect(lifecycleOwner, connection) {
        if (connection != VpnConnectionState.CONNECTED) {
            connectionStats = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous: NativeConnectionStats? = null
            var smoothedDownload = 0.0
            var smoothedUpload = 0.0
            while (true) {
                // JNI reflection and JSON decoding are small but not frame work. Some
                // vendor devices expose their cost as visible input latency, so sample
                // away from the main dispatcher.
                val snapshot = withContext(Dispatchers.Default) {
                    ConnectionStatsReader.snapshot()
                }
                if (snapshot != null) {
                    previous?.let { old ->
                        val download = (snapshot.downloadBytes - old.downloadBytes).coerceAtLeast(0).toDouble()
                        val upload = (snapshot.uploadBytes - old.uploadBytes).coerceAtLeast(0).toDouble()
                        val alpha = 0.35
                        smoothedDownload = if (smoothedDownload == 0.0) download else alpha * download + (1 - alpha) * smoothedDownload
                        smoothedUpload = if (smoothedUpload == 0.0) upload else alpha * upload + (1 - alpha) * smoothedUpload
                    }
                    previous = snapshot
                    connectionStats = DisplayedConnectionStats(snapshot, smoothedDownload, smoothedUpload)
                }
                delay(1_000)
            }
        }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(activity) == null) {
            if (!isAlwaysOnVpnActive(activity)) ProxyVpnService.start(activity) else error = null
        } else {
            val status = readAlwaysOnVpnStatus(activity)
            val dismissedImmediately = System.currentTimeMillis() - vpnPermissionRequestedAt < 1_000
            if (status.hasOtherProvider || dismissedImmediately) {
                error = null
                showAlwaysOnConflict = true
            } else {
                error = "VPN access was not granted in the Android confirmation dialog"
            }
        }
    }
    val requestVpnAccess = {
        val status = readAlwaysOnVpnStatus(activity)
        if (status.hasOtherProvider) {
            error = null
            showAlwaysOnConflict = true
        } else {
            val intent = VpnService.prepare(activity)
            if (intent == null) {
                ProxyVpnService.start(activity)
            } else {
                vpnPermissionRequestedAt = System.currentTimeMillis()
                vpnPermission.launch(intent)
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnAccess()
    }
    val connect = {
        if (isAlwaysOnVpnActive(activity)) {
            systemVpnStatus = readAlwaysOnVpnStatus(activity)
            error = null
        } else {
            error = globalSettings.applyTo(store.activeProfile().config).validationError()
        }
        if (error == null && !isAlwaysOnVpnActive(activity)) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestVpnAccess()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MegaProxy") }) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            val connected = connection == VpnConnectionState.CONNECTED
            val statusLabel = when (connection) {
                VpnConnectionState.CONNECTED -> "Connected"
                VpnConnectionState.CONNECTING -> "Connecting"
                VpnConnectionState.DISCONNECTED -> "Disconnected"
            }
            val statusColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Card(Modifier.fillMaxWidth().semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusLabel
            }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (connected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = statusColor,
                    )
                    Text(
                        if (connection == VpnConnectionState.CONNECTING) "Connecting…" else statusLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        color = statusColor,
                    )
                    if (alwaysOn) {
                        Text(
                            if (lockdown) "Always-on · Block without VPN" else "Always-on VPN",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (connected && transportProtocol != VpnTransportProtocol.UNKNOWN) {
                        val transportLabel = when (transportProtocol) {
                            VpnTransportProtocol.HTTP_1_1 -> stringResource(R.string.transport_http_1_1)
                            VpnTransportProtocol.HTTP_2 -> stringResource(R.string.transport_http_2)
                            VpnTransportProtocol.SSH_MULTIPLEXED -> stringResource(R.string.transport_ssh_multiplexed)
                            VpnTransportProtocol.UNKNOWN -> ""
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                transportLabel,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            networkWarning?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(14.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            connectionStats?.let { stats ->
                ConnectionStatsCard(stats)
                Spacer(Modifier.height(12.dp))
            }
            val displayedProfileId = if (alwaysOn) runtimeProfileId.ifEmpty { connectionProfileId } else activeProfileId
            val activeProfile = profiles.firstOrNull { it.id == displayedProfileId } ?: profiles.first()
            val actualProfile = profiles.firstOrNull { it.id == runtimeProfileId }
            val activeProfileError = globalSettings.applyTo(activeProfile.config).connectionValidationError()
            val profileColor = Color(ProfileColors.argb[Math.floorMod(activeProfile.colorIndex, ProfileColors.argb.size)])
            val onProfileColor = if (profileColor.luminance() > 0.45f) Color.Black else Color.White
            Box(Modifier.fillMaxWidth()) {
                Card(
                    Modifier.fillMaxWidth().clickable { profileMenuExpanded = true },
                    colors = CardDefaults.cardColors(
                        containerColor = profileColor,
                        contentColor = onProfileColor,
                        disabledContainerColor = profileColor,
                        disabledContentColor = onProfileColor,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Profile", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (activeProfile.flagEmoji.isNotEmpty()) {
                            Surface(
                                color = Color.White,
                                contentColor = Color.Black,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)),
                            ) {
                                Text(activeProfile.flagEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                        Text(activeProfile.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ProfileTypeBadge(activeProfile.config.type, onProfileColor)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select profile", tint = onProfileColor)
                    }
                    if (actualProfile != null && actualProfile.id != activeProfile.id && connection != VpnConnectionState.DISCONNECTED) {
                        Text("Connected through: ${actualProfile.displayNameWithFlag}", style = MaterialTheme.typography.bodySmall)
                    }
                        DropdownMenu(profileMenuExpanded, { profileMenuExpanded = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(profile.displayNameWithFlag, modifier = Modifier.weight(1f))
                                            ProfileTypeBadge(profile.config.type, MaterialTheme.colorScheme.onSurface)
                                        }
                                    },
                                    onClick = {
                                        val useAsAlwaysOn = isAlwaysOnVpnActive(activity)
                                        if (useAsAlwaysOn) {
                                            connectionProfileId = profile.id
                                        } else {
                                            activeProfileId = profile.id
                                        }
                                        if (useAsAlwaysOn || connection != VpnConnectionState.DISCONNECTED) {
                                            ProxyVpnService.switchProfile(activity, profile.id, useAsAlwaysOn)
                                        } else {
                                            scope.launch(ConfigIoDispatcher) { store.setActiveProfile(profile.id) }
                                        }
                                        profileMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (connection == VpnConnectionState.DISCONNECTED && activeProfileError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(activeProfileError, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = {
                            activity.startActivity(Intent(activity, ProfileEditorActivity::class.java).apply {
                                putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, activeProfile.id)
                            })
                        }) { Text(stringResource(R.string.configure)) }
                    }
                }
            }
            Button(
                onClick = {
                    if (isAlwaysOnVpnActive(activity)) {
                        systemVpnStatus = readAlwaysOnVpnStatus(activity)
                    } else if (connection != VpnConnectionState.DISCONNECTED) {
                        ProxyVpnService.stop(activity)
                    } else {
                        connect()
                    }
                },
                enabled = !alwaysOn &&
                    (connection != VpnConnectionState.DISCONNECTED || activeProfileError == null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (connection) {
                        VpnConnectionState.CONNECTED -> stringResource(R.string.disconnect)
                        VpnConnectionState.CONNECTING -> stringResource(R.string.disconnect)
                        VpnConnectionState.DISCONNECTED -> stringResource(R.string.connect)
                    },
                )
            }
            if (connected) {
                FilledTonalButton(
                    onClick = { ProxyVpnService.reconnect(activity) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.reconnect))
                        if (pendingReconnect) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    stringResource(R.string.apply_new_settings),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = { activity.startActivity(Intent(activity, ConnectionTestActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.test_connection)) }
            FilledTonalButton(
                onClick = { activity.startActivity(Intent(activity, ProxySettingsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings)) }
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            val intent = withContext(Dispatchers.IO) {
                                FeedbackEmail.createIntent(activity, connection, alwaysOn, lockdown)
                            }
                            activity.startActivity(intent)
                        }.onFailure {
                            error = "No email client is available: ${it.message ?: "unknown error"}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.feedback)) }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            if (alwaysOn) {
                Text(
                    "Always-on VPN is used. Selecting a profile updates the Always-on profile and reconnects immediately; disconnect remains managed by Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            }
        }
    }

    if (showCrashReport) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("MegaProxy stopped unexpectedly") },
            text = { Text("A privacy-filtered crash report was saved. You can send it to help diagnose the problem.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            val intent = withContext(Dispatchers.IO) {
                                FeedbackEmail.createIntent(
                                    activity,
                                    connection,
                                    alwaysOn,
                                    lockdown,
                                    crashReport = true,
                                )
                            }
                            activity.startActivity(intent)
                            CrashHandler.markReportHandled()
                            showCrashReport = false
                        }.onFailure {
                            error = "Could not open an email client: ${it.message ?: "unknown error"}"
                        }
                    }
                }) { Text("Report") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashHandler.markReportHandled()
                    showCrashReport = false
                }) { Text("Close") }
            },
        )
    }

    if (showAlwaysOnConflict) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnConflict = false },
            title = { Text("Always-on VPN is already in use") },
            text = { Text(OTHER_ALWAYS_ON_VPN_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    showAlwaysOnConflict = false
                    openAndroidVpnSettings(activity)
                }) { Text("Change settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAlwaysOnConflict = false }) { Text("Cancel") }
            },
        )
    }

    pendingHostKey?.takeIf { !it.testOnly }?.let { pending ->
        fun dismissHostKeyPrompt() {
            SshHostKeyPromptState.clear()
            ProxyVpnService.dismissHostKeyPrompt(activity)
        }
        AlertDialog(
            onDismissRequest = ::dismissHostKeyPrompt,
            title = { Text(if (pending.changed) "SSH host key changed" else "Trust SSH host key?") },
            text = { Text(buildString {
                if (pending.changed) {
                    append("Warning: the previously trusted key for the ${pending.hop} host has changed. This may indicate a server reinstall or a man-in-the-middle attack. Verify it through a trusted channel before replacing it.\n\n")
                } else {
                    append("This is the first connection to the ${pending.hop} SSH host. Verify its fingerprint through a trusted channel.\n\n")
                }
                append("Algorithm: ${pending.algorithm}\nFingerprint: ${pending.fingerprint}")
            }) },
            confirmButton = {
                TextButton(onClick = {
                    if (store.trustSshHostKey(pending.profileId, pending.hop, pending.fingerprint)) {
                        SshHostKeyPromptState.clear()
                        ProxyVpnService.reconnect(activity)
                    } else {
                        error = "The SSH host key could not be saved to this profile"
                    }
                }) { Text(if (pending.changed) "Replace trusted key" else "Trust and connect") }
            },
            dismissButton = {
                TextButton(onClick = ::dismissHostKeyPrompt) { Text("Cancel") }
            },
        )
    }
}

private fun isAlwaysOnVpnActive(activity: Activity): Boolean =
    ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled

private data class DisplayedConnectionStats(
    val native: NativeConnectionStats,
    val downloadBytesPerSecond: Double,
    val uploadBytesPerSecond: Double,
)

private data class RefreshedMainConfig(
    val status: AlwaysOnVpnStatus,
    val profiles: List<ProxyProfile>,
    val activeProfileId: String,
    val connectionProfileId: String,
    val pendingReconnect: Boolean,
    val globalSettings: net.megaproxy487.model.GlobalConnectionSettings,
)

@Composable
private fun ConnectionStatsCard(stats: DisplayedConnectionStats) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatValue("Download", "↓ ${formatRate(stats.downloadBytesPerSecond)}")
            StatValue("Upload", "↑ ${formatRate(stats.uploadBytesPerSecond)}")
            val latency = stats.native.proxyLatencyMillis
            val ageMillis = System.currentTimeMillis() - stats.native.proxyLatencyAtMillis
            StatValue(
                "Proxy latency",
                if (latency <= 0) "—" else "${latency.toInt()} ms${formatAge(ageMillis)}",
            )
        }
        Text(
            if (stats.native.connectionSamples == 0) "Connection errors: no samples"
            else "Connection errors: ${"%.1f".format(stats.native.connectionErrorRate * 100)}% · ${stats.native.connectionSamples} samples",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun StatValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000)
    bytesPerSecond >= 1_000 -> "%.1f KB/s".format(bytesPerSecond / 1_000)
    else -> "${bytesPerSecond.toInt()} B/s"
}

private fun formatAge(ageMillis: Long): String = when {
    ageMillis < 30_000 -> ""
    ageMillis < 120_000 -> " · ${ageMillis / 1_000}s ago"
    else -> " · ${ageMillis / 60_000}m ago"
}
