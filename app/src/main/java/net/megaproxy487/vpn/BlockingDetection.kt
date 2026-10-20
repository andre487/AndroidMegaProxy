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

/** Count independent failures within a bounded window, rather than every failed stream. */
internal class BlockingEvidence {
    private var firstFailureAt: Long? = null
    private var count = 0
    private val failedSessions = mutableSetOf<String>()

    @Synchronized fun failure(now: Long, detail: String): Int {
        val first = firstFailureAt
        if (first == null || now - first > 60_000L) {
            firstFailureAt = now
            count = 0
            failedSessions.clear()
        }
        val session = detail.split(' ').firstOrNull { it.startsWith("quic_session=") }
            ?.removePrefix("quic_session=")?.takeUnless { it == "0" }
        if (session != null && !failedSessions.add(session)) return 0
        return ++count
    }

    @Synchronized fun recovered() {
        firstFailureAt = null
        count = 0
        failedSessions.clear()
    }
}

internal fun isProxyTunnelEstablished(message: String): Boolean =
    "event=connection" in message && "stage=tunnel" in message && "hop=jump" !in message &&
        transportProtocolFromDiagnostic(message)?.let { it != VpnTransportProtocol.SSH_MULTIPLEXED } == true

/** Upstream reachability failures refresh bootstrap DNS without claiming interference. */
internal fun isBootstrapConnectionFailure(message: String): Boolean =
    "scope=target" !in message && "mode=direct" !in message &&
        ("stage=tcp_connect" in message || "stage=socks_handshake" in message ||
            ("stage=tls_handshake" in message && "protocol=http3" in message) ||
            ("stage=direct_tcpip" in message && "event=ssh_transport" in message)) &&
        "result=failed" in message &&
        ("reason=refused" in message || "reason=unreachable" in message)
