package net.megaproxy487

import androidx.compose.foundation.shape.RoundedCornerShape

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.megaproxy487.uiStringResource as stringResource
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.TestDiagnosticLog
import net.megaproxy487.vpn.TestState
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.openAndroidVpnSettings
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(activity: Activity, autoStart: Boolean, onBack: () -> Unit) {
    val state by TestDiagnosticLog.state
    val exitIp by TestDiagnosticLog.exitIp
    val countryCode by TestDiagnosticLog.countryCode
    val http3 by TestDiagnosticLog.http3
    val http3Fallback by TestDiagnosticLog.http3Fallback
    var showAlwaysOnConflict by remember { mutableStateOf(false) }
    var speedRunning by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val snapshot = TestDiagnosticLog.entries.joinToString("\n")
            scope.launch {
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(snapshot)
                        } ?: throw UiException(R.string.error_open_export)
                    }
                    exportMessage = activity.uiText(R.string.diagnostic_log_exported)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    exportMessage = error.userMessage(activity, R.string.log_export_failed)
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(activity) == null) ProxyVpnService.test(activity)
        else scope.launch {
            val status = withContext(net.megaproxy487.data.ConfigIoDispatcher) { readAlwaysOnVpnStatus(activity) }
            if (status.hasOtherProvider) {
                TestDiagnosticLog.fail(activity.uiText(R.string.other_always_on_vpn))
                showAlwaysOnConflict = true
            } else {
                TestDiagnosticLog.fail(activity.uiText(R.string.vpn_permission_denied))
            }
        }
    }
    val runTest = runTest@{
        if (TestDiagnosticLog.state.value == TestState.RUNNING || speedRunning) return@runTest
        TestDiagnosticLog.begin()
        scope.launch {
            try {
                // A live VPN is tested with its actual runtime configuration in the service.
                val error = if (ProxyVpnService.isRunning) null else withContext(net.megaproxy487.data.ConfigIoDispatcher) {
                    val store = ConfigStore(activity)
                    store.globalConnectionSettings().applyTo(store.activeProfile().config)
                        .connectionValidationError()?.let { activity.uiText(it) }
                }
                if (error != null) {
                    TestDiagnosticLog.fail(error)
                } else {
                    val status = withContext(net.megaproxy487.data.ConfigIoDispatcher) { readAlwaysOnVpnStatus(activity) }
                    if (status.hasOtherProvider) {
                        TestDiagnosticLog.fail(activity.uiText(R.string.other_always_on_vpn))
                        showAlwaysOnConflict = true
                    } else {
                        val intent = VpnService.prepare(activity)
                        if (intent == null) ProxyVpnService.test(activity) else permission.launch(intent)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                TestDiagnosticLog.reset()
                throw cancelled
            } catch (_: Exception) {
                TestDiagnosticLog.fail(activity.uiText(R.string.error_config_storage))
            }
        }
    }
    var autoStartConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStartConsumed) {
            autoStartConsumed = true
            if (shouldAutoStartConnectionTest(state)) runTest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        ScreenContent(Modifier.padding(padding)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val logHeight = maxOf(160.dp, maxHeight * 0.55f)
                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val statusLabel = when (state) {
                        TestState.IDLE -> activity.uiText(R.string.test_ready)
                        TestState.RUNNING -> activity.uiText(R.string.test_testing)
                        TestState.SUCCEEDED -> activity.uiText(R.string.test_passed)
                        TestState.FAILED -> activity.uiText(R.string.test_failed)
                    }
                    Card(Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = statusLabel
                    }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val statusColor = when (state) {
                                TestState.SUCCEEDED -> MaterialTheme.colorScheme.primary
                                TestState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                if (state == TestState.RUNNING) activity.uiText(R.string.test_testing_progress) else statusLabel,
                                style = MaterialTheme.typography.titleLarge,
                                color = statusColor,
                            )
                            if (state == TestState.RUNNING) {
                                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                            }
                            exitIp?.let {
                                SelectionContainer { Text(stringResource(R.string.proxy_exit_ip, it)) }
                            }
                            countryCode?.let { Text(stringResource(R.string.proxy_exit_country, formatCountry(it, systemFormattingLocale()))) }
                            if (http3Fallback) Text(stringResource(R.string.http3_fallback_warning),
                                color = MaterialTheme.colorScheme.tertiary)
                            http3.forEach { probe ->
                                val status = stringResource(if (probe.confirmed) R.string.test_http3_confirmed else R.string.test_http3_unavailable)
                                Text(
                                    stringResource(
                                        R.string.test_http3_provider,
                                        probe.provider,
                                        stringResource(if (probe.confirmed) R.string.test_status_success_icon else R.string.test_status_failed_icon),
                                    ),
                                    Modifier.clearAndSetSemantics {
                                        contentDescription = activity.uiText(R.string.test_http3_provider_a11y, probe.provider, status)
                                    },
                                )
                            }
                            if (http3.isNotEmpty()) {
                                Text(stringResource(R.string.test_http3_help), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    WrappingActions(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(shape = RoundedCornerShape(12.dp), onClick = runTest, enabled = state != TestState.RUNNING && !speedRunning) { Text(stringResource(R.string.run_again)) }
                    }
                    Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleMedium)
                    if (state == TestState.SUCCEEDED) {
                        SpeedTestContent(activity, onRunningChanged = { speedRunning = it })
                    } else {
                        Text(stringResource(R.string.diagnostics_speed_locked), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(activity.uiText(R.string.test_privacy_help), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.diagnostic_log), style = MaterialTheme.typography.titleMedium)
                    WrappingActions(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(shape = RoundedCornerShape(12.dp), onClick = {
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(activity.uiText(R.string.connection_test_clipboard_label), TestDiagnosticLog.entries.joinToString("\n")))
                        }, enabled = TestDiagnosticLog.entries.isNotEmpty()) { Text(stringResource(R.string.copy_log)) }
                        Button(shape = RoundedCornerShape(12.dp), onClick = {
                            exportDocument.launch("MegaProxy-diagnostics.log")
                        }, enabled = TestDiagnosticLog.entries.isNotEmpty()) { Text(stringResource(R.string.export_action)) }
                    }
                    exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = logHeight)) {
                        SelectionContainer {
                            Text(
                                TestDiagnosticLog.entries.joinToString("\n").ifEmpty {
                                    if (state == TestState.RUNNING) activity.uiText(R.string.test_waiting) else activity.uiText(R.string.test_empty)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAlwaysOnConflict) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnConflict = false },
            title = { DialogTitle(stringResource(R.string.always_on_conflict_title)) },
            text = { ScrollableDialogText(activity.uiText(R.string.other_always_on_vpn)) },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    showAlwaysOnConflict = false
                    openAndroidVpnSettings(activity)
                }) { Text(stringResource(R.string.change_settings)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = { showAlwaysOnConflict = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
