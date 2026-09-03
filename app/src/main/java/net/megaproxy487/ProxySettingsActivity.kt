package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigExportFormat
import net.megaproxy487.data.ConfigTransfer
import net.megaproxy487.data.FoxyProxyParser
import net.megaproxy487.data.PortableConfiguration
import net.megaproxy487.data.ProxyListParser
import net.megaproxy487.data.SuperProxyParser
import net.megaproxy487.data.readConfigText
import net.megaproxy487.model.ProfileColors
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.ui.theme.MegaProxyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfilesActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { SettingsScreen(this) } }
    }
}

private data class MissingProfilesReview(
    val profiles: List<ProxyProfile>,
    val importSummary: String,
)

private data class ProfileOptionsReview(
    val profile: ProxyProfile,
    val changedGroups: List<Int>,
)

private data class ImportOptionsReview(
    val configuration: PortableConfiguration,
    val existingProfiles: Map<String, ProxyProfile>,
    val profiles: List<ProfileOptionsReview>,
    val globalChanged: Boolean,
    val currentDiagnosticLogLimitMb: Int,
)

private fun profileOptionGroups(current: ProxyProfile, imported: ProxyProfile): List<Int> = buildList {
    if (current.config.type != imported.config.type || current.config.port != imported.config.port ||
        current.config.jumpPort != imported.config.jumpPort ||
        current.config.sameJumpAuthentication != imported.config.sameJumpAuthentication
    ) add(R.string.import_option_connection)
    if (current.config.allowInvalidProxyCertificate != imported.config.allowInvalidProxyCertificate ||
        current.config.profile != imported.config.profile || current.config.customJa3 != imported.config.customJa3 ||
        current.config.sshProfile != imported.config.sshProfile ||
        current.config.trustedHostKey != imported.config.trustedHostKey ||
        current.config.acceptAnyHostKey != imported.config.acceptAnyHostKey ||
        current.config.jumpTrustedHostKey != imported.config.jumpTrustedHostKey ||
        current.config.jumpAcceptAnyHostKey != imported.config.jumpAcceptAnyHostKey
    ) add(R.string.import_option_security)
    if (current.config.dnsProvider != imported.config.dnsProvider ||
        current.config.customDohUrl != imported.config.customDohUrl
    ) add(R.string.import_option_dns)
    if (current.config.selectedPackages != imported.config.selectedPackages ||
        current.config.allowIpv6 != imported.config.allowIpv6 ||
        current.config.routeAllApps != imported.config.routeAllApps ||
        current.config.bypassLocalNetworks != imported.config.bypassLocalNetworks
    ) add(R.string.import_option_routing)
}

private fun keepLocalProfileOptions(current: ProxyProfile, imported: ProxyProfile): ProxyProfile = current.copy(
    id = imported.id,
    name = imported.name,
    colorIndex = imported.colorIndex,
    countryCode = imported.countryCode,
    config = current.config.copy(
        host = imported.config.host,
        username = imported.config.username,
        password = imported.config.password,
        privateKey = imported.config.privateKey,
        jumpHost = imported.config.jumpHost,
        jumpUsername = imported.config.jumpUsername,
        jumpPassword = imported.config.jumpPassword,
        jumpPrivateKey = imported.config.jumpPrivateKey,
    ),
)

private fun importOptionKey(profileId: String, group: Int) = "$profileId:$group"

