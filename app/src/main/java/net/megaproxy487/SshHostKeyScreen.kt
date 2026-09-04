package net.megaproxy487

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.PendingSshHostKey
import net.megaproxy487.vpn.ProxyVpnService

@Composable
internal fun SshHostKeyScreen(activity: Activity, prompt: PendingSshHostKey, onDismiss: () -> Unit) {
    fun reject() {
        // A normal/Always-on connection remains paused with an actionable persistent
        // notification. Tests are disposable and may release their temporary service.
        if (prompt.testOnly) ProxyVpnService.dismissHostKeyPrompt(activity)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::reject,
        title = { Text(stringResource(if (prompt.changed) R.string.ssh_host_key_changed else R.string.trust_ssh_host_key)) },
        text = { Text(buildString {
            append(activity.getString(if (prompt.changed) R.string.ssh_changed_key_warning else R.string.ssh_first_connection_warning, prompt.hop))
            append(activity.getString(R.string.ssh_key_details, prompt.algorithm, prompt.fingerprint))
        }) },
        confirmButton = { TextButton(onClick = {
            if (ConfigStore(activity).trustSshHostKey(prompt.profileId, prompt.hop, prompt.fingerprint)) {
                if (prompt.testOnly) ProxyVpnService.test(activity) else ProxyVpnService.reconnect(activity)
            }
            onDismiss()
        }) { Text(stringResource(if (prompt.changed) R.string.replace_trusted_key else R.string.trust_and_connect)) } },
        dismissButton = { TextButton(onClick = ::reject) { Text(stringResource(R.string.cancel)) } },
    )
}
