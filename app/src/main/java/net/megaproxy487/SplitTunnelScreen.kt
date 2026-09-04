package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.ui.theme.MegaProxyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppItem(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitTunnelScreen(activity: Activity, onBack: () -> Unit) {
    val store = remember { ConfigStore(activity) }
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var appSearch by remember { mutableStateOf("") }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnDeferredNotice by remember { mutableStateOf(false) }
    var deferChangesUntilNextConnection by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val alwaysOnActive = remember { ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled }

    fun updateSettings(updated: GlobalConnectionSettings) {
        val selectedPackagesOnly = settings.selectedPackages != updated.selectedPackages &&
            settings.copy(selectedPackages = updated.selectedPackages) == updated
        val affectsActiveRouting = !(settings.routeAllApps && updated.routeAllApps && selectedPackagesOnly)
        settings = updated
        coroutineScope.launch(ConfigIoDispatcher) {
            store.saveGlobalConnectionSettings(updated)
            if (ProxyVpnService.isRunning && affectsActiveRouting) store.markPendingReconnect()
        }
        if (ProxyVpnService.isRunning && affectsActiveRouting && !deferChangesUntilNextConnection) {
            if (alwaysOnActive) {
                deferChangesUntilNextConnection = true
                showAlwaysOnDeferredNotice = true
            } else {
                showReconnectPrompt = true
            }
        }
    }

    var apps by remember(activity) { mutableStateOf<List<AppItem>?>(null) }
    LaunchedEffect(activity) {
        apps = withContext(Dispatchers.IO) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            activity.packageManager.queryIntentActivities(launcher, 0)
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(activity.packageManager).toString()) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    val visibleApps = remember(apps, appSearch) {
        val query = appSearch.trim()
        val availableApps = apps.orEmpty()
        if (query.isEmpty()) availableApps else availableApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_tunneling)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        // Use one inset source. Applying safeDrawing in Scaffold and imePadding to
        // its child can double-apply or misreport the IME area on some OEM builds.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text(stringResource(R.string.routing_mode), style = MaterialTheme.typography.titleMedium) }
            item {
                Column(Modifier.selectableGroup()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                            selected = !settings.routeAllApps,
                            onClick = { updateSettings(settings.copy(routeAllApps = false)) },
                            role = Role.RadioButton,
                        ),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(!settings.routeAllApps, null)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.split_tunneling))
                            Text("Only selected applications use the proxy.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                            selected = settings.routeAllApps,
                            onClick = { updateSettings(settings.copy(routeAllApps = true)) },
                            role = Role.RadioButton,
                        ),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(settings.routeAllApps, null)
                        Column(Modifier.weight(1f)) {
                            Text("Global VPN")
                            Text("All applications use the proxy. This is the default mode.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("UDP and QUIC are blocked; DNS uses the profile's configured DoH endpoint.", style = MaterialTheme.typography.bodySmall) }
            item {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                        value = settings.bypassLocalNetworks,
                        onValueChange = { updateSettings(settings.copy(bypassLocalNetworks = it)) },
                        role = Role.Checkbox,
                    ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Bypass local networks")
                        Text("Direct TCP access to private and link-local IP addresses.", style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(settings.bypassLocalNetworks, null)
                }
            }
            if (!settings.routeAllApps) {
                item { Text(stringResource(R.string.applications), style = MaterialTheme.typography.titleMedium) }
                if (apps == null) item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
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
                    val checked = app.packageName in settings.selectedPackages
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                            value = checked,
                            onValueChange = { checkedValue ->
                            updateSettings(settings.copy(selectedPackages = if (checked) {
                                if (checkedValue) settings.selectedPackages else settings.selectedPackages - app.packageName
                            } else {
                                if (checkedValue) settings.selectedPackages + app.packageName else settings.selectedPackages
                            }))
                            },
                            role = Role.Checkbox,
                        ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(app.label, Modifier.weight(1f))
                        Checkbox(checked, null)
                    }
                }
            }
            item { Text(stringResource(R.string.changes_saved_automatically), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
