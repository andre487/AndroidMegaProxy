package net.megaproxy487

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.PendingSshHostKey
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.uiStringResource as stringResource

private class HostKeyReviewState : ViewModel() {
    var saving by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var completed by mutableStateOf(false)
}

@Composable
internal fun SshHostKeyScreen(activity: Activity, prompt: PendingSshHostKey, onDismiss: () -> Unit) {
    val state = viewModel<HostKeyReviewState>(key = "${prompt.profileId}:${prompt.hop}:${prompt.fingerprint}") {
        HostKeyReviewState()
    }
    LaunchedEffect(state.completed) { if (state.completed) onDismiss() }
    fun reject() {
        if (state.saving) return
        // Regular connections remain paused; temporary diagnostics may release their service.
        if (prompt.testOnly) ProxyVpnService.dismissHostKeyPrompt(activity)
        onDismiss()
    }
    androidx.activity.compose.BackHandler { reject() }
    AlertDialog(
        onDismissRequest = ::reject,
        title = { DialogTitle(stringResource(if (prompt.changed) R.string.ssh_host_key_changed else R.string.trust_ssh_host_key)) },
        text = {
            Column {
                ScrollableDialogText(buildString {
                    append(activity.uiText(if (prompt.changed) R.string.ssh_changed_key_warning else R.string.ssh_first_connection_warning, activity.sshHopLabel(prompt.hop)))
                    append(activity.uiText(R.string.ssh_key_details, prompt.algorithm, prompt.fingerprint))
                })
                if (state.saving) Text(stringResource(R.string.saving_changes))
                if (state.failed) Text(stringResource(R.string.ssh_key_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), enabled = !state.saving, onClick = {
            state.saving = true
            state.failed = false
            val app = activity.applicationContext
            state.viewModelScope.launch {
                try {
                    val saved = withContext(ConfigIoDispatcher) {
                        ConfigStore(app).trustSshHostKey(prompt.profileId, prompt.hop, prompt.fingerprint)
                    }
                    if (saved) {
                        if (prompt.testOnly) ProxyVpnService.test(app) else ProxyVpnService.reconnect(app)
                        state.completed = true
                    } else state.failed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state.failed = true
                } finally { state.saving = false }
            }
        }) { Text(stringResource(when {
            prompt.testOnly && prompt.changed -> R.string.replace_and_test
            prompt.testOnly -> R.string.trust_and_test
            prompt.changed -> R.string.replace_trusted_key
            else -> R.string.trust_and_connect
        })) } },
        dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), enabled = !state.saving, onClick = ::reject) { Text(stringResource(R.string.cancel)) } },
    )
}
