package net.megaproxy487.vpn

enum class BlockingSignal { TCP_TIMEOUT, HANDSHAKE_TIMEOUT, CONNECTION_RESET, SILENT_DROP }

object BlockingDetection {
    fun classify(message: String): BlockingSignal? {
        val value = message.lowercase()
        if (listOf("authenticate", "credentials", "host_key", "certificate", "x509", "invalid ", "refused").any(value::contains)) return null
        return when {
            "handshake" in value && "timeout" in value -> BlockingSignal.HANDSHAKE_TIMEOUT
            "reset" in value || "broken pipe" in value -> BlockingSignal.CONNECTION_RESET
            "i/o timeout" in value || "timed out" in value -> BlockingSignal.TCP_TIMEOUT
            "unexpected eof" in value || "silent" in value -> BlockingSignal.SILENT_DROP
            else -> null
        }
    }
}
