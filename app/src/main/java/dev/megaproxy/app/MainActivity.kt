package dev.megaproxy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.megaproxy.app.data.ConfigStore
import dev.megaproxy.app.vpn.ProxyVpnService
import dev.megaproxy.app.vpn.VpnConnectionState
import dev.megaproxy.app.vpn.VpnRuntimeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BatteryOptimizationReminder.maybeRequest(this)
        setContent { MaterialTheme { MainScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: Activity) {
    val connection by VpnRuntimeState.connection
    val store = remember { ConfigStore(activity) }
    var error by remember { mutableStateOf<String?>(null) }
    var alwaysOn by remember { mutableStateOf(ProxyVpnService.isAlwaysOnMode) }
    var lockdown by remember { mutableStateOf(ProxyVpnService.isLockdownMode) }
    var showAlwaysOnDisconnectDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                alwaysOn = ProxyVpnService.isAlwaysOnMode
                lockdown = ProxyVpnService.isLockdownMode
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) ProxyVpnService.start(activity)
        else error = "VPN permission is required"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = { TopAppBar(title = { Text("MegaProxy") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
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
            Button(
                onClick = {
                    if (connected) {
                        if (alwaysOn) showAlwaysOnDisconnectDialog = true
                        else ProxyVpnService.stop(activity)
                    } else {
                        error = store.load().validationError()
                        if (error == null) {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            val intent = VpnService.prepare(activity)
                            if (intent == null) ProxyVpnService.start(activity) else vpnPermission.launch(intent)
                        }
                    }
                },
                enabled = connection != VpnConnectionState.CONNECTING,
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
        }
    }
    if (showAlwaysOnDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnDisconnectDialog = false },
            title = { Text("Always-on VPN is enabled") },
            text = { Text("Android will restart MegaProxy if it is stopped. Disable Always-on VPN in system settings to disconnect it.") },
            confirmButton = {
                TextButton(onClick = {
                    showAlwaysOnDisconnectDialog = false
                    activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }) { Text("Open VPN settings") }
            },
            dismissButton = { TextButton(onClick = { showAlwaysOnDisconnectDialog = false }) { Text("Cancel") } },
        )
    }
}
