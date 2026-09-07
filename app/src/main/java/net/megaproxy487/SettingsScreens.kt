package net.megaproxy487

import androidx.compose.foundation.shape.RoundedCornerShape

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import net.megaproxy487.uiStringResource as stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHomeScreen(activity: Activity, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember { activity.getSystemService(PowerManager::class.java) }
    var batteryOptimizationDisabled by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(activity.packageName))
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTrafficUnitsDialog by remember { mutableStateOf(false) }
    var trafficUnitSystem by remember { mutableStateOf(TrafficUnitPreferences.current(activity)) }
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

    SettingsScaffold(onBack, stringResource(R.string.settings)) {
        Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium)
        connectionSettingsDestinations.forEach { destination ->
            SettingsButton(stringResource(destination.titleRes), stringResource(destination.descriptionRes)) {
                onNavigate(destination.route)
            }
        }
        Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        visibilitySettingsDestination.let { destination ->
            SettingsButton(stringResource(destination.titleRes), stringResource(destination.descriptionRes)) {
                onNavigate(destination.route)
            }
        }
        if (!batteryOptimizationDisabled) {
            SettingsButton(stringResource(R.string.battery_settings), stringResource(R.string.battery_settings_description)) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                })
            }
        }
        diagnosticLogSettingsDestination.let { destination ->
            SettingsButton(stringResource(destination.titleRes), stringResource(destination.descriptionRes)) {
                onNavigate(destination.route)
            }
        }
        Text(stringResource(R.string.appearance_and_language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsButton(
            stringResource(R.string.language),
            "${stringResource(R.string.language_description)} · ${if (AppLanguageManager.current(activity) == AppLanguage.RUSSIAN) stringResource(R.string.language_russian) else stringResource(R.string.language_english)}",
        ) { showLanguageDialog = true }
        SettingsButton(
            stringResource(R.string.traffic_units),
            stringResource(
                R.string.traffic_units_description,
                stringResource(
                    if (trafficUnitSystem == TrafficUnitSystem.IEC) R.string.traffic_units_iec
                    else R.string.traffic_units_si,
                ),
            ),
        ) { showTrafficUnitsDialog = true }
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
                }.onFailure { supportError = activity.uiText(R.string.could_not_open_email) }
            }
        }
        SettingsButton(stringResource(R.string.github_issues), stringResource(R.string.github_issues_description)) {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andre487/AndroidMegaProxy/issues")))
            }.onFailure { supportError = activity.uiText(R.string.no_browser) }
        }
        supportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showLanguageDialog) {
        val selected = AppLanguageManager.current(activity)
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { DialogTitle(stringResource(R.string.language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { showLanguageDialog = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showTrafficUnitsDialog) {
        AlertDialog(
            onDismissRequest = { showTrafficUnitsDialog = false },
            title = { DialogTitle(stringResource(R.string.traffic_units)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrafficUnitSystem.entries.forEach { unitSystem ->
                        val label = stringResource(
                            if (unitSystem == TrafficUnitSystem.IEC) R.string.traffic_units_iec
                            else R.string.traffic_units_si,
                        )
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                                value = trafficUnitSystem == unitSystem,
                                role = Role.RadioButton,
                                onValueChange = {
                                    trafficUnitSystem = unitSystem
                                    TrafficUnitPreferences.set(activity, unitSystem)
                                    showTrafficUnitsDialog = false
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(trafficUnitSystem == unitSystem, null)
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = { showTrafficUnitsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FailoverSettingsScreen(activity: Activity, onBack: () -> Unit) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var expanded by remember { mutableStateOf(false) }
    var pendingAll by remember { mutableStateOf(false) }
    val alwaysOn = remember { ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled }
    fun save(mode: FailoverMode = settings.failoverMode, ids: List<String> = settings.failoverProfileIds) {
        settings = settings.copy(failoverMode = mode, failoverProfileIds = ids)
        val snapshot = settings
        ConfigWrites.submit("global") { store.saveGlobalConnectionSettings(snapshot) }
    }
    SettingsScaffold(onBack, stringResource(R.string.failover)) {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(activity.uiText(settings.failoverMode.titleRes), {}, readOnly = true, label = { FieldLabel(stringResource(R.string.failover_mode)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            DropdownMenu(expanded, { expanded = false }) {
                FailoverMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(activity.uiText(mode.titleRes)) }, onClick = {
                    expanded = false
                    if (mode == FailoverMode.ALL) pendingAll = true else save(mode = mode)
                }) }
            }
        }
        Text(stringResource(R.string.failover_trigger_description), style = MaterialTheme.typography.bodySmall)
        if (settings.failoverMode == FailoverMode.SELECTED) {
            Text(stringResource(R.string.fallback_profiles), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.fallback_profiles_order), style = MaterialTheme.typography.bodySmall)
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
                    Text(profile.localizedNameWithFlag(activity), modifier = Modifier.weight(1f))
                }
            }
            val usableFallbacks = store.sortedProfiles().count { it.id in settings.failoverProfileIds }
            if (usableFallbacks < 2) {
                Text(
                    stringResource(R.string.failover_profiles_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (settings.failoverMode != FailoverMode.DISABLED) {
            Text(stringResource(R.string.failover_location_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (alwaysOn) Text(
                if (settings.failoverMode == FailoverMode.ALL)
                    stringResource(R.string.always_on_all_failover_status)
                else
                    stringResource(R.string.always_on_selected_failover_status),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (pendingAll) AlertDialog(
        onDismissRequest = { pendingAll = false },
        title = { DialogTitle(stringResource(R.string.enable_global_failover_title)) },
        text = { ScrollableDialogText(stringResource(R.string.enable_global_failover_message)) },
        confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { pendingAll = false; save(mode = FailoverMode.ALL) }) { Text(stringResource(R.string.enable)) } },
        dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { pendingAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlwaysOnSettingsScreen(activity: Activity, onBack: () -> Unit) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(store.alwaysOnProfile()) }
    val alwaysOnActive = remember { ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled }
    SettingsScaffold(onBack, stringResource(R.string.always_on_vpn)) {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                selected.localizedNameWithFlag(activity), {}, readOnly = true,
                label = { FieldLabel(stringResource(R.string.always_on_profile)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                store.sortedProfiles().forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.localizedNameWithFlag(activity)) }, onClick = {
                        selected = profile
                        ConfigWrites.submit("always-on") { store.setAlwaysOnProfile(profile.id) }
                        expanded = false
                        if (alwaysOnActive && ProxyVpnService.isRunning) {
                            ProxyVpnService.switchProfile(activity, profile.id, true)
                        }
                    })
                }
            }
        }
        Text(stringResource(R.string.always_on_profile_description), style = MaterialTheme.typography.bodySmall)
        if (alwaysOnActive) {
            Text(stringResource(R.string.always_on_profile_reconnect), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        SettingsButton(stringResource(R.string.open_always_on_settings)) {
            activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TlsFingerprintScreen(activity: Activity, onBack: () -> Unit) {
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

    val invalidFields = remember { mutableStateMapOf<String, Boolean>() }
    val writeStatus by ConfigWrites.status.collectAsState()
    fun saveSettings(updated: net.megaproxy487.model.GlobalConnectionSettings) {
        settings = updated
        val snapshot = settings
        ConfigWrites.submit("global") {
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
            activity.uiText(R.string.invalid_ja3)
        } else null
    }

    SettingsScaffold(onBack, stringResource(R.string.fingerprints)) {
        Text(stringResource(R.string.https_fingerprint), style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                activity.uiText(settings.tlsProfile.titleRes), {}, readOnly = true,
                label = { FieldLabel(stringResource(R.string.https_tls_ja3_profile)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                TlsProfile.entries.filter { it.available }.forEach { profile ->
                    DropdownMenuItem(text = { Text(activity.uiText(profile.titleRes)) }, onClick = { save(profile = profile); expanded = false })
                }
            }
        }
        Text(stringResource(R.string.default_fingerprint_description), style = MaterialTheme.typography.bodySmall)
        if (settings.tlsProfile == TlsProfile.CUSTOM) {
            OutlinedTextField(
                settings.customJa3,
                { if (it.length <= 8 * 1024) save(customJa3 = it) },
                label = { FieldLabel(stringResource(R.string.ja3_format)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.ssh_fingerprint), style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(sshExpanded, { sshExpanded = it }) {
            OutlinedTextField(
                activity.uiText(settings.sshProfile.titleRes), {}, readOnly = true,
                label = { FieldLabel(stringResource(R.string.ssh_client_profile)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(sshExpanded, { sshExpanded = false }) {
                SshProfile.entries.forEach { profile ->
                    DropdownMenuItem(text = { Text(activity.uiText(profile.titleRes)) }, onClick = {
                        save(sshProfile = profile)
                        sshExpanded = false
                    })
                }
            }
        }
        ExposedDropdownMenuBox(sshAuthExpanded, { sshAuthExpanded = it }) {
            OutlinedTextField(
                activity.uiText(settings.sshAuthMode.titleRes), {}, readOnly = true,
                label = { FieldLabel(stringResource(R.string.ssh_authentication)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshAuthExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            DropdownMenu(sshAuthExpanded, { sshAuthExpanded = false }) {
                SshAuthMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(activity.uiText(mode.titleRes)) }, onClick = {
                    saveSettings(settings.copy(sshAuthMode = mode))
                    sshAuthExpanded = false
                }) }
            }
        }
        IntegerSettingField(settings.sshKeepaliveSeconds, 0..3600, stringResource(R.string.ssh_keepalive), onValidityChange = { invalidFields["sshKeepaliveSeconds"] = !it }) {
            saveSettings(settings.copy(sshKeepaliveSeconds = it))
        }
        IntegerSettingField(settings.sshMaxChannels, 1..256, stringResource(R.string.ssh_max_channels), onValidityChange = { invalidFields["sshMaxChannels"] = !it }) {
            saveSettings(settings.copy(sshMaxChannels = it))
        }
        IntegerSettingField(settings.sshRotationMinutes, 0..1440, stringResource(R.string.ssh_rotation_minutes), onValidityChange = { invalidFields["sshRotationMinutes"] = !it }) {
            saveSettings(settings.copy(sshRotationMinutes = it))
        }
        IntegerSettingField(settings.sshRotationMb, 0..10240, stringResource(R.string.ssh_rotation_mb), onValidityChange = { invalidFields["sshRotationMb"] = !it }) {
            saveSettings(settings.copy(sshRotationMb = it))
        }
        Text(stringResource(R.string.ssh_profile_description), style = MaterialTheme.typography.bodySmall)
        if (showReconnectPrompt) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.reconnect_apply_changes))
                    WrappingActions() {
                        TextButton(shape = RoundedCornerShape(12.dp), onClick = { showReconnectPrompt = false; deferred = true }) { Text(stringResource(R.string.next_connection)) }
                        TextButton(shape = RoundedCornerShape(12.dp), enabled = error == null && invalidFields.values.none { it } && writeStatus.pending == 0 && !writeStatus.failed, onClick = {
                            showReconnectPrompt = false
                            deferred = true
                            ProxyVpnService.reconnect(activity)
                        }) { Text(stringResource(R.string.reconnect_now)) }
                    }
                }
            }
        }
        if (showAlwaysOnNotice) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.always_on_changes_next_connection))
                    TextButton(shape = RoundedCornerShape(12.dp), onClick = { showAlwaysOnNotice = false }) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
        Text(stringResource(R.string.global_changes_saved), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IntegerSettingField(initialValue: Int, range: IntRange, label: String,
    onValidityChange: (Boolean) -> Unit = {}, onValidValue: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialValue.toString()) }
    SideEffect { onValidityChange(validIntegerInput(text, range) != null) }
    IntegerInputField(text, { text = it }, range, label, onValidValue, Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(onBack: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                content = { SaveStatusBanner(); content() },
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
