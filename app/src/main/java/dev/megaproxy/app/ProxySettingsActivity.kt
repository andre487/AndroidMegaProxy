package dev.megaproxy.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.megaproxy.app.data.ConfigStore
import dev.megaproxy.app.data.ProxyListParser
import dev.megaproxy.app.model.ProfileColors
import dev.megaproxy.app.model.ProxyProfile
import dev.megaproxy.app.model.ProfileSort
import dev.megaproxy.app.vpn.ProxyVpnService

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    var profiles by remember { mutableStateOf(store.sortedProfiles()) }
    var sort by remember { mutableStateOf(store.profileSort()) }
    var ascending by remember { mutableStateOf(store.isProfileSortAscending()) }
    var sortExpanded by remember { mutableStateOf(false) }
    var alwaysOnExpanded by remember { mutableStateOf(false) }
    var deleteProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var showReconnectWarning by remember { mutableStateOf(false) }
    var importedProfileIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var skippedNonHttps by remember { mutableStateOf(0) }
    var showImportFilterNotice by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refresh() { profiles = store.sortedProfiles() }
    fun edit(profile: ProxyProfile) {
        activity.startActivity(Intent(activity, ProfileEditorActivity::class.java).apply {
            putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile.id)
        })
    }
    fun openImportedRouting(individual: Boolean) {
        activity.startActivity(Intent(activity, SplitTunnelActivity::class.java).apply {
            putStringArrayListExtra(SplitTunnelActivity.EXTRA_PROFILE_IDS, ArrayList(importedProfileIds))
            putExtra(SplitTunnelActivity.EXTRA_CONFIGURE_INDIVIDUALLY, individual)
        })
        importedProfileIds = emptyList()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected file")
                ProxyListParser.parse(text).getOrThrow()
            }.onSuccess { imported ->
                val added = store.importProfiles(imported.proxies)
                refresh()
                importedProfileIds = added.map { it.id }
                skippedNonHttps = imported.skippedNonHttps
                showImportFilterNotice = imported.skippedNonHttps > 0
                importError = null
            }.onFailure { importError = it.message ?: "Could not import the proxy list" }
        }
    }

    Scaffold(
        topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(onClick = { importDocument.launch(arrayOf("text/plain", "text/*")) }) {
                    Text("Import")
                }
            },
        )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val profile = store.addProfile()
                refresh()
                edit(profile)
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add profile")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Always-on VPN", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            item {
                ExposedDropdownMenuBox(alwaysOnExpanded, { alwaysOnExpanded = it }) {
                    OutlinedTextField(
                        store.alwaysOnProfile().displayNameWithFlag, {}, readOnly = true,
                        label = { Text("Profile for Always-on VPN") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(alwaysOnExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    DropdownMenu(alwaysOnExpanded, { alwaysOnExpanded = false }) {
                        profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.displayNameWithFlag) }, onClick = {
                            store.setAlwaysOnProfile(profile.id)
                            alwaysOnExpanded = false
                            if (ProxyVpnService.isAlwaysOnMode && ProxyVpnService.isRunning) showReconnectWarning = true
                        }) }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Android Always-on VPN settings") }
            }
            item {
                OutlinedButton(onClick = {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    })
                }, modifier = Modifier.fillMaxWidth()) { Text("Battery settings") }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { Text("Profiles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(sortExpanded, { sortExpanded = it }, Modifier.weight(1f)) {
                        OutlinedTextField(
                            sort.title, {}, readOnly = true, label = { Text("Sort by") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sortExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        DropdownMenu(sortExpanded, { sortExpanded = false }) {
                            ProfileSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                sort = option
                                store.setProfileSort(sort, ascending)
                                refresh()
                                sortExpanded = false
                            }) }
                        }
                    }
                    OutlinedButton(onClick = {
                        ascending = !ascending
                        store.setProfileSort(sort, ascending)
                        refresh()
                    }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (ascending) "Ascending" else "Descending")
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onConfigure = { edit(profile) },
                    onClone = { store.cloneProfile(profile.id); refresh() },
                    onDelete = { deleteProfile = profile },
                )
            }
            importError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { Text("Changes are saved automatically.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text("Delete ${profile.displayName}?") },
            text = { Text("The profile and its encrypted credentials will be removed.") },
            confirmButton = { TextButton(onClick = {
                store.deleteProfile(profile.id)
                refresh()
                deleteProfile = null
            }, enabled = profiles.size > 1) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Cancel") } },
        )
    }
    if (showReconnectWarning) {
        AlertDialog(
            onDismissRequest = { showReconnectWarning = false },
            title = { Text("Reconnect required") },
            text = { Text("The new Always-on profile will be used only after the VPN reconnects.") },
            confirmButton = { TextButton(onClick = { showReconnectWarning = false }) { Text("OK") } },
        )
    }
    if (showImportFilterNotice) {
        AlertDialog(
            onDismissRequest = { showImportFilterNotice = false },
            title = { Text("Only HTTPS proxies were imported") },
            text = { Text("Imported ${importedProfileIds.size} HTTPS proxy profile(s). Skipped $skippedNonHttps non-HTTPS URL(s).") },
            confirmButton = { TextButton(onClick = { showImportFilterNotice = false }) { Text("Continue") } },
        )
    }
    if (importedProfileIds.isNotEmpty() && !showImportFilterNotice) {
        AlertDialog(
            onDismissRequest = { importedProfileIds = emptyList() },
            title = { Text("Configure imported profiles") },
            text = { Text("Choose whether routing settings should be shared by all imported profiles or configured one profile at a time.") },
            confirmButton = { TextButton(onClick = { openImportedRouting(false) }) { Text("Same for all") } },
            dismissButton = { TextButton(onClick = { openImportedRouting(true) }) { Text("One by one") } },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProxyProfile,
    onConfigure: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    val background = Color(ProfileColors.argb[Math.floorMod(profile.colorIndex, ProfileColors.argb.size)])
    val foreground = if (background.luminance() > 0.45f) Color.Black else Color.White
    Card(
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onConfigure),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.flagEmoji.isNotEmpty()) {
                    Surface(
                        color = Color.White, contentColor = Color.Black,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)),
                    ) { Text(profile.flagEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
                }
                Column {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${profile.config.host}:${profile.config.port}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onConfigure) { Text("Configure", color = foreground) }
                TextButton(onClick = onClone) { Text("Clone", color = foreground) }
                TextButton(onClick = onDelete) { Text("Delete", color = foreground) }
            }
        }
    }
}
