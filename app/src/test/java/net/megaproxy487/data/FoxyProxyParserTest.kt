package net.megaproxy487.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FoxyProxyParserTest {
    @Test
    fun `imports only TLS proxy entries from current FoxyProxy JSON`() {
        val result = FoxyProxyParser.parse(
            """
            {
              "mode":"pattern",
              "data":[
                {
                  "active":true,
                  "title":"Amsterdam",
                  "type":"https",
                  "hostname":"proxy.example",
                  "port":"8443",
                  "username":"user",
                  "password":"secret",
                  "cc":"nl"
                },
                {
                  "title":"Not supported",
                  "type":"socks5",
                  "hostname":"socks.example",
                  "port":"1080"
                }
              ]
            }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(1, result.skippedNonHttps)
        val proxy = result.proxies.single()
        assertEquals("Amsterdam", proxy.name)
        assertEquals("NL", proxy.countryCode)
        assertEquals("proxy.example", proxy.config.host)
        assertEquals(8443, proxy.config.port)
        assertEquals("user", proxy.config.username)
        assertEquals("secret", proxy.config.password)
    }

    @Test
    fun `rejects FoxyProxy config without HTTPS proxies`() {
        val result = FoxyProxyParser.parse(
            """{"data":[{"type":"http","hostname":"proxy.example","port":8080}]}""",
        )
        check(result.isFailure)
    }
}
