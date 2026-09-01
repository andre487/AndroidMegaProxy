package net.megaproxy487.vpn

import androidx.compose.runtime.mutableStateOf

data class PendingSshHostKey(
    val profileId: String,
    val hop: String,
    val algorithm: String,
    val fingerprint: String,
    val changed: Boolean,
    val testOnly: Boolean,
)

object SshHostKeyPromptState {
    val pending = mutableStateOf<PendingSshHostKey?>(null)

    fun show(value: PendingSshHostKey) {
        pending.value = value
    }

    fun clear() {
        pending.value = null
    }
}
