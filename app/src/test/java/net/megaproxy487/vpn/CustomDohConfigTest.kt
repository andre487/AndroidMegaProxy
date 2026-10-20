package net.megaproxy487.vpn

import android.app.Application
import android.net.VpnService
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomDohConfigTest {
    @Test fun customEndpointReachesNativeConfigurationWithoutDefaultFallbacks() {
        val core = NativeProxyCore(VpnService())
        for (type in ProxyType.entries) {
            for (url in listOf("https://dns.example", "HTTPS://192.0.2.1:8443/query?key=synthetic",
                "https://[2001:db8::1]:8443/dns-query")) {
                val config = ProxyConfig(type = type, dnsProvider = DnsProvider.CUSTOM, customDohUrl = "  $url  ")
                val json = JSONObject(core.configJson(config))
                assertEquals(type.name, json.getString("type"))
                assertEquals(url, json.getString("dohUrl"))
                assertEquals(0, json.getJSONArray("dohFallbackUrls").length())
            }
        }
        val builtIn = JSONObject(core.configJson(ProxyConfig(customDohUrl = "invalid but unused")))
        assertEquals(DnsProvider.CLOUDFLARE.url, builtIn.getString("dohUrl"))
    }
}
