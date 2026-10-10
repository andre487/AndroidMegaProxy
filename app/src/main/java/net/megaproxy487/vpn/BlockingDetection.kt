package net.megaproxy487.vpn

enum class BlockingSignal {
    TCP_TIMEOUT,
    TLS_HANDSHAKE_TIMEOUT,
    TLS_HANDSHAKE_RESET,
    CONNECT_RESPONSE_TIMEOUT,
    CONNECT_RESPONSE_RESET,
    SSH_HANDSHAKE_TIMEOUT,
    CONNECTION_RESET,
    SILENT_DROP,
}

object BlockingDetection {
    fun classify(message: String): BlockingSignal? {
        val value = message.lowercase()
        if (listOf("scope=target", "scope=local_deadline", "authenticate", "credentials", "host_key", "certificate", "x509", "invalid ", "refused").any(value::contains)) return null
        return when {
            "stage=tls_handshake" in value && "reason=timeout" in value -> BlockingSignal.TLS_HANDSHAKE_TIMEOUT
            "stage=tls_handshake" in value && ("reason=reset" in value || "reason=eof" in value) -> BlockingSignal.TLS_HANDSHAKE_RESET
            ("stage=connect_response" in value || "stage=socks_handshake" in value) && "reason=timeout" in value -> BlockingSignal.CONNECT_RESPONSE_TIMEOUT
            ("stage=connect_response" in value || "stage=socks_handshake" in value) && ("reason=reset" in value || "reason=eof" in value) -> BlockingSignal.CONNECT_RESPONSE_RESET
            "ssh" in value && "handshake" in value && ("timeout" in value || "timed out" in value) -> BlockingSignal.SSH_HANDSHAKE_TIMEOUT
            "handshake" in value && "timeout" in value -> BlockingSignal.TLS_HANDSHAKE_TIMEOUT
            "reset" in value || "broken pipe" in value -> BlockingSignal.CONNECTION_RESET
            "i/o timeout" in value || "timed out" in value -> BlockingSignal.TCP_TIMEOUT
            "unexpected eof" in value || "silent" in value -> BlockingSignal.SILENT_DROP
            else -> null
        }
    }
}

/** Error strings may contain certificate identities or peer-controlled text. */
internal fun nativeFailureReason(message: String): String {
    val value = message.lowercase()
    return when {
        "ssh_host_key_unknown" in value -> "host_key_unknown"
        "ssh_host_key_changed" in value -> "host_key_changed"
        "certificate" in value || "x509" in value -> "certificate"
        listOf("authentication", "authenticate", "credentials", "status 407").any(value::contains) -> "authentication"
        "protect rejected" in value -> "vpn_protect"
        "timeout" in value || "timed out" in value -> "timeout"
        "reset" in value || "broken pipe" in value -> "reset"
        "refused" in value -> "refused"
        "unreachable" in value -> "unreachable"
        "eof" in value -> "eof"
        else -> "other"
    }
}
