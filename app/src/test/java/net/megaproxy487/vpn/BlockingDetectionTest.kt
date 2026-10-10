package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockingDetectionTest {
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
}
