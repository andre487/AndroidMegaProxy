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


internal class BridgeSourceValidator(
    private val enabled: () -> Boolean,
    private val allowedUids: Set<Int>?,
    private val resolve: (Int, String, Int, String, Int) -> Int,
) : mobile.SourceValidator {
    override fun allow(protocol: Long, source: String, sourcePort: Long, destination: String, destinationPort: Long): Boolean {
        if (!enabled() || (protocol != 6L && protocol != 17L) || sourcePort !in 1..65535L || destinationPort !in 1..65535L) return false
        return try {
            val uid = resolve(protocol.toInt(), source, sourcePort.toInt(), destination, destinationPort.toInt())
            enabled() && uid >= 0 && (allowedUids == null || uid in allowedUids)
        } catch (_: Exception) {
            false
        }
    }
}
