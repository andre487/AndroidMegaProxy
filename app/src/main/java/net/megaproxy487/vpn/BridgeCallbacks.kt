package net.megaproxy487.vpn

import mobile.Protector
import mobile.Reporter

/** These interfaces have no Go error result. Never leave a recoverable JNI exception pending. */
internal class BridgeProtector(
    private val enabled: () -> Boolean,
    private val protect: (Int) -> Boolean,
    private val onFailure: (Exception) -> Unit,
) : Protector {
    override fun protect(fd: Long): Boolean {
        if (!enabled() || fd !in 0..Int.MAX_VALUE.toLong()) return false
        return try {
            protect(fd.toInt())
        } catch (error: Exception) {
            onFailure(error)
            false // Fail closed: Go must not dial an unprotected upstream socket.
        }
    }
}

internal class BridgeReporter(
    private val enabled: () -> Boolean,
    private val deliver: (String) -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Reporter {
    override fun report(message: String) {
        if (!enabled()) return
        try {
            deliver(message)
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}
