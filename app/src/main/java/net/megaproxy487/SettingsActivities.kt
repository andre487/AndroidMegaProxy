package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.model.Ja3Spec
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.SshProfile
import net.megaproxy487.model.SshAuthMode
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.VpnRuntimeState
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.ui.theme.MegaProxyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxySettingsActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { SettingsHomeScreen(this) } }
    }
}

class AlwaysOnSettingsActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { AlwaysOnSettingsScreen(this) } }
    }
}

class TlsFingerprintActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { TlsFingerprintScreen(this) } }
    }
}

class FailoverSettingsActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { FailoverSettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(activity: Activity) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember { activity.getSystemService(PowerManager::class.java) }
    var batteryOptimizationDisabled by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(activity.packageName))
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var supportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(activity.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(activity, stringResource(R.string.settings)) {
        Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium)
        SettingsButton(stringResource(R.string.profiles), stringResource(R.string.profiles_description)) { activity.startActivity(Intent(activity, ProfilesActivity::class.java)) }
        SettingsButton(stringResource(R.string.always_on_vpn), stringResource(R.string.always_on_description)) { activity.startActivity(Intent(activity, AlwaysOnSettingsActivity::class.java)) }
        SettingsButton(stringResource(R.string.fingerprints), stringResource(R.string.fingerprints_description)) { activity.startActivity(Intent(activity, TlsFingerprintActivity::class.java)) }
        SettingsButton(stringResource(R.string.split_tunneling), stringResource(R.string.split_tunneling_description)) { activity.startActivity(Intent(activity, SplitTunnelActivity::class.java)) }
        SettingsButton(stringResource(R.string.failover), stringResource(R.string.failover_description)) { activity.startActivity(Intent(activity, FailoverSettingsActivity::class.java)) }
        Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsButton(stringResource(R.string.visibility), stringResource(R.string.visibility_description)) { activity.startActivity(Intent(activity, VisibilityActivity::class.java)) }
        if (!batteryOptimizationDisabled) {
            SettingsButton(stringResource(R.string.battery_settings), stringResource(R.string.battery_settings_description)) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                })
            }
        }
        SettingsButton(stringResource(R.string.diagnostic_log), stringResource(R.string.diagnostic_log_description)) { activity.startActivity(Intent(activity, DiagnosticLogActivity::class.java)) }
        Text(stringResource(R.string.appearance_and_language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsButton(
            stringResource(R.string.language),
            "${stringResource(R.string.language_description)} · ${if (AppLanguageManager.current(activity) == AppLanguage.RUSSIAN) stringResource(R.string.language_russian) else stringResource(R.string.language_english)}",
        ) { showLanguageDialog = true }
        Text(stringResource(R.string.support), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsButton(stringResource(R.string.feedback), stringResource(R.string.feedback_description)) {
            scope.launch {
                runCatching {
                    val status = readAlwaysOnVpnStatus(activity)
                    val intent = withContext(Dispatchers.IO) {
                        FeedbackEmail.createIntent(
                            activity,
                            VpnRuntimeState.connection.value,
                            VpnRuntimeState.alwaysOn.value || status.enabled,
                            VpnRuntimeState.lockdown.value || status.lockdown,
                        )
                    }
                    activity.startActivity(intent)
                }.onFailure { supportError = it.message ?: "Could not open an email client" }
            }
        }
        SettingsButton(stringResource(R.string.github_issues), stringResource(R.string.github_issues_description)) {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andre487/AndroidMegaProxy/issues")))
            }.onFailure { supportError = activity.getString(R.string.no_browser) }
        }
        supportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(R.string.version_and_commit, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_HASH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }

    if (showLanguageDialog) {
        val selected = AppLanguageManager.current(activity)
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        val label = if (language == AppLanguage.RUSSIAN) {
                            stringResource(R.string.language_russian)
                        } else {
                            stringResource(R.string.language_english)
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                                value = selected == language,
                                role = Role.RadioButton,
                                onValueChange = {
                                    AppLanguageManager.set(activity, language)
                                    showLanguageDialog = false
                                    activity.recreate()
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected == language, null)
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailoverSettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var expanded by remember { mutableStateOf(false) }
    var pendingAll by remember { mutableStateOf(false) }
    val alwaysOn = remember { ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled }
    fun save(mode: FailoverMode = settings.failoverMode, ids: List<String> = settings.failoverProfileIds) {
        settings = settings.copy(failoverMode = mode, failoverProfileIds = ids)
        val snapshot = settings
        scope.launch(ConfigIoDispatcher) { store.saveGlobalConnectionSettings(snapshot) }
    }
    SettingsScaffold(activity, "Failover") {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(settings.failoverMode.title, {}, readOnly = true, label = { Text("Failover mode") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            DropdownMenu(expanded, { expanded = false }) {
                FailoverMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.title) }, onClick = {
                    expanded = false
                    if (mode == FailoverMode.ALL) pendingAll = true else save(mode = mode)
                }) }
            }
        }
        Text("Failover is only triggered after repeated timeout, reset or silent-drop signals. Authentication, host-key, certificate and configuration errors never trigger it.", style = MaterialTheme.typography.bodySmall)
        if (settings.failoverMode == FailoverMode.SELECTED) {
            Text("Fallback profiles", style = MaterialTheme.typography.titleMedium)
            Text("Profiles are tried in the same order as the Profiles screen.", style = MaterialTheme.typography.bodySmall)
            store.sortedProfiles().forEach { profile ->
                val checked = profile.id in settings.failoverProfileIds
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                        value = checked,
                        onValueChange = { checkedValue ->
                        val ids = if (checkedValue) settings.failoverProfileIds + profile.id else settings.failoverProfileIds - profile.id
                        save(ids = ids)
                        },
                        role = Role.Checkbox,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, null)
                    Text(profile.displayNameWithFlag)
                }
            }
            val usableFallbacks = store.sortedProfiles().count { it.id in settings.failoverProfileIds }
            if (usableFallbacks < 2) {
                Text(
                    "Select at least two profiles. With fewer profiles, failover may have nowhere to switch.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (settings.failoverMode != FailoverMode.DISABLED) {
            Text("Warning: switching profiles may change the apparent country and public exit IP.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (alwaysOn) Text(
                if (settings.failoverMode == FailoverMode.ALL)
                    "Always-on VPN remains active during automatic switching. Every switch is reported in the persistent notification and on the main screen."
                else
                    "Always-on VPN remains active during automatic switching. The persistent notification and the main screen show the profile actually in use.",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (pendingAll) AlertDialog(
        onDismissRequest = { pendingAll = false },
        title = { Text("Enable global failover?") },
        text = { Text("MegaProxy will automatically try any configured profile when blocking is suspected. This can change your location and public exit IP. Individual switches will not require confirmation.") },
        confirmButton = { TextButton(onClick = { pendingAll = false; save(mode = FailoverMode.ALL) }) { Text("Enable") } },
        dismissButton = { TextButton(onClick = { pendingAll = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlwaysOnSettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(store.alwaysOnProfile()) }
    val alwaysOnActive = remember { ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled }
    SettingsScaffold(activity, "Always-on VPN") {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                selected.displayNameWithFlag, {}, readOnly = true,
                label = { Text("Profile for Always-on VPN") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                store.sortedProfiles().forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.displayNameWithFlag) }, onClick = {
                        selected = profile
                        scope.launch(ConfigIoDispatcher) { store.setAlwaysOnProfile(profile.id) }
                        expanded = false
                        if (alwaysOnActive && ProxyVpnService.isRunning) {
                            ProxyVpnService.switchProfile(activity, profile.id, true)
                        }
                    })
                }
            }
        }
        Text("This profile is used when Android starts MegaProxy as an Always-on VPN.", style = MaterialTheme.typography.bodySmall)
        if (alwaysOnActive) {
            Text("Always-on is active. Selecting another profile reconnects immediately.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        SettingsButton("Open Android Always-on VPN settings") {
            activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TlsFingerprintScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var expanded by remember { mutableStateOf(false) }
    var sshExpanded by remember { mutableStateOf(false) }
    var sshAuthExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnNotice by remember { mutableStateOf(false) }
    var deferred by remember { mutableStateOf(false) }

    fun saveSettings(updated: net.megaproxy487.model.GlobalConnectionSettings) {
        settings = updated
        val snapshot = settings
        scope.launch(ConfigIoDispatcher) {
            store.saveGlobalConnectionSettings(snapshot)
            if (ProxyVpnService.isRunning) store.markPendingReconnect()
        }
        if (ProxyVpnService.isRunning && !deferred) {
            if (ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled) {
                deferred = true; showAlwaysOnNotice = true
            } else showReconnectPrompt = true
        }
    }

    fun save(
        profile: TlsProfile = settings.tlsProfile,
        customJa3: String = settings.customJa3,
        sshProfile: SshProfile = settings.sshProfile,
    ) {
        saveSettings(settings.copy(tlsProfile = profile, customJa3 = customJa3, sshProfile = sshProfile))
        error = if (profile == TlsProfile.CUSTOM && Ja3Spec.parse(customJa3) == null) {
            "JA3 must contain five fields: version,ciphers,extensions,groups,points"
        } else null
    }

    SettingsScaffold(activity, "Fingerprints") {
        Text("HTTPS fingerprint", style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                settings.tlsProfile.title, {}, readOnly = true,
                label = { Text("HTTPS TLS / JA3 profile") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                TlsProfile.entries.filter { it.available }.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.title) }, onClick = { save(profile = profile); expanded = false })
                }
            }
        }
        Text("Default currently uses the Chrome Android fingerprint and may change in a future MegaProxy update.", style = MaterialTheme.typography.bodySmall)
        if (settings.tlsProfile == TlsProfile.CUSTOM) {
            OutlinedTextField(
                settings.customJa3,
                { if (it.length <= 8 * 1024) save(customJa3 = it) },
                label = { Text("JA3: version,ciphers,extensions,groups,points") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("SSH fingerprint", style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(sshExpanded, { sshExpanded = it }) {
            OutlinedTextField(
                settings.sshProfile.title, {}, readOnly = true,
                label = { Text("SSH client profile") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(sshExpanded, { sshExpanded = false }) {
                SshProfile.entries.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.title) }, onClick = {
                        save(sshProfile = profile)
                        sshExpanded = false
                    })
                }
            }
        }
        ExposedDropdownMenuBox(sshAuthExpanded, { sshAuthExpanded = it }) {
            OutlinedTextField(
                settings.sshAuthMode.title, {}, readOnly = true,
                label = { Text("SSH authentication") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshAuthExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(sshAuthExpanded, { sshAuthExpanded = false }) {
                SshAuthMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.title) }, onClick = {
                    saveSettings(settings.copy(sshAuthMode = mode))
                    sshAuthExpanded = false
                }) }
            }
        }
        IntegerSettingField(settings.sshKeepaliveSeconds, 0..3600, "SSH keepalive, seconds (0 = disabled)") {
            saveSettings(settings.copy(sshKeepaliveSeconds = it))
        }
        IntegerSettingField(settings.sshMaxChannels, 1..256, "Maximum parallel SSH channels") {
            saveSettings(settings.copy(sshMaxChannels = it))
        }
        IntegerSettingField(settings.sshRotationMinutes, 0..1440, "Rotate SSH session after minutes (0 = disabled)") {
            saveSettings(settings.copy(sshRotationMinutes = it))
        }
        IntegerSettingField(settings.sshRotationMb, 0..10240, "Rotate SSH session after MB (0 = disabled)") {
            saveSettings(settings.copy(sshRotationMb = it))
        }
        Text("The SSH profile controls the client banner and preferred KEX, cipher and MAC ordering.", style = MaterialTheme.typography.bodySmall)
        if (showReconnectPrompt) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Reconnect to apply these changes to the active VPN.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showReconnectPrompt = false; deferred = true }) { Text("Next connection") }
                        TextButton(onClick = {
                            showReconnectPrompt = false
                            deferred = true
                            ProxyVpnService.reconnect(activity)
                        }) { Text("Reconnect now") }
                    }
                }
            }
        }
        if (showAlwaysOnNotice) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Always-on is active. Changes apply on the next connection.", modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAlwaysOnNotice = false }) { Text("Dismiss") }
                }
            }
        }
        Text("Changes are saved automatically and apply to every profile.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IntegerSettingField(initialValue: Int, range: IntRange, label: String, onValidValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(initialValue.toString()) }
    val parsed = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            if (value.length <= range.last.toString().length && value.all(Char::isDigit)) {
                text = value
                value.toIntOrNull()?.takeIf { it in range }?.let(onValidValue)
            }
        },
        label = { Text(label) },
        supportingText = if (text.isNotEmpty() && parsed !in range) {
            { Text("Allowed range: ${range.first}–${range.last}") }
        } else null,
        isError = text.isNotEmpty() && parsed !in range,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(activity: Activity, title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsButton(label: String, description: String? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
