package net.megaproxy487

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalVpnVerdictTest {
    @Test fun headerValuesMapToVpnVerdicts() {
        assertEquals(ExternalVpnVerdict.NOT_DETECTED, parseExternalVpnVerdict("0"))
        assertEquals(ExternalVpnVerdict.DETECTED, parseExternalVpnVerdict("1"))
        assertEquals(ExternalVpnVerdict.UNKNOWN_FORMAT, parseExternalVpnVerdict(null))
        assertEquals(ExternalVpnVerdict.UNKNOWN_FORMAT, parseExternalVpnVerdict("unexpected"))
    }

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
}
