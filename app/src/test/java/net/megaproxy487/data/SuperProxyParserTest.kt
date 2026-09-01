package net.megaproxy487.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperProxyParserTest {
    @Test
    fun `imports Super Proxy v1 HTTPS list and ignores certificate pin`() {
        val text = """
            # superproxy:proxylist:v1
            https://user:p%40ss@proxy.example?fingerprint=sha256%2Fignored
        """.trimIndent()

        assertTrue(SuperProxyParser.matches(text))
        val proxy = SuperProxyParser.parse(text).getOrThrow().proxies.single()
        assertEquals("proxy.example", proxy.config.host)
        assertEquals(443, proxy.config.port)
        assertEquals("user", proxy.config.username)
        assertEquals("p@ss", proxy.config.password)
    }

    @Test
    fun `rejects a generic proxy list as Super Proxy config`() {
        check(SuperProxyParser.parse("https://user:pass@proxy.example").isFailure)
    }
}
