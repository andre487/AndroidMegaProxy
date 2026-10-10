package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTestResultTest {
    @Test
    fun parsesIndependentHttp3ResultsAndIgnoresUnknownProviders() {
        val result = parseConnectionTestResult("""{"exitIp":"203.0.113.7","http3":[{"provider":"www.cloudflare.com","status":"confirmed"},{"provider":"quic.browserleaks.com","status":"unavailable"},{"provider":"unknown","status":"confirmed"}]}""")
        assertEquals(listOf(Http3ProbeResult("Cloudflare", true), Http3ProbeResult("BrowserLeaks", false)), result.http3)
        assertEquals(emptyList<Http3ProbeResult>(), parseConnectionTestResult("""{"exitIp":"203.0.113.7"}""").http3)
    }

    @Test
    fun parsesIpAndCountryFromNativeResult() {
        assertEquals(
            ConnectionTestResult("203.0.113.7", "NL"),
            parseConnectionTestResult("""{"exitIp":"203.0.113.7","countryCode":"NL"}"""),
        )
    }

    @Test
    fun acceptsUnavailableOptionalCountry() {
        val result = parseConnectionTestResult("""{"exitIp":"2001:db8::7"}""")
        assertEquals("2001:db8::7", result.exitIp)
        assertNull(result.countryCode)
    }
}
