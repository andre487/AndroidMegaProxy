package net.megaproxy487

import androidx.compose.foundation.shape.RoundedCornerShape

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.megaproxy487.uiStringResource as stringResource
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.TestDiagnosticLog
import net.megaproxy487.vpn.TestState
import net.megaproxy487.vpn.SshHostKeyPromptState
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.openAndroidVpnSettings
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.ui.theme.MegaProxyTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionTestScreen(activity: Activity, autoStart: Boolean, onBack: () -> Unit) {
    val state by TestDiagnosticLog.state
    val exitIp by TestDiagnosticLog.exitIp
    val countryCode by TestDiagnosticLog.countryCode
    val pendingHostKey by SshHostKeyPromptState.pending
    var vpnPermissionRequestedAt by remember { mutableStateOf(0L) }
    var showAlwaysOnConflict by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(activity) == null) ProxyVpnService.test(activity)
        else {
            val status = readAlwaysOnVpnStatus(activity)
            val dismissedImmediately = System.currentTimeMillis() - vpnPermissionRequestedAt < 1_000
            if (status.hasOtherProvider || dismissedImmediately) {
                TestDiagnosticLog.fail(activity.uiText(R.string.other_always_on_vpn))
                showAlwaysOnConflict = true
            } else {
                TestDiagnosticLog.fail(activity.uiText(R.string.vpn_permission_denied))
            }
        }
    }
    val runTest = {
        val configStore = ConfigStore(activity)
        val error = configStore.globalConnectionSettings().applyTo(configStore.activeProfile().config)
            .connectionValidationError()?.let { activity.uiText(it) }
        if (error != null) {
            TestDiagnosticLog.fail(error)
        } else {
            TestDiagnosticLog.begin()
            val status = readAlwaysOnVpnStatus(activity)
            if (status.hasOtherProvider) {
                TestDiagnosticLog.fail(activity.uiText(R.string.other_always_on_vpn))
                showAlwaysOnConflict = true
            } else {
                val intent = VpnService.prepare(activity)
                if (intent == null) {
                    ProxyVpnService.test(activity)
                } else {
                    vpnPermissionRequestedAt = System.currentTimeMillis()
                    permission.launch(intent)
                }
            }
        }
    }
    var autoStartConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStartConsumed) {
            autoStartConsumed = true
            TestDiagnosticLog.reset()
            runTest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.connection_test)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
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
                        exitIp?.let { Text(stringResource(R.string.proxy_exit_ip, it)) }
                        countryCode?.let { Text(stringResource(R.string.proxy_exit_country, formatCountry(it, systemFormattingLocale()))) }
                    }
                }
                WrappingActions(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(shape = RoundedCornerShape(12.dp), onClick = runTest, enabled = state != TestState.RUNNING) { Text(stringResource(R.string.run_again)) }
                    Button(shape = RoundedCornerShape(12.dp), onClick = {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(activity.uiText(R.string.connection_test_clipboard_label), TestDiagnosticLog.entries.joinToString("\n")))
                    }, enabled = TestDiagnosticLog.entries.isNotEmpty()) { Text(stringResource(R.string.copy_log)) }
                }
                Text(
                    activity.uiText(R.string.test_privacy_help),
                    style = MaterialTheme.typography.bodySmall,
                )
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
    pendingHostKey?.takeIf { it.testOnly }?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                SshHostKeyPromptState.clear()
                ProxyVpnService.dismissHostKeyPrompt(activity)
            },
            title = { DialogTitle(stringResource(if (pending.changed) R.string.ssh_host_key_changed else R.string.trust_ssh_host_key)) },
            text = { ScrollableDialogText(buildString {
                if (pending.changed) {
                    append(activity.uiText(R.string.ssh_changed_key_warning, activity.sshHopLabel(pending.hop)))
                } else {
                    append(activity.uiText(R.string.ssh_first_connection_warning, activity.sshHopLabel(pending.hop)))
                }
                append(activity.uiText(R.string.ssh_key_details, pending.algorithm, pending.fingerprint))
            }) },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    val saved = ConfigStore(activity).trustSshHostKey(
                        pending.profileId, pending.hop, pending.fingerprint,
                    )
                    SshHostKeyPromptState.clear()
                    val persisted = ConfigStore(activity).profile(pending.profileId)?.config?.let { config ->
                        if (pending.hop == "jump") config.jumpTrustedHostKey else config.trustedHostKey
                    }
                    if (saved && persisted == pending.fingerprint) {
                        ProxyVpnService.test(activity)
                    } else {
                        TestDiagnosticLog.fail(activity.uiText(R.string.test_key_save_failed))
                    }
                }) { Text(stringResource(if (pending.changed) R.string.replace_and_test else R.string.trust_and_test)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    SshHostKeyPromptState.clear()
                    ProxyVpnService.dismissHostKeyPrompt(activity)
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
