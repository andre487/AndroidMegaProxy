package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTestResultTest {
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
