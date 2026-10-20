package net.megaproxy487

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyCheckVerdictTest {
    @Test fun proxyCheckResponseMapsDetectionAndLimitStates() {
        val clear = """{"status":"ok","198.51.100.1":{"detections":{"vpn":false,"proxy":false,"tor":false,"anonymous":false}}}"""
        val detected = """{"status":"ok","203.0.113.1":{"detections":{"vpn":true,"proxy":false,"tor":false,"anonymous":true}}}"""
        val limited = """{"status":"denied","message":"100 queries exhausted, try again tomorrow."}"""
        assertEquals(ProxyCheckVerdict.NOT_DETECTED, parseProxyCheckResponse(200, clear).verdict)
        assertEquals(ProxyCheckVerdict.DETECTED, parseProxyCheckResponse(200, detected).verdict)
        assertEquals(ProxyCheckVerdict.LIMIT_REACHED, parseProxyCheckResponse(429, limited).verdict)
        assertEquals(ProxyCheckVerdict.UNKNOWN_FORMAT, parseProxyCheckResponse(200, "{}").verdict)
    }

    @Test fun proxyCheckCacheExpiresAfterOneDay() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(true, isFreshProxyCheckCache(1_000, 1_000 + day - 1))
        assertEquals(false, isFreshProxyCheckCache(1_000, 1_000 + day))
        assertEquals(false, isFreshProxyCheckCache(2_000, 1_000))
    }

    @Test fun exitIpParserAcceptsOnlyNumericAddresses() {
        assertEquals(true, isIpAddress("198.51.100.1"))
        assertEquals(true, isIpAddress("2001:db8::1"))
        assertEquals(false, isIpAddress("<html>error</html>"))
        assertEquals(false, isIpAddress("999.999.999.999"))
    }

    @Test fun parsesCloudflareTraceAndCalculatesTransferSpeed() {
        val trace = parseCloudflareTrace("ip=203.0.113.1\nloc=NL\nhttp=h2\ntls=TLSv1.3\n")
        assertEquals("203.0.113.1", trace["ip"])
        assertEquals("NL", trace["loc"])
        assertEquals("h2", trace["http"])
        assertEquals(listOf(20, 60, 100), SPEED_TEST_MEBIBYTES)
        assertEquals(80.0, transferMbps(10_000_000, 1_000), 0.001)
        assertEquals(0.0, transferMbps(10_000_000, 0), 0.001)
    }
}
