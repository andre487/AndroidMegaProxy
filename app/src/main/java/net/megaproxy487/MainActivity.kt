package net.megaproxy487

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.model.ProfileColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        PersistentDiagnosticLog.initialize(this, store.diagnosticLogLimitMb())
        BatteryOptimizationReminder.maybeRequest(this)
        setContent { MaterialTheme { MainScreen(this) } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: Activity) {
    val connection by VpnRuntimeState.connection
    val runtimeAlwaysOn by VpnRuntimeState.alwaysOn
    val runtimeLockdown by VpnRuntimeState.lockdown
    val runtimeProfileId by VpnRuntimeState.connectionProfileId
    val store = remember { ConfigStore(activity) }
    var error by remember { mutableStateOf<String?>(null) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(store.sortedProfiles()) }
    var activeProfileId by remember { mutableStateOf(store.activeProfileId()) }
    var connectionProfileId by remember { mutableStateOf(store.connectionProfile().id) }
    var connectionStats by remember { mutableStateOf<DisplayedConnectionStats?>(null) }
    var systemVpnStatus by remember { mutableStateOf(readAlwaysOnVpnStatus(activity)) }
    val alwaysOn = runtimeAlwaysOn || systemVpnStatus.enabled
    val lockdown = runtimeLockdown || systemVpnStatus.lockdown
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemVpnStatus = readAlwaysOnVpnStatus(activity)
                profiles = store.sortedProfiles()
                activeProfileId = store.activeProfileId()
                connectionProfileId = if (readAlwaysOnVpnStatus(activity).enabled) {
                    store.alwaysOnProfileId()
                } else {
                    store.connectionProfile().id
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(alwaysOn) {
        if (alwaysOn) profileMenuExpanded = false
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
                val snapshot = ConnectionStatsReader.snapshot()
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
        if (it.resultCode == Activity.RESULT_OK) {
            if (!isAlwaysOnVpnActive(activity)) ProxyVpnService.start(activity) else error = null
        } else {
            error = "VPN permission is required"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val connect = {
        if (isAlwaysOnVpnActive(activity)) {
            systemVpnStatus = readAlwaysOnVpnStatus(activity)
            error = null
        } else {
            error = store.activeProfile().config.validationError()
        }
        if (error == null && !isAlwaysOnVpnActive(activity)) {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            val intent = VpnService.prepare(activity)
            if (intent == null) ProxyVpnService.start(activity) else vpnPermission.launch(intent)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("MegaProxy") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val connected = connection == VpnConnectionState.CONNECTED
            val statusColor = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            Card(Modifier.fillMaxWidth()) {
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
                        when (connection) {
                            VpnConnectionState.CONNECTED -> "Connected"
                            VpnConnectionState.CONNECTING -> "Connecting…"
                            VpnConnectionState.DISCONNECTED -> "Disconnected"
                        },
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
                }
            }
            Spacer(Modifier.height(24.dp))
            connectionStats?.let { stats ->
                ConnectionStatsCard(stats)
                Spacer(Modifier.height(12.dp))
            }
            val displayedProfileId = if (alwaysOn) runtimeProfileId.ifEmpty { connectionProfileId } else activeProfileId
            val activeProfile = profiles.firstOrNull { it.id == displayedProfileId } ?: profiles.first()
            val profileColor = Color(ProfileColors.argb[Math.floorMod(activeProfile.colorIndex, ProfileColors.argb.size)])
            val onProfileColor = if (profileColor.luminance() > 0.45f) Color.Black else Color.White
            Box(Modifier.fillMaxWidth()) {
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !alwaysOn) {
                        if (!isAlwaysOnVpnActive(activity)) profileMenuExpanded = true
                    },
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
                        Text(activeProfile.displayName, style = MaterialTheme.typography.titleMedium)
                    }
                        DropdownMenu(profileMenuExpanded, { profileMenuExpanded = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.displayNameWithFlag) },
                                    onClick = {
                                        if (!isAlwaysOnVpnActive(activity)) {
                                            store.setActiveProfile(profile.id)
                                            activeProfileId = profile.id
                                        }
                                        profileMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (alwaysOn) {
                    Box(
                        Modifier.matchParentSize()
                            .background(Color.Gray.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                            .clickable(onClick = {}),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Profile locked by Always-on VPN",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (isAlwaysOnVpnActive(activity)) {
                        systemVpnStatus = readAlwaysOnVpnStatus(activity)
                    } else if (connected) {
                        ProxyVpnService.stop(activity)
                    } else {
                        connect()
                    }
                },
                enabled = connection != VpnConnectionState.CONNECTING && !alwaysOn,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connected) "Disconnect" else "Connect") }
            FilledTonalButton(
                onClick = { activity.startActivity(Intent(activity, ConnectionTestActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test") }
            FilledTonalButton(
                onClick = { activity.startActivity(Intent(activity, ProxySettingsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Settings") }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            if (alwaysOn) {
                Text(
                    "Always-on VPN is used. Connection controls are managed by Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun isAlwaysOnVpnActive(activity: Activity): Boolean =
    ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled

private data class DisplayedConnectionStats(
    val native: NativeConnectionStats,
    val downloadBytesPerSecond: Double,
    val uploadBytesPerSecond: Double,
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
