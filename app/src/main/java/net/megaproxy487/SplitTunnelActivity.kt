package net.megaproxy487

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

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
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var appSearch by remember { mutableStateOf("") }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnDeferredNotice by remember { mutableStateOf(false) }
    var deferChangesUntilNextConnection by remember { mutableStateOf(false) }

    fun updateSettings(updated: GlobalConnectionSettings) {
        val selectedPackagesOnly = settings.selectedPackages != updated.selectedPackages &&
            settings.copy(selectedPackages = updated.selectedPackages) == updated
        val affectsActiveRouting = !(settings.routeAllApps && updated.routeAllApps && selectedPackagesOnly)
        settings = updated
        store.saveGlobalConnectionSettings(updated)
        if (ProxyVpnService.isRunning && affectsActiveRouting && !deferChangesUntilNextConnection) {
            val alwaysOn = ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled
            if (alwaysOn) {
                deferChangesUntilNextConnection = true
                showAlwaysOnDeferredNotice = true
            } else {
                showReconnectPrompt = true
            }
        }
    }

    val apps = remember {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        activity.packageManager.queryIntentActivities(launcher, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(activity.packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    val visibleApps = remember(apps, appSearch) {
        val query = appSearch.trim()
        if (query.isEmpty()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split tunneling") },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (showReconnectPrompt || showAlwaysOnDeferredNotice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (showAlwaysOnDeferredNotice) "Always-on is active. Routing changes apply on the next connection."
                            else "Reconnect to apply routing changes to the active VPN.",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                showReconnectPrompt = false
                                showAlwaysOnDeferredNotice = false
                                deferChangesUntilNextConnection = true
                            }) { Text(if (showAlwaysOnDeferredNotice) "Dismiss" else "Next connection") }
                            if (showReconnectPrompt) {
                                TextButton(onClick = {
                                    showReconnectPrompt = false
                                    deferChangesUntilNextConnection = true
                                    ProxyVpnService.reconnect(activity)
                                }) { Text("Reconnect now") }
                            }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text("Routing mode", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(!settings.routeAllApps, { updateSettings(settings.copy(routeAllApps = false)) })
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text("Split tunneling")
                        Text("Only selected applications use the proxy.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(settings.routeAllApps, { updateSettings(settings.copy(routeAllApps = true)) })
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text("Global VPN")
                        Text("All applications use the proxy. This is the default mode.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("UDP and QUIC are blocked; DNS uses the profile's configured DoH endpoint.", style = MaterialTheme.typography.bodySmall) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Bypass local networks")
                        Text("Direct TCP access to private and link-local IP addresses.", style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(settings.bypassLocalNetworks, { updateSettings(settings.copy(bypassLocalNetworks = it)) })
                }
            }
            if (!settings.routeAllApps) {
                item { Text("Applications", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text("Search applications") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(visibleApps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(app.label, Modifier.weight(1f).padding(top = 12.dp))
                        Checkbox(app.packageName in settings.selectedPackages, { checked ->
                            updateSettings(settings.copy(selectedPackages = if (checked) {
                                settings.selectedPackages + app.packageName
                            } else {
                                settings.selectedPackages - app.packageName
                            }))
                        })
                    }
                }
            }
            item { Text("Changes are saved automatically and apply to every profile.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
