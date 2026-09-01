package net.megaproxy487.data

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.TlsProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTransferTest {
    @Test
    fun `proxy list omits passwords by default`() {
        val profile = ProxyProfile(
            id = "one", name = "Amsterdam", colorIndex = 0, countryCode = "nl",
            config = ProxyConfig(host = "proxy.example", username = "user+name", password = "secret"),
        )
        val exported = ConfigTransfer.exportProxyList(listOf(profile), includePasswords = false)
        assertTrue(exported.contains("https://user%2Bname:@proxy.example"))
        assertFalse(exported.contains("secret"))
        val imported = ProxyListParser.parse(exported).getOrThrow().proxies.single()
        assertEquals("", imported.config.password)
        assertEquals("NL", imported.countryCode)
        assertEquals("Amsterdam", imported.name)
    }

    @Test
    fun `proxy list includes password when requested`() {
        val profile = ProxyProfile(
            id = "one", colorIndex = 0,
            config = ProxyConfig(host = "proxy.example", username = "user", password = "s e:c@r"),
        )
        val exported = ConfigTransfer.exportProxyList(listOf(profile), includePasswords = true)
        assertEquals("s e:c@r", ProxyListParser.parse(exported).getOrThrow().proxies.single().config.password)
    }

    @Test
    fun `json import preserves unavailable applications and tolerates future values`() {
        val imported = ConfigTransfer.importJson(
            """
            {
              "schema":"dev.megaproxy.config",
              "version":1,
              "activeProfileId":"source",
              "alwaysOnProfileId":"source",
              "profileSort":"FUTURE_SORT",
              "profiles":[{
                "id":"source",
                "name":"Portable",
                "color":999,
                "countryCode":"de",
                "proxy":{"host":"proxy.example","port":70000,"username":"u"},
                "tls":{"fingerprint":"FUTURE_BROWSER","customJa3":"future"},
                "dns":{"provider":"FUTURE_DNS","customDohUrl":"https://dns.example/query"},
                "routing":{"selectedPackages":["com.example.not.installed","invalid package"],"routeAllApps":false},
                "futureField":true
              }]
            }
            """.trimIndent(),
        )
        val profile = imported.profiles.single()
        assertEquals(443, profile.config.port)
        assertEquals(TlsProfile.DEFAULT, profile.config.profile)
        assertEquals(setOf("com.example.not.installed"), profile.config.selectedPackages)
        assertEquals("", profile.config.password)
        assertEquals("source", imported.activeProfileId)
    }

    @Test
    fun `unsupported global fingerprint falls back to default`() {
        val imported = ConfigTransfer.importJson(
            """
            {
              "schema":"dev.megaproxy.config",
              "version":2,
              "tls":{"fingerprint":"SAMSUNG_INTERNET","customJa3":"ignored"},
              "routing":{"selectedPackages":["com.example.missing"],"routeAllApps":false},
              "profiles":[{
                "id":"one",
                "proxy":{"host":"proxy.example","username":"user"}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(TlsProfile.DEFAULT, imported.globalConnectionSettings?.tlsProfile)
        assertEquals(setOf("com.example.missing"), imported.globalConnectionSettings?.selectedPackages)
    }

    @Test
    fun `version one keeps per-profile IPv6 setting`() {
        val imported = ConfigTransfer.importJson(
            """{"schema":"dev.megaproxy.config","version":1,"profiles":[{
              "id":"one","proxy":{"host":"proxy.example"},"routing":{"allowIpv6":true}
            }]}""",
        )

        assertTrue(imported.profiles.single().config.allowIpv6)
    }

    @Test
    fun `legacy global IPv6 setting migrates to every profile`() {
        val imported = ConfigTransfer.importJson(
            """{"schema":"dev.megaproxy.config","version":5,
              "routing":{"allowIpv6":true},"profiles":[
                {"id":"one","proxy":{"host":"one.example"}},
                {"id":"two","proxy":{"host":"two.example"}}
              ]}""",
        )

        assertTrue(imported.profiles.all { it.config.allowIpv6 })
    }
}
