package net.megaproxy487

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.TestDiagnosticLog
import net.megaproxy487.vpn.TestState
import net.megaproxy487.vpn.OTHER_ALWAYS_ON_VPN_MESSAGE
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.openAndroidVpnSettings
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

class ConnectionTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoStart = savedInstanceState == null
        if (autoStart) TestDiagnosticLog.reset()
        setContent { MaterialTheme { ConnectionTestScreen(this, autoStart) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTestScreen(activity: Activity, autoStart: Boolean) {
    val state by TestDiagnosticLog.state
    val exitIp by TestDiagnosticLog.exitIp
    var vpnPermissionRequestedAt by remember { mutableStateOf(0L) }
    var showAlwaysOnConflict by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(activity) == null) ProxyVpnService.test(activity)
        else {
            val status = readAlwaysOnVpnStatus(activity)
            val dismissedImmediately = System.currentTimeMillis() - vpnPermissionRequestedAt < 1_000
            if (status.hasOtherProvider || dismissedImmediately) {
                TestDiagnosticLog.fail(OTHER_ALWAYS_ON_VPN_MESSAGE)
                showAlwaysOnConflict = true
            } else {
                TestDiagnosticLog.fail("VPN access was not granted in the Android confirmation dialog")
            }
        }
    }
    val runTest = {
        val configStore = ConfigStore(activity)
        val error = configStore.globalConnectionSettings().applyTo(configStore.activeProfile().config)
            .connectionValidationError()
        if (error != null) {
            TestDiagnosticLog.fail(error)
        } else {
            TestDiagnosticLog.begin()
            val status = readAlwaysOnVpnStatus(activity)
            if (status.hasOtherProvider) {
                TestDiagnosticLog.fail(OTHER_ALWAYS_ON_VPN_MESSAGE)
                showAlwaysOnConflict = true
            } else {
                val intent = VpnService.prepare(activity)
                if (intent == null) {
                    ProxyVpnService.test(activity)
                } else {
                    vpnPermissionRequestedAt = System.currentTimeMillis()
                    permission.launch(intent)
                }
            }
        }
    }
    LaunchedEffect(autoStart) { if (autoStart) runTest() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection test") },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (state) {
                            TestState.IDLE -> "Ready"
                            TestState.RUNNING -> "Testing…"
                            TestState.SUCCEEDED -> "Test passed"
                            TestState.FAILED -> "Test failed"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    exitIp?.let { Text("Proxy exit IP: $it") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = runTest, enabled = state != TestState.RUNNING) { Text("Run again") }
                Button(onClick = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MegaProxy connection test", TestDiagnosticLog.entries.joinToString("\n")))
                }) { Text("Copy log") }
            }
            Text(
                "The test uses a temporary VPN when the main connection is inactive. Credentials and traffic content are not logged.",
                style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SelectionContainer {
                    Text(
                        TestDiagnosticLog.entries.joinToString("\n").ifEmpty { "No test events yet." },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
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
}