private fun applySelectedProfileOptions(
    current: ProxyProfile,
    imported: ProxyProfile,
    selected: Set<String>,
): ProxyProfile {
    var result = keepLocalProfileOptions(current, imported)
    fun selected(group: Int) = importOptionKey(imported.id, group) in selected
    if (selected(R.string.import_option_connection)) result = result.copy(config = result.config.copy(
        type = imported.config.type,
        port = imported.config.port,
        jumpPort = imported.config.jumpPort,
        sameJumpAuthentication = imported.config.sameJumpAuthentication,
    ))
    if (selected(R.string.import_option_security)) result = result.copy(config = result.config.copy(
        allowInvalidProxyCertificate = imported.config.allowInvalidProxyCertificate,
        profile = imported.config.profile,
        customJa3 = imported.config.customJa3,
        sshProfile = imported.config.sshProfile,
        trustedHostKey = imported.config.trustedHostKey,
        acceptAnyHostKey = imported.config.acceptAnyHostKey,
        jumpTrustedHostKey = imported.config.jumpTrustedHostKey,
        jumpAcceptAnyHostKey = imported.config.jumpAcceptAnyHostKey,
    ))
    if (selected(R.string.import_option_dns)) result = result.copy(config = result.config.copy(
        dnsProvider = imported.config.dnsProvider,
        customDohUrl = imported.config.customDohUrl,
    ))
    if (selected(R.string.import_option_routing)) result = result.copy(config = result.config.copy(
        selectedPackages = imported.config.selectedPackages,
        allowIpv6 = imported.config.allowIpv6,
        routeAllApps = imported.config.routeAllApps,
        bypassLocalNetworks = imported.config.bypassLocalNetworks,
    ))
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    val scope = rememberCoroutineScope()
    val profiles = remember { mutableStateListOf<ProxyProfile>() }
    var deleteProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var importedProfileCount by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    var skippedNonHttps by remember { mutableStateOf(0) }
    var showImportFilterNotice by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showPasswordExportWarning by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ConfigExportFormat.JSON) }
    var includePasswords by remember { mutableStateOf(false) }
    var includePrivateKeys by remember { mutableStateOf(false) }
    var pendingExportContent by remember { mutableStateOf("") }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var pendingUnsafeImport by remember { mutableStateOf<PortableConfiguration?>(null) }
    var missingProfilesReview by remember { mutableStateOf<MissingProfilesReview?>(null) }
    val selectedMissingProfileIds = remember { mutableStateListOf<String>() }
    var importOptionsReview by remember { mutableStateOf<ImportOptionsReview?>(null) }
    val selectedImportOptionKeys = remember { mutableStateListOf<String>() }
    var applyImportedGlobalOptions by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var draggedProfileId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val loaded = withContext(ConfigIoDispatcher) { store.sortedProfiles() }
            profiles.clear()
            profiles.addAll(loaded)
        }
    }
    LaunchedEffect(Unit) { refresh() }
    fun moveProfile(profileId: String, delta: Int): Boolean {
        val sourceIndex = profiles.indexOfFirst { it.id == profileId }
        val targetIndex = sourceIndex + delta
        if (sourceIndex < 0 || targetIndex !in profiles.indices) return false
        profiles.add(targetIndex, profiles.removeAt(sourceIndex))
        val order = profiles.map(ProxyProfile::id)
        scope.launch(ConfigIoDispatcher) { store.reorderProfiles(order) }
        return true
    }
    fun edit(profile: ProxyProfile) {
        activity.startActivity(Intent(activity, ProfileEditorActivity::class.java).apply {
            putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile.id)
        })
    }
    fun writeExport(uri: Uri?) {
        if (uri == null) return
        val content = pendingExportContent
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) }
                        ?: error("Could not open the export file")
                }
            }
            result.onSuccess { transferMessage = activity.getString(R.string.configuration_exported) }
                .onFailure {
                    importError = activity.getString(
                        R.string.configuration_export_failed,
                        it.message ?: activity.getString(R.string.unknown_error),
                    )
                }
        }
    }
    fun applyJsonImport(configuration: PortableConfiguration) {
        scope.launch {
            val result = withContext(ConfigIoDispatcher) {
                store.importConfiguration(configuration).also {
                    if (ProxyVpnService.isRunning) store.markPendingReconnect()
                    PersistentDiagnosticLog.setLimitMb(store.diagnosticLogLimitMb())
                }
            }
            refresh()
            val imported = result.added + result.updated + result.unchanged
            val missingPasswords = imported.count { it.config.password.isEmpty() }
            val summary = listOfNotNull(
                activity.getString(R.string.config_import_summary, result.added.size, result.updated.size, result.unchanged.size),
                activity.getString(R.string.config_import_skipped, configuration.skippedProfiles)
                    .takeIf { configuration.skippedProfiles > 0 },
                activity.getString(R.string.config_import_missing_passwords, missingPasswords)
                    .takeIf { missingPasswords > 0 },
                activity.getString(R.string.config_import_always_on_reconnect)
                    .takeIf { ProxyVpnService.isAlwaysOnMode && ProxyVpnService.isRunning },
            ).joinToString(" ")
            if (result.missing.isEmpty()) {
                transferMessage = summary
            } else {
                selectedMissingProfileIds.clear()
                missingProfilesReview = MissingProfilesReview(result.missing, summary)
            }
            importedProfileCount = 0
        }
    }
    fun prepareJsonImport(configuration: PortableConfiguration) {
        scope.launch {
            val review = withContext(ConfigIoDispatcher) {
                val existing = store.profiles().associateBy(ProxyProfile::id)
                val profileReviews = configuration.profiles.mapNotNull { imported ->
                    val current = existing[imported.id] ?: return@mapNotNull null
                    profileOptionGroups(current, imported)
                        .takeIf(List<Int>::isNotEmpty)
                        ?.let { ProfileOptionsReview(imported, it) }
                }
                val importedGlobal = configuration.globalConnectionSettings
                val globalChanged = importedGlobal != null && (
                    importedGlobal != store.globalConnectionSettings() ||
                        configuration.diagnosticLogLimitMb != store.diagnosticLogLimitMb() ||
                        configuration.activeProfileId?.let { it != store.activeProfileId() } == true ||
                        configuration.alwaysOnProfileId?.let { it != store.alwaysOnProfileId() } == true
                    )
                ImportOptionsReview(
                    configuration = configuration,
                    existingProfiles = existing,
                    profiles = profileReviews,
                    globalChanged = globalChanged,
                    currentDiagnosticLogLimitMb = store.diagnosticLogLimitMb(),
                )
            }
            if (review.profiles.isEmpty() && !review.globalChanged) {
                applyJsonImport(configuration)
            } else {
                selectedImportOptionKeys.clear()
                applyImportedGlobalOptions = false
                importOptionsReview = review
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportTxtDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), ::writeExport)
    val exportJsonDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::writeExport)
    fun launchExport() {
        scope.launch {
            pendingExportContent = withContext(ConfigIoDispatcher) {
                when (exportFormat) {
                    ConfigExportFormat.PROXY_LIST -> ConfigTransfer.exportProxyList(store.profiles(), includePasswords)
                    ConfigExportFormat.JSON -> ConfigTransfer.exportJson(store, includePasswords, includePrivateKeys)
                }
            }
            if (exportFormat == ConfigExportFormat.PROXY_LIST) exportTxtDocument.launch("ProxyList.txt")
            else exportJsonDocument.launch("MegaProxy-config.json")
        }
    }
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)?.buffered()?.use { it.readConfigText() }
                    ?: error("Could not read the selected file")
                val isJson = activity.contentResolver.getType(uri) == "application/json" ||
                    uri.lastPathSegment.orEmpty().substringAfterLast('.', "").equals("json", true) ||
                    text.trimStart().startsWith('{')
                if (isJson) {
                    val isMegaProxy = runCatching {
                        ConfigTransfer.isSupportedSchema(org.json.JSONObject(text).optString("schema"))
                    }.getOrDefault(false)
                    if (isMegaProxy) {
                        val configuration = ConfigTransfer.importJson(text)
                        if (configuration.profiles.any { it.config.allowInvalidProxyCertificate || it.config.acceptAnyHostKey || it.config.jumpAcceptAnyHostKey }) {
                            pendingUnsafeImport = configuration
                        } else {
                            prepareJsonImport(configuration)
                        }
                    } else {
                        val imported = FoxyProxyParser.parse(text).getOrThrow()
                        val added = store.importProfiles(imported.proxies)
                        refresh()
                        importedProfileCount = added.size
                        skippedNonHttps = imported.skippedNonHttps
                        showImportFilterNotice = imported.skippedNonHttps > 0
                        if (imported.skippedNonHttps == 0) {
                            transferMessage = activity.getString(R.string.imported_foxyproxy, added.size)
                        }
                    }
                } else {
                    val isSuperProxy = SuperProxyParser.matches(text)
                    val imported = if (isSuperProxy) {
                        SuperProxyParser.parse(text).getOrThrow()
                    } else {
                        ProxyListParser.parse(text).getOrThrow()
                    }
                    val added = store.importProfiles(imported.proxies)
                    refresh()
                    importedProfileCount = added.size
                    skippedNonHttps = imported.skippedNonHttps
                    showImportFilterNotice = imported.skippedNonHttps > 0
                    if (imported.skippedNonHttps == 0) {
                        transferMessage = if (isSuperProxy) {
                            activity.getString(R.string.imported_super_proxy, added.size)
                        } else {
                            activity.getString(R.string.imported_https_profiles, added.size)
                        }
                    }
                }
                importError = null
            }.onFailure {
                importError = activity.getString(
                    R.string.configuration_import_failed,
                    it.message ?: activity.getString(R.string.unknown_error),
                )
            }
        }
    }

    Scaffold(
        topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.profiles)) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                TextButton(onClick = { importDocument.launch(arrayOf("text/plain", "application/json", "application/octet-stream")) }) {
                    Text(stringResource(R.string.import_action))
                }
                TextButton(onClick = { showExportDialog = true }) { Text(stringResource(R.string.export_action)) }
            },
        )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scope.launch {
                    val profile = withContext(ConfigIoDispatcher) { store.addProfile() }
                    refresh()
                    edit(profile)
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_profile))
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 840.dp).padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.profile_reorder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(profiles, key = { it.id }) { profile ->
                var dragOffset by remember(profile.id) { mutableStateOf(0f) }
                ProfileCard(
                    profile = profile,
                    modifier = Modifier
                        .zIndex(if (draggedProfileId == profile.id) 1f else 0f)
                        .graphicsLayer {
                            translationY = dragOffset
                            alpha = if (draggedProfileId == profile.id) 0.92f else 1f
                        }
                        .pointerInput(profile.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedProfileId = profile.id },
                                onDragCancel = {
                                    dragOffset = 0f
                                    draggedProfileId = null
                                    val order = profiles.map(ProxyProfile::id)
                                    scope.launch(ConfigIoDispatcher) { store.reorderProfiles(order) }
                                },
                                onDragEnd = {
                                    dragOffset = 0f
                                    draggedProfileId = null
                                    val order = profiles.map(ProxyProfile::id)
                                    scope.launch(ConfigIoDispatcher) { store.reorderProfiles(order) }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val currentIndex = profiles.indexOfFirst { it.id == profile.id }
                                    val currentInfo = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == profile.id } ?: return@detectDragGesturesAfterLongPress
                                    val targetY = currentInfo.offset + currentInfo.size / 2f + dragOffset
                                    val targetInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        it.key != profile.id && targetY.toInt() in it.offset..(it.offset + it.size)
                                    } ?: return@detectDragGesturesAfterLongPress
                                    val targetIndex = profiles.indexOfFirst { it.id == targetInfo.key }
                                    if (currentIndex >= 0 && targetIndex >= 0 && currentIndex != targetIndex) {
                                        dragOffset += currentInfo.offset - targetInfo.offset
                                        profiles.add(targetIndex, profiles.removeAt(currentIndex))
                                    }
                                },
                            )
                        }
                        .semantics {
                            customActions = buildList {
                                if (profiles.firstOrNull()?.id != profile.id) {
                                    add(CustomAccessibilityAction(activity.getString(R.string.move_up)) { moveProfile(profile.id, -1) })
                                }
                                if (profiles.lastOrNull()?.id != profile.id) {
                                    add(CustomAccessibilityAction(activity.getString(R.string.move_down)) { moveProfile(profile.id, 1) })
                                }
                            }
                        },
                    onConfigure = { edit(profile) },
                    onClone = {
                        scope.launch {
                            withContext(ConfigIoDispatcher) { store.cloneProfile(profile.id) }
                            refresh()
                        }
                    },
                    onDelete = { deleteProfile = profile },
                )
                }
                importError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                item { Text(stringResource(R.string.changes_saved_automatically), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text(stringResource(R.string.delete_profile_title, profile.displayName)) },
            text = { Text(stringResource(R.string.delete_profile_message)) },
            confirmButton = { TextButton(onClick = {
                deleteProfile = null
                scope.launch {
                    val reconnect = withContext(ConfigIoDispatcher) {
                        val needed = store.isConnectionDesired() && store.connectionProfile().id == profile.id
                        store.deleteProfile(profile.id)
                        needed
                    }
                    refresh()
                    if (reconnect) ProxyVpnService.reconnect(activity)
                }
            }, enabled = profiles.size > 1) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showImportFilterNotice) {
        AlertDialog(
            onDismissRequest = { showImportFilterNotice = false },
            title = { Text(stringResource(R.string.only_https_imported_title)) },
            text = { Text(stringResource(R.string.only_https_imported_message, importedProfileCount, skippedNonHttps)) },
            confirmButton = { TextButton(onClick = { showImportFilterNotice = false }) { Text(stringResource(R.string.continue_action)) } },
        )
    }
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export_configuration_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.format))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(exportFormat == ConfigExportFormat.JSON, { exportFormat = ConfigExportFormat.JSON })
                        Text(stringResource(R.string.export_json_description))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(exportFormat == ConfigExportFormat.PROXY_LIST, { exportFormat = ConfigExportFormat.PROXY_LIST })
                        Text(stringResource(R.string.export_proxy_list_description))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includePasswords, { includePasswords = it })
                        Text(stringResource(R.string.include_passwords))
                    }
                    if (!includePasswords) {
                        Text(stringResource(R.string.passwords_omitted_message), style = MaterialTheme.typography.bodySmall)
                    }
                    if (exportFormat == ConfigExportFormat.JSON) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includePrivateKeys, { includePrivateKeys = it })
                        Text(stringResource(R.string.include_private_keys))
                    }
                    if (includePrivateKeys) Text(stringResource(R.string.private_keys_plaintext_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                showExportDialog = false
                if (includePasswords || includePrivateKeys) showPasswordExportWarning = true else launchExport()
            }) { Text(stringResource(R.string.export_action)) } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showPasswordExportWarning) {
        AlertDialog(
            onDismissRequest = { showPasswordExportWarning = false },
            title = { Text(stringResource(R.string.export_secrets_title)) },
            text = { Text(stringResource(R.string.export_secrets_message)) },
            confirmButton = { TextButton(onClick = {
                showPasswordExportWarning = false
                launchExport()
            }) { Text(stringResource(R.string.export_anyway)) } },
            dismissButton = { TextButton(onClick = { showPasswordExportWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            title = { Text(stringResource(R.string.configuration_transfer)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { transferMessage = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    pendingUnsafeImport?.let { configuration ->
        AlertDialog(
            onDismissRequest = { pendingUnsafeImport = null },
            title = { Text(stringResource(R.string.unsafe_import_title)) },
            text = { Text(stringResource(R.string.unsafe_import_message)) },
            confirmButton = { TextButton(onClick = {
                pendingUnsafeImport = null
                prepareJsonImport(configuration)
            }) { Text(stringResource(R.string.import_anyway)) } },
            dismissButton = { TextButton(onClick = { pendingUnsafeImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    importOptionsReview?.let { review ->
        AlertDialog(
            onDismissRequest = {
                importOptionsReview = null
                selectedImportOptionKeys.clear()
                applyImportedGlobalOptions = false
            },
            title = { Text(stringResource(R.string.import_options_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.import_options_message))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(review.profiles, key = { it.profile.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.padding(top = 10.dp)) {
                                    Text(item.profile.displayName, style = MaterialTheme.typography.titleSmall)
                                    item.changedGroups.forEach { group ->
                                        val key = importOptionKey(item.profile.id, group)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = key in selectedImportOptionKeys,
                                                onCheckedChange = { checked ->
                                                    if (checked) selectedImportOptionKeys.add(key)
                                                    else selectedImportOptionKeys.remove(key)
                                                },
                                            )
                                            Text(stringResource(group))
                                        }
                                    }
                                }
                            }
                        }
                        if (review.globalChanged) item {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = applyImportedGlobalOptions,
                                    onCheckedChange = { applyImportedGlobalOptions = it },
                                )
                                Text(stringResource(R.string.import_global_options))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selectedOptions = selectedImportOptionKeys.toSet()
                    val applyGlobals = !review.globalChanged || applyImportedGlobalOptions
                    val resolved = review.configuration.copy(
                        profiles = review.configuration.profiles.map { imported ->
                            val current = review.existingProfiles[imported.id]
                            if (current != null) applySelectedProfileOptions(current, imported, selectedOptions)
                            else imported
                        },
                        activeProfileId = review.configuration.activeProfileId.takeIf { applyGlobals },
                        alwaysOnProfileId = review.configuration.alwaysOnProfileId.takeIf { applyGlobals },
                        diagnosticLogLimitMb = if (applyGlobals) {
                            review.configuration.diagnosticLogLimitMb
                        } else review.currentDiagnosticLogLimitMb,
                        globalConnectionSettings = review.configuration.globalConnectionSettings.takeIf { applyGlobals },
                    )
                    importOptionsReview = null
                    selectedImportOptionKeys.clear()
                    applyImportedGlobalOptions = false
                    applyJsonImport(resolved)
                }) { Text(stringResource(R.string.apply_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importOptionsReview = null
                    selectedImportOptionKeys.clear()
                    applyImportedGlobalOptions = false
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    missingProfilesReview?.let { review ->
        AlertDialog(
            onDismissRequest = {
                missingProfilesReview = null
                selectedMissingProfileIds.clear()
                transferMessage = review.importSummary
            },
            title = { Text(stringResource(R.string.remove_missing_profiles_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_missing_profiles_message))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(review.profiles, key = { it.id }) { profile ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = profile.id in selectedMissingProfileIds,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedMissingProfileIds.add(profile.id)
                                        else selectedMissingProfileIds.remove(profile.id)
                                    },
                                )
                                Column {
                                    Text(profile.displayName)
                                    Text("${profile.config.host}:${profile.config.port}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedMissingProfileIds.isNotEmpty(),
                    onClick = {
                        val selected = selectedMissingProfileIds.toSet()
                        missingProfilesReview = null
                        selectedMissingProfileIds.clear()
                        scope.launch {
                            val reconnect = withContext(ConfigIoDispatcher) {
                                val desired = store.isConnectionDesired()
                                store.deleteProfiles(selected).also {
                                    if (ProxyVpnService.isRunning) store.markPendingReconnect()
                                } && desired
                            }
                            refresh()
                            if (reconnect) ProxyVpnService.reconnect(activity)
                            transferMessage = review.importSummary + " " +
                                activity.getString(R.string.config_import_removed, selected.size)
                        }
                    },
                ) { Text(stringResource(R.string.remove_selected)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    missingProfilesReview = null
                    selectedMissingProfileIds.clear()
                    transferMessage = review.importSummary
                }) { Text(stringResource(R.string.keep_all)) }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProxyProfile,
    modifier: Modifier = Modifier,
    onConfigure: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    val background = Color(ProfileColors.argb[Math.floorMod(profile.colorIndex, ProfileColors.argb.size)])
    val foreground = if (background.luminance() > 0.45f) Color.Black else Color.White
    Card(
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.flagEmoji.isNotEmpty()) {
                    Surface(
                        color = Color.White, contentColor = Color.Black,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)),
                    ) { Text(profile.flagEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${profile.config.host}:${profile.config.port}", style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    color = foreground.copy(alpha = 0.16f),
                    contentColor = foreground,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, foreground.copy(alpha = 0.55f)),
                ) {
                    Text(
                        when (profile.config.type) {
                            ProxyType.HTTPS -> "HTTPS"
                            ProxyType.SSH -> "SSH"
                            ProxyType.SSH_JUMP -> "SSH + Jump"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onConfigure) { Text(stringResource(R.string.configure), color = foreground) }
                TextButton(onClick = onClone) { Text(stringResource(R.string.clone), color = foreground) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = foreground) }
            }
        }
    }
}
