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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.navigationBarsPadding
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
import net.megaproxy487.uiStringResource as stringResource
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import net.megaproxy487.data.ConfigWrites
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        current.config.jumpAllowInvalidProxyCertificate != imported.config.jumpAllowInvalidProxyCertificate ||
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
        jumpAllowInvalidProxyCertificate = imported.config.jumpAllowInvalidProxyCertificate,
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

private class ProfilesUiState(private val context: android.content.Context) : ViewModel() {
    val profiles = mutableStateListOf<ProxyProfile>()
    var deleteProfile by mutableStateOf<ProxyProfile?>(null)
    var importedProfileCount by mutableStateOf(0)
    var importError by mutableStateOf<String?>(null)
    var skippedNonHttps by mutableStateOf(0)
    var showImportFilterNotice by mutableStateOf(false)
    var showExportDialog by mutableStateOf(false)
    var showPasswordExportWarning by mutableStateOf(false)
    var exportFormat by mutableStateOf(ConfigExportFormat.JSON)
    var includePasswords by mutableStateOf(false)
    var includePrivateKeys by mutableStateOf(false)
    var pendingExportContent by mutableStateOf<String?>(null)
    var exportReady by mutableStateOf(false)
    var transferMessage by mutableStateOf<String?>(null)
    var pendingUnsafeImport by mutableStateOf<PortableConfiguration?>(null)
    var missingProfilesReview by mutableStateOf<MissingProfilesReview?>(null)
    val selectedMissingProfileIds = mutableStateListOf<String>()
    var importOptionsReview by mutableStateOf<ImportOptionsReview?>(null)
    val selectedImportOptionKeys = mutableStateListOf<String>()
    var applyImportedGlobalOptions by mutableStateOf(false)
    private var pendingOperations by mutableStateOf(0)
    val busy: Boolean get() = pendingOperations > 0

