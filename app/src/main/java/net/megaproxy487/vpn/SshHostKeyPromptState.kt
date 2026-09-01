package net.megaproxy487.vpn

import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    val pending = mutableStateOf<PendingSshHostKey?>(null)

    fun show(value: PendingSshHostKey) {
        update(value)
    }

    fun clear() {
        update(null)
    }

    private fun update(value: PendingSshHostKey?) {
        if (Looper.myLooper() == Looper.getMainLooper()) pending.value = value
        else mainHandler.post { pending.value = value }
    }
}
