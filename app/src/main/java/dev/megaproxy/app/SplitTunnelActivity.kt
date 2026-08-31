package dev.megaproxy.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.megaproxy.app.data.ConfigStore

class SplitTunnelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SplitTunnelScreen(this) } }
    }
}

private data class AppItem(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelScreen(activity: SplitTunnelActivity) {
    val store = remember { ConfigStore(activity) }
    var config by remember { mutableStateOf(store.load()) }
    val apps = remember {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        activity.packageManager.queryIntentActivities(launcher, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(activity.packageManager).toString()) }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Split tunneling") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Only selected applications use the proxy. UDP and QUIC are blocked; DNS uses the configured DoH endpoint.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Enable IPv6 destinations")
                    Text(
                        "Disabled by default for proxies that cannot CONNECT to IPv6 addresses.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(config.allowIpv6, { config = config.copy(allowIpv6 = it) })
            }
            apps.forEach { app ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(app.label, Modifier.weight(1f).padding(top = 12.dp))
                    Checkbox(app.packageName in config.selectedPackages, { checked ->
                        config = config.copy(selectedPackages = if (checked) config.selectedPackages + app.packageName else config.selectedPackages - app.packageName)
                    })
                }
            }
            Button(onClick = { store.save(config); activity.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
