package net.megaproxy487

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        title = { Text(if (prompt.changed) "SSH host key changed" else "Trust SSH host key?") },
        text = { Text(buildString {
            if (prompt.changed) append("Warning: the previously trusted key for the ${prompt.hop} host has changed. This can indicate a server reinstall or a man-in-the-middle attack. Verify the fingerprint through a trusted channel before replacing it.\n\n")
            else append("This is the first connection to the ${prompt.hop} SSH host. Verify its fingerprint through a trusted channel.\n\n")
            append("Algorithm: ${prompt.algorithm}\nFingerprint: ${prompt.fingerprint}")
        }) },
        confirmButton = { TextButton(onClick = {
            if (ConfigStore(activity).trustSshHostKey(prompt.profileId, prompt.hop, prompt.fingerprint)) {
                if (prompt.testOnly) ProxyVpnService.test(activity) else ProxyVpnService.reconnect(activity)
            }
            onDismiss()
        }) { Text(if (prompt.changed) "Replace trusted key" else "Trust and connect") } },
        dismissButton = { TextButton(onClick = ::reject) { Text("Cancel") } },
    )
}