    fun launchOperation(block: suspend CoroutineScope.() -> Unit) {
        pendingOperations++
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                pendingExportContent = null
                exportReady = false
                importError = failure.userMessage(context, R.string.transfer_failed)
            } finally { pendingOperations-- }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesScreen(activity: Activity, onBack: () -> Unit, onEditProfile: (String) -> Unit) {
    val store = remember { ConfigStore(activity) }
    val uiState = viewModel { ProfilesUiState(activity.applicationContext) }
    val busy = uiState.busy
    val profiles = uiState.profiles
    var deleteProfile by uiState::deleteProfile
    var importedProfileCount by uiState::importedProfileCount
    var importError by uiState::importError
    var skippedNonHttps by uiState::skippedNonHttps
    var showImportFilterNotice by uiState::showImportFilterNotice
    var showExportDialog by uiState::showExportDialog
    var showPasswordExportWarning by uiState::showPasswordExportWarning
    var exportFormat by uiState::exportFormat
    var includePasswords by uiState::includePasswords
    var includePrivateKeys by uiState::includePrivateKeys
    var pendingExportContent by uiState::pendingExportContent
    var exportReady by uiState::exportReady
    var transferMessage by uiState::transferMessage
    var pendingUnsafeImport by uiState::pendingUnsafeImport
    var missingProfilesReview by uiState::missingProfilesReview
    val selectedMissingProfileIds = uiState.selectedMissingProfileIds
    var importOptionsReview by uiState::importOptionsReview
    val selectedImportOptionKeys = uiState.selectedImportOptionKeys
    var applyImportedGlobalOptions by uiState::applyImportedGlobalOptions
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var draggedProfileId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        uiState.launchOperation {
            val loaded = withContext(ConfigIoDispatcher) { store.sortedProfiles() }
            profiles.clear()
            profiles.addAll(loaded)
        }
    }
    LaunchedEffect(Unit) { refresh() }
    fun moveProfile(profileId: String, delta: Int): Boolean {
        if (uiState.busy) return false
        val sourceIndex = profiles.indexOfFirst { it.id == profileId }
        val targetIndex = sourceIndex + delta
        if (sourceIndex < 0 || targetIndex !in profiles.indices) return false
        profiles.add(targetIndex, profiles.removeAt(sourceIndex))
        val order = profiles.map(ProxyProfile::id)
        ConfigWrites.submit("profile-order") { store.reorderProfiles(order) }
        return true
    }
    fun edit(profile: ProxyProfile) {
        onEditProfile(profile.id)
    }
    fun writeExport(uri: Uri?) {
        val prepared = pendingExportContent
        pendingExportContent = null
        if (uri == null) return
        uiState.launchOperation {
            val content = requireExportContent(prepared)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) }
                        ?: throw UiException(R.string.error_open_export)
                }
            }
            result.onSuccess { transferMessage = activity.uiText(R.string.configuration_exported) }
                .onFailure {
                    importError = activity.uiText(
                        R.string.configuration_export_failed,
                        it.userMessage(activity, R.string.unknown_error),
                    )
                }
        }
    }
    fun applyJsonImport(configuration: PortableConfiguration) {
        uiState.launchOperation {
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
                activity.uiText(R.string.config_import_summary, result.added.size, result.updated.size, result.unchanged.size),
                activity.uiText(R.string.config_import_skipped, configuration.skippedProfiles)
                    .takeIf { configuration.skippedProfiles > 0 },
                activity.uiText(R.string.config_import_missing_passwords, missingPasswords)
                    .takeIf { missingPasswords > 0 },
                activity.uiText(R.string.config_import_always_on_reconnect)
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
        uiState.launchOperation {
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
        if (uiState.busy || pendingExportContent != null) return
        uiState.launchOperation {
            pendingExportContent = withContext(ConfigIoDispatcher) {
                when (exportFormat) {
                    ConfigExportFormat.PROXY_LIST -> ConfigTransfer.exportProxyList(store.profiles(), includePasswords)
                    ConfigExportFormat.JSON -> ConfigTransfer.exportJson(store, includePasswords, includePrivateKeys)
                }
            }
            requireExportContent(pendingExportContent)
            exportReady = true
        }
    }
    LaunchedEffect(exportReady) {
        if (exportReady) {
            exportReady = false
            if (exportFormat == ConfigExportFormat.PROXY_LIST) exportTxtDocument.launch("ProxyList.txt")
            else exportJsonDocument.launch("MegaProxy-config.json")
        }
    }
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !uiState.busy) {
            uiState.launchOperation {
                val parsed = withContext(Dispatchers.IO) {
                    val text = activity.contentResolver.openInputStream(uri)?.buffered()?.use { it.readConfigText() }
                        ?: throw UiException(R.string.error_read_file)
                    parseProfileImport(text, activity.contentResolver.getType(uri), uri.lastPathSegment.orEmpty())
                }
                when (parsed) {
                    is ParsedProfileImport.Configuration -> {
                        val configuration = parsed.value
                        if (configuration.profiles.any { it.config.allowInvalidProxyCertificate || it.config.jumpAllowInvalidProxyCertificate || it.config.acceptAnyHostKey || it.config.jumpAcceptAnyHostKey }) {
                            pendingUnsafeImport = configuration
                        } else prepareJsonImport(configuration)
                    }
                    is ParsedProfileImport.ProxyList -> {
                        val added = withContext(ConfigIoDispatcher) { store.importProfiles(parsed.value.proxies) }
                        refresh()
                        importedProfileCount = added.size
                        skippedNonHttps = parsed.value.skippedNonHttps
                        showImportFilterNotice = skippedNonHttps > 0
                        if (!showImportFilterNotice) transferMessage = activity.uiText(parsed.summaryRes, added.size)
                    }
                }
                importError = null
            }
        }
    }

    Scaffold(
        topBar = {
        TopAppBar(
            title = { ScreenTitle(stringResource(R.string.profiles)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(enabled = !busy, onClick = { importDocument.launch(arrayOf("text/plain", "application/json", "application/octet-stream")) }) {
                    Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.import_action))
                }
                IconButton(enabled = !busy, onClick = { showExportDialog = true }) { Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.export_action)) }
            },
        )
        },
        bottomBar = {
            Column {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.transfer_working), modifier = Modifier.padding(horizontal = 16.dp))
                }
                SaveStatusBanner()
            Surface(tonalElevation = 3.dp) {
                Button(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !busy,
                    onClick = {
                        onEditProfile("new")
                    },
                ) { Text(stringResource(R.string.add_profile)) }
            }
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
                contentPadding = PaddingValues(bottom = 16.dp),
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
                                    ConfigWrites.submit("profile-order") { store.reorderProfiles(order) }
                                },
                                onDragEnd = {
                                    dragOffset = 0f
                                    draggedProfileId = null
                                    val order = profiles.map(ProxyProfile::id)
                                    ConfigWrites.submit("profile-order") { store.reorderProfiles(order) }
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
                                    add(CustomAccessibilityAction(activity.uiText(R.string.move_up)) { moveProfile(profile.id, -1) })
                                }
                                if (profiles.lastOrNull()?.id != profile.id) {
                                    add(CustomAccessibilityAction(activity.uiText(R.string.move_down)) { moveProfile(profile.id, 1) })
                                }
                            }
                        },
                    enabled = !busy,
                    onConfigure = { edit(profile) },
                    onClone = {
                        uiState.launchOperation {
                            withContext(ConfigIoDispatcher) { store.cloneProfile(profile.id) }
                            refresh()
                        }
                    },
                    onDelete = { deleteProfile = profile },
                )
                }
                item { Text(stringResource(R.string.changes_saved_automatically), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }

    importError?.let { message ->
        AlertDialog(onDismissRequest = { importError = null },
            title = { DialogTitle(stringResource(R.string.configuration_transfer)) },
            text = { ScrollableDialogText(message) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { importError = null }) {
                Text(stringResource(R.string.ok))
            } })
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { DialogTitle(stringResource(R.string.delete_profile_confirmation)) },
            text = { ScrollableDialogText(profile.localizedName(activity) + "\n\n" + stringResource(R.string.delete_profile_message)) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                deleteProfile = null
                uiState.launchOperation {
                    val reconnect = withContext(ConfigIoDispatcher) {
                        val needed = store.isConnectionDesired() && store.connectionProfile().id == profile.id
                        store.deleteProfile(profile.id)
                        needed
                    }
                    refresh()
                    if (reconnect) ProxyVpnService.reconnect(activity)
                }
            }, enabled = profiles.size > 1) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { deleteProfile = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showImportFilterNotice) {
        AlertDialog(
            onDismissRequest = { showImportFilterNotice = false },
            title = { DialogTitle(stringResource(R.string.only_https_imported_title)) },
            text = { ScrollableDialogText(stringResource(R.string.only_https_imported_message, importedProfileCount, skippedNonHttps)) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { showImportFilterNotice = false }) { Text(stringResource(R.string.continue_action)) } },
        )
    }
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { DialogTitle(stringResource(R.string.export_configuration_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.format))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(exportFormat == ConfigExportFormat.JSON, { exportFormat = ConfigExportFormat.JSON })
                        Text(stringResource(R.string.export_json_description), modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(exportFormat == ConfigExportFormat.PROXY_LIST, { exportFormat = ConfigExportFormat.PROXY_LIST })
                        Text(stringResource(R.string.export_proxy_list_description), modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includePasswords, { includePasswords = it })
                        Text(stringResource(R.string.include_passwords), modifier = Modifier.weight(1f))
                    }
                    if (!includePasswords) {
                        Text(stringResource(R.string.passwords_omitted_message), style = MaterialTheme.typography.bodySmall)
                    }
                    if (exportFormat == ConfigExportFormat.JSON) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includePrivateKeys, { includePrivateKeys = it })
                        Text(stringResource(R.string.include_private_keys), modifier = Modifier.weight(1f))
                    }
                    if (includePrivateKeys) Text(stringResource(R.string.private_keys_plaintext_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                showExportDialog = false
                if (includePasswords || includePrivateKeys) showPasswordExportWarning = true else launchExport()
            }) { Text(stringResource(R.string.export_action)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showPasswordExportWarning) {
        AlertDialog(
            onDismissRequest = { showPasswordExportWarning = false },
            title = { DialogTitle(stringResource(R.string.export_secrets_title)) },
            text = { ScrollableDialogText(stringResource(R.string.export_secrets_message)) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                showPasswordExportWarning = false
                launchExport()
            }) { Text(stringResource(R.string.export_anyway)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { showPasswordExportWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            title = { DialogTitle(stringResource(R.string.configuration_transfer)) },
            text = { ScrollableDialogText(message) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { transferMessage = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    pendingUnsafeImport?.let { configuration ->
        AlertDialog(
            onDismissRequest = { pendingUnsafeImport = null },
            title = { DialogTitle(stringResource(R.string.unsafe_import_title)) },
            text = { ScrollableDialogText(stringResource(R.string.unsafe_import_message)) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                pendingUnsafeImport = null
                prepareJsonImport(configuration)
            }) { Text(stringResource(R.string.import_anyway)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { pendingUnsafeImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    importOptionsReview?.let { review ->
        AlertDialog(
            onDismissRequest = {
                importOptionsReview = null
                selectedImportOptionKeys.clear()
                applyImportedGlobalOptions = false
            },
            title = { DialogTitle(stringResource(R.string.import_options_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.import_options_message), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(review.profiles, key = { it.profile.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.padding(top = 10.dp)) {
                                    Text(item.profile.localizedName(activity), style = MaterialTheme.typography.titleSmall)
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
                                            Text(stringResource(group), modifier = Modifier.weight(1f))
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
                                Text(stringResource(R.string.import_global_options), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
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
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
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
            title = { DialogTitle(stringResource(R.string.remove_missing_profiles_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_missing_profiles_message), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
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
                                Column(Modifier.weight(1f)) {
                                    Text(profile.localizedName(activity))
                                    Text("${profile.config.host}:${profile.config.port}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp),
                    enabled = selectedMissingProfileIds.isNotEmpty(),
                    onClick = {
                        val selected = selectedMissingProfileIds.toSet()
                        missingProfilesReview = null
                        selectedMissingProfileIds.clear()
                        uiState.launchOperation {
                            val reconnect = withContext(ConfigIoDispatcher) {
                                val desired = store.isConnectionDesired()
                                store.deleteProfiles(selected).also {
                                    if (ProxyVpnService.isRunning) store.markPendingReconnect()
                                } && desired
                            }
                            refresh()
                            if (reconnect) ProxyVpnService.reconnect(activity)
                            transferMessage = review.importSummary + " " +
                                activity.uiText(R.string.config_import_removed, selected.size)
                        }
                    },
                ) { Text(stringResource(R.string.remove_selected)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onConfigure: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current
    val background = Color(ProfileColors.argb[Math.floorMod(profile.colorIndex, ProfileColors.argb.size)])
    val foreground = if (background.luminance() > 0.45f) Color.Black else Color.White
    Card(
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdaptiveLabelRow(
                label = { labelModifier ->
                    Row(labelModifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.flagEmoji.isNotEmpty()) {
                            Surface(
                                color = Color.White, contentColor = Color.Black,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)),
                            ) { Text(profile.flagEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(profile.localizedName(activity), style = MaterialTheme.typography.titleMedium)
                            Text("${profile.config.host}:${profile.config.port}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                trailing = { ProfileTypeBadge(profile.config.type, foreground) },
            )
            WrappingActions() {
                TextButton(enabled = enabled, shape = RoundedCornerShape(12.dp), onClick = onConfigure) { Text(stringResource(R.string.configure), color = foreground) }
                TextButton(enabled = enabled, shape = RoundedCornerShape(12.dp), onClick = onClone) { Text(stringResource(R.string.clone), color = foreground) }
                TextButton(enabled = enabled, shape = RoundedCornerShape(12.dp), onClick = onDelete) { Text(stringResource(R.string.delete), color = foreground) }
            }
        }
    }
}
