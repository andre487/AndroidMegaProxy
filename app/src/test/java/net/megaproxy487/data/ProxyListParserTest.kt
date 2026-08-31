package net.megaproxy487.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyListParserTest {
    @Test
    fun parsesHttpsProxyUriAndTitle() {
        val result = ProxyListParser.parse(
            "https://user:p%40ss%3Aword@example.com:8443?title=Amsterdam&cc=NL",
        ).getOrThrow().proxies.single()

        assertEquals("Amsterdam", result.name)
        assertEquals("NL", result.countryCode)
        assertEquals("example.com", result.config.host)
        assertEquals(8443, result.config.port)
        assertEquals("user", result.config.username)
        assertEquals("p@ss:word", result.config.password)
        assertTrue(!result.config.routeAllApps)
    }

    @Test
    fun preservesLiteralPlusInBasicAuthAndDefaultsPort() {
        val result = ProxyListParser.parse("https://user:abc+123@example.com").getOrThrow().proxies.single()

        assertEquals(443, result.config.port)
        assertEquals("abc+123", result.config.password)
    }

    @Test
    fun skipsNonHttpsProxyAndReportsIt() {
        val result = ProxyListParser.parse(
            "https://user:password@example.com\nhttp://user:password@example.net:8080",
        ).getOrThrow()

        assertEquals(1, result.proxies.size)
        assertEquals(1, result.skippedNonHttps)
    }
}
