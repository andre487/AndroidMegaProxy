package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockingDetectionTest {
    @Test fun socksHandshakeFailuresUseProxyRecoveryWithoutClaimingTls() {
        assertEquals(BlockingSignal.CONNECT_RESPONSE_TIMEOUT,
            BlockingDetection.classify("event=connection protocol=socks5 stage=socks_handshake result=failed reason=timeout"))
        for (reason in listOf("reset", "eof")) assertEquals(BlockingSignal.CONNECT_RESPONSE_RESET,
            BlockingDetection.classify("event=connection protocol=socks5 stage=socks_handshake result=failed reason=$reason"))
        assertNull(BlockingDetection.classify("event=connection protocol=socks5 stage=socks_handshake reason=proxy_authentication"))
        assertNull(BlockingDetection.classify("event=connection protocol=socks5 stage=socks_handshake reason=timeout scope=target"))
    }

    @Test fun classifiesTimeoutAndReset() {
        assertEquals(BlockingSignal.TCP_TIMEOUT, BlockingDetection.classify("dial tcp: i/o timeout"))
        assertEquals(BlockingSignal.CONNECTION_RESET, BlockingDetection.classify("connection reset by peer"))
        assertEquals(
            BlockingSignal.TLS_HANDSHAKE_RESET,
            BlockingDetection.classify("event=connection stage=tls_handshake result=failed reason=reset"),
        )
        assertEquals(
            BlockingSignal.CONNECT_RESPONSE_TIMEOUT,
            BlockingDetection.classify("event=connection stage=connect_response result=failed reason=timeout"),
        )
        assertEquals(
            BlockingSignal.SSH_HANDSHAKE_TIMEOUT,
            BlockingDetection.classify("SSH destination handshake: i/o timeout"),
        )
    }

    @Test fun doesNotTreatAuthenticationOrCertificateErrorsAsBlocking() {
        assertNull(BlockingDetection.classify("unable to authenticate: i/o timeout"))
        assertNull(BlockingDetection.classify("x509 certificate error"))
        assertNull(BlockingDetection.classify("connection refused"))
    }
    @Test fun targetFailuresAndLocalDeadlinesDoNotRestartTheVpn() {
        assertNull(BlockingDetection.classify("event=connection stage=connect_response reason=timeout scope=target dpi_hint=none"))
        assertNull(BlockingDetection.classify("event=connection stage=tunnel_io reason=reset scope=target dpi_hint=none"))
        assertNull(BlockingDetection.classify("event=connection stage=tunnel_io reason=timeout scope=local_deadline dpi_hint=none"))
        assertEquals(BlockingSignal.TLS_HANDSHAKE_TIMEOUT, BlockingDetection.classify("event=connection stage=tls_handshake reason=timeout scope=proxy dpi_hint=possible_tls_interference"))
    }

    @Test fun oneQuicSessionFailureIsNotSeveralIndependentFailures() {
        val evidence = BlockingEvidence()
        assertEquals(1, evidence.failure(0, "quic_session=123 conn=1"))
        repeat(100) { assertEquals(0, evidence.failure(10, "conn=$it quic_session=123")) }
        assertEquals(2, evidence.failure(20, "quic_session=456"))
        assertEquals(0, evidence.failure(30, "quic_session=456"))
    }

    @Test fun failuresMustBeCloseTogetherAndUninterruptedBySuccess() {
        val evidence = BlockingEvidence()
        assertEquals(1, evidence.failure(0, "handshake timeout"))
        assertEquals(1, evidence.failure(60_001, "handshake timeout"))
        evidence.recovered()
        assertEquals(1, evidence.failure(60_002, "quic_session=10"))
        evidence.recovered()
        assertEquals(1, evidence.failure(60_003, "quic_session=11"))
        assertEquals(2, evidence.failure(60_004, "quic_session=12"))
    }

    @Test fun onlySuccessfulProxyTunnelsProveRecovery() {
        org.junit.Assert.assertTrue(isProxyTunnelEstablished("event=connection protocol=http3 stage=tunnel result=established"))
        org.junit.Assert.assertTrue(isProxyTunnelEstablished("event=connection protocol=http2 stage=tunnel result=established"))
        org.junit.Assert.assertFalse(isProxyTunnelEstablished("event=connection mode=direct stage=tunnel result=established"))
        org.junit.Assert.assertFalse(isProxyTunnelEstablished("event=connection protocol=http3 hop=jump stage=tunnel result=established"))
        org.junit.Assert.assertFalse(isProxyTunnelEstablished("event=masque_session protocol=http3 result=established"))
        org.junit.Assert.assertFalse(isProxyTunnelEstablished("event=connection protocol=http3 stage=tunnel result=failed"))
    }
}
