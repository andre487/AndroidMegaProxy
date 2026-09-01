package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockingDetectionTest {
    @Test fun classifiesTimeoutAndReset() {
        assertEquals(BlockingSignal.TCP_TIMEOUT, BlockingDetection.classify("dial tcp: i/o timeout"))
        assertEquals(BlockingSignal.CONNECTION_RESET, BlockingDetection.classify("connection reset by peer"))
    }

    @Test fun doesNotTreatAuthenticationOrCertificateErrorsAsBlocking() {
        assertNull(BlockingDetection.classify("unable to authenticate: i/o timeout"))
        assertNull(BlockingDetection.classify("x509 certificate error"))
        assertNull(BlockingDetection.classify("connection refused"))
    }
}
