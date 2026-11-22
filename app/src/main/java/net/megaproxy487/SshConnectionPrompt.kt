package net.megaproxy487

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.operationResult
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.uiStringResource as stringResource

@Composable
internal fun sshConnectionPrompt(
    store: ConfigStore,
    onProfileChanged: () -> Unit,
): (ProxyProfile, () -> Unit) -> Unit {
    var pending by remember { mutableStateOf<Pair<ProxyProfile, () -> Unit>?>(null) }
    pending?.let { (profile, connect) ->
        SshUnlockDialog(profile.config, onCancel = { pending = null }) { destination, jump ->
            if (pending?.first != profile) return@SshUnlockDialog
            val unlocked = withContext(ConfigIoDispatcher) {
                operationResult { store.unlockForConnection(profile, destination, jump) }.getOrDefault(false)
            }
            if (pending?.first != profile) return@SshUnlockDialog
            pending = null
            if (unlocked) connect() else onProfileChanged()
        }
    }
    return { profile, connect ->
        if (profile.config.needsSshPassphrase(false, true) || profile.config.needsSshPassphrase(true, true)) {
            pending = profile to connect
        } else connect()
    }
}

@Composable
private fun SshUnlockDialog(
    config: ProxyConfig,
    onCancel: () -> Unit,
    onUnlocked: suspend (String, String) -> Unit,
) {
    val destinationNeeded = config.needsSshPassphrase(false, requireSaved = true)
    val jumpNeeded = config.needsSshPassphrase(true, requireSaved = true)
    var destination by remember { mutableStateOf("") }
    var jump by remember { mutableStateOf("") }
    var failedJump by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onCancel,
        title = { DialogTitle(stringResource(R.string.ssh_key_passphrase)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (destinationNeeded) PasswordField(destination, {
                    if (!checking && it.length <= 16_384) { destination = it; failedJump = null }
                }, stringResource(R.string.ssh_unlock_destination),
                    error = if (failedJump == false) stringResource(R.string.ssh_unlock_failed) else null)
                if (jumpNeeded) PasswordField(jump, {
                    if (!checking && it.length <= 16_384) { jump = it; failedJump = null }
                }, stringResource(R.string.ssh_unlock_jump),
                    error = if (failedJump == true) stringResource(R.string.ssh_unlock_failed) else null)
            }
        },
        confirmButton = {
            TextButton(enabled = !checking && (!destinationNeeded || destination.isNotEmpty()) &&
                (!jumpNeeded || jump.isNotEmpty()), onClick = {
                checking = true
                scope.launch {
                    failedJump = withContext(Dispatchers.Default) {
                        when {
                            destinationNeeded && runCatching { mobile.Mobile.checkSSHKeyPassphrase(config.privateKey, destination) }.isFailure -> false
                            jumpNeeded && runCatching { mobile.Mobile.checkSSHKeyPassphrase(config.jumpPrivateKey, jump) }.isFailure -> true
                            else -> null
                        }
                    }
                    if (failedJump == null) onUnlocked(destination, jump)
                    checking = false
                }
            }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
