package net.megaproxy487

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

class SplitTunnelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SplitTunnelScreen(this) } }
    }

    companion object {
        const val EXTRA_PROFILE_IDS = "profile_ids"
        const val EXTRA_CONFIGURE_INDIVIDUALLY = "configure_individually"
    }
}

private data class AppItem(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelScreen(activity: SplitTunnelActivity) {
    val store = remember { ConfigStore(activity) }
    val importedIds = remember {
        activity.intent.getStringArrayListExtra(SplitTunnelActivity.EXTRA_PROFILE_IDS).orEmpty()
    }
    val configureIndividually = remember {
        activity.intent.getBooleanExtra(SplitTunnelActivity.EXTRA_CONFIGURE_INDIVIDUALLY, false)
    }
    val targetProfiles = remember(importedIds) {
        if (importedIds.isEmpty()) listOf(store.activeProfile())
        else importedIds.mapNotNull(store::profile)
    }
    var currentIndex by remember { mutableStateOf(0) }
    var currentProfile by remember { mutableStateOf(targetProfiles.first()) }
    var config by remember { mutableStateOf(currentProfile.config) }
    var appSearch by remember { mutableStateOf("") }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnDeferredNotice by remember { mutableStateOf(false) }
    var deferChangesUntilNextConnection by remember { mutableStateOf(false) }

    fun updateConfig(updated: net.megaproxy487.model.ProxyConfig) {
        config = updated
        val profilesToUpdate = if (configureIndividually) listOf(currentProfile) else targetProfiles
        profilesToUpdate.forEach { store.saveProfile(it.copy(config = updated)) }
        currentProfile = currentProfile.copy(config = updated)
        val affectsConnectedProfile = profilesToUpdate.any { it.id == store.connectionProfile().id }
        if (ProxyVpnService.isRunning && affectsConnectedProfile && !deferChangesUntilNextConnection) {
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
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    val visibleApps = remember(apps, appSearch) {
        val query = appSearch.trim()
        if (query.isEmpty()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }
    fun finishCurrentProfile() {
        if (configureIndividually && currentIndex < targetProfiles.lastIndex) {
            currentIndex += 1
            currentProfile = targetProfiles[currentIndex]
            config = currentProfile.config
            appSearch = ""
        } else {
            store.setActiveProfile(targetProfiles.first().id)
            activity.finish()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (importedIds.isEmpty()) "Routing"
                        else if (configureIndividually) "Routing: ${currentProfile.displayName}"
                        else "Routing for ${targetProfiles.size} profiles"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (importedIds.isNotEmpty()) {
                Button(
                    onClick = ::finishCurrentProfile,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(if (configureIndividually && currentIndex < targetProfiles.lastIndex) "Next profile" else "Done")
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
            item { Row(Modifier.fillMaxWidth()) {
                RadioButton(!config.routeAllApps, {
                    updateConfig(config.copy(routeAllApps = false))
                })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("Split tunneling")
                    Text("Only selected applications use the proxy (recommended).", style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Row(Modifier.fillMaxWidth()) {
                RadioButton(config.routeAllApps, {
                    updateConfig(config.copy(routeAllApps = true))
                })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("Global VPN")
                    Text("All applications use the proxy. This mode is opt-in.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Text("UDP and QUIC are blocked; DNS uses the configured DoH endpoint.", style = MaterialTheme.typography.bodySmall) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Enable IPv6 destinations")
                    Text(
                        "Disabled by default for proxies that cannot CONNECT to IPv6 addresses.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(config.allowIpv6, {
                    updateConfig(config.copy(allowIpv6 = it))
                })
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Bypass local networks")
                    Text(
                        "Direct TCP access to private and link-local IP addresses. DNS still uses DoH; local UDP and mDNS remain blocked.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(config.bypassLocalNetworks, {
                    updateConfig(config.copy(bypassLocalNetworks = it))
                })
            } }
            if (!config.routeAllApps) {
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
                        Checkbox(app.packageName in config.selectedPackages, { checked ->
                            updateConfig(config.copy(selectedPackages = if (checked) config.selectedPackages + app.packageName else config.selectedPackages - app.packageName))
                        })
                    }
                }
            }
            item { Text("Changes are saved automatically.", style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showReconnectPrompt) {
        AlertDialog(
            onDismissRequest = {
                showReconnectPrompt = false
                deferChangesUntilNextConnection = true
            },
            title = { Text("Apply routing changes?") },
            text = {
                Text("The VPN is currently connected. Reconnect now to apply the new split-tunneling settings, or apply them the next time it connects.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showReconnectPrompt = false
                    ProxyVpnService.reconnect(activity)
                }) { Text("Reconnect now") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReconnectPrompt = false
                    deferChangesUntilNextConnection = true
                }) { Text("Next connection") }
            },
        )
    }

    if (showAlwaysOnDeferredNotice) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnDeferredNotice = false },
            title = { Text("Routing settings saved") },
            text = { Text("Always-on VPN is managed by Android. The new split-tunneling settings will be applied the next time the VPN connects.") },
            confirmButton = {
                TextButton(onClick = { showAlwaysOnDeferredNotice = false }) { Text("OK") }
            },
        )
    }
}
