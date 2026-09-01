package net.megaproxy487

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService

class SshHostKeyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val hop = intent.getStringExtra(EXTRA_HOP).orEmpty()
        val algorithm = intent.getStringExtra(EXTRA_ALGORITHM).orEmpty()
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
        val changed = intent.getBooleanExtra(EXTRA_CHANGED, false)
        val testOnly = intent.getBooleanExtra(EXTRA_TEST_ONLY, false)
        setContent {
            MaterialTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(if (changed) "SSH host key changed" else "Trust SSH host key?") },
                    text = { Text(buildString {
                        if (changed) append("Warning: the previously trusted key for the $hop host has changed. This can indicate a server reinstall or a man-in-the-middle attack. Verify the fingerprint through a trusted channel before replacing it.\n\n")
                        else append("This is the first connection to the $hop SSH host. Verify its fingerprint through a trusted channel.\n\n")
                        append("Algorithm: $algorithm\nFingerprint: $fingerprint")
                    }) },
                    confirmButton = { TextButton(onClick = {
                        if (ConfigStore(this).trustSshHostKey(profileId, hop, fingerprint)) {
                            if (testOnly) ProxyVpnService.test(this) else ProxyVpnService.reconnect(this)
                        }
                        finish()
                    }) { Text(if (changed) "Replace trusted key" else "Trust and connect") } },
                    dismissButton = { TextButton(onClick = { ProxyVpnService.dismissHostKeyPrompt(this); finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_HOP = "hop"
        const val EXTRA_ALGORITHM = "algorithm"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_CHANGED = "changed"
        const val EXTRA_TEST_ONLY = "test_only"
    }
}
