package dev.megaproxy.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.megaproxy.app.data.ConfigStore
import dev.megaproxy.app.vpn.ProxyVpnService
import dev.megaproxy.app.vpn.TestDiagnosticLog
import dev.megaproxy.app.vpn.TestState

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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) ProxyVpnService.test(activity)
        else TestDiagnosticLog.fail("VPN permission is required for the connection test")
    }
    val runTest = {
        val error = ConfigStore(activity).load().connectionValidationError()
        if (error != null) {
            TestDiagnosticLog.fail(error)
        } else {
            TestDiagnosticLog.begin()
            val intent = VpnService.prepare(activity)
            if (intent == null) ProxyVpnService.test(activity) else permission.launch(intent)
        }
    }
    LaunchedEffect(autoStart) { if (autoStart) runTest() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Connection test") },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
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
}
