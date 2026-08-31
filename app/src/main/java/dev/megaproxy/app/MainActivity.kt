package dev.megaproxy.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.megaproxy.app.data.ConfigStore
import dev.megaproxy.app.vpn.DiagnosticLog
import dev.megaproxy.app.vpn.ProxyVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(ProxyVpnService.isRunning) }
    var alwaysOn by remember { mutableStateOf(ProxyVpnService.isAlwaysOnMode) }
    var lockdown by remember { mutableStateOf(ProxyVpnService.isLockdownMode) }
    var showAlwaysOnDisconnectDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                running = ProxyVpnService.isRunning
                alwaysOn = ProxyVpnService.isAlwaysOnMode
                lockdown = ProxyVpnService.isLockdownMode
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            ProxyVpnService.start(activity)
            running = true
        }
    }
    val testVpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) ProxyVpnService.test(activity)
        else error = "VPN permission is required for the connection test"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = { TopAppBar(title = { Text("MegaProxy") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    running && lockdown -> "VPN connected · Always-on lockdown"
                    running && alwaysOn -> "VPN connected · Always-on"
                    running -> "VPN connected"
                    else -> "VPN disconnected"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { activity.startActivity(Intent(activity, ProxySettingsActivity::class.java)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Proxy & JA3") }
                Button(
                    onClick = { activity.startActivity(Intent(activity, SplitTunnelActivity::class.java)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Split tunneling") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (running) {
                        if (alwaysOn) {
                            showAlwaysOnDisconnectDialog = true
                        } else {
                            ProxyVpnService.stop(activity)
                            running = false
                        }
                    } else {
                        val config = store.load()
                        error = config.validationError()
                        if (error == null) {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            val intent = VpnService.prepare(activity)
                            if (intent == null) { ProxyVpnService.start(activity); running = true }
                            else vpnPermission.launch(intent)
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (running) "Disconnect" else "Connect") }
                Button(onClick = {
                    val config = store.load()
                    error = config.connectionValidationError()
                    if (error == null) {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        val intent = VpnService.prepare(activity)
                        if (intent == null) ProxyVpnService.test(activity) else testVpnPermission.launch(intent)
                    }
                }, modifier = Modifier.weight(1f)) { Text("Test connection") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, modifier = Modifier.weight(1f)) {
                    Text("Always-on VPN")
                }
                Button(onClick = {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    })
                }, modifier = Modifier.weight(1f)) { Text("Battery settings") }
            }
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                "Metadata may include destination hostnames. Credentials and traffic content are never logged.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { DiagnosticLog.clear() }) { Text("Clear") }
                Button(onClick = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MegaProxy diagnostics", DiagnosticLog.entries.joinToString("\n")))
                }) { Text("Copy") }
            }
            SelectionContainer {
                Text(DiagnosticLog.entries.joinToString("\n").ifEmpty { "No diagnostic events yet." }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showAlwaysOnDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnDisconnectDialog = false },
            title = { Text("Always-on VPN is enabled") },
            text = {
                Text("Android will immediately restart MegaProxy if it is stopped. Open the system VPN settings and disable Always-on VPN to disconnect it explicitly.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showAlwaysOnDisconnectDialog = false
                    activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }) { Text("Open VPN settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAlwaysOnDisconnectDialog = false }) { Text("Cancel") }
            },
        )
    }
}
