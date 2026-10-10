package net.megaproxy487

import net.megaproxy487.data.*
import net.megaproxy487.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

class ConfigSubscriptionTest {
    @Test fun strictPortableSettingsRejectMalformedAndUnsafeInputs() {
        val invalid = listOf("{}", """{"url":123}""", """{"url":"http://example.com"}""",
            """{"url":"https://user:pass@example.com"}""", """{"url":"https://example.com/#token"}""",
            """{"url":"https://example.com","enabled":"false"}""",
            """{"url":"https://example.com","intervalMinutes":1.1}""",
            """{"url":"https://example.com","intervalMinutes":10081}""",
            """{"url":"https://example.com","password":null}""",
            """{"url":"https://example.com","username":"x:y"}""",
            """{"url":"https://example.com","fallbackUrls":["https://EXAMPLE.com:443/"]}""",
            """{"url":"https://example.com","fallbackUrls":[true]}""")
        invalid.forEach { input -> assertTrue(input, runCatching { ConfigSubscription.fromJson(JSONObject(input)) }.isFailure) }
        val settings = ConfigSubscription.fromJson(JSONObject("""{"url":"https://example.com","future":"ignored"}"""))
        assertEquals(60, settings.intervalMinutes)
        assertTrue(settings.enabled)
        assertFalse(settings.toJson().has("future"))
        assertTrue(runCatching { settings.copy(password = "x\nsecret").validate() }.isFailure)
        assertTrue(runCatching { settings.copy(url = "https://example.com:65536").validate() }.isFailure)
        assertTrue(runCatching { settings.copy(fallbackUrls = List(8) { "https://$it.example.com" }).validate() }.isFailure)
    }

    @Test fun onlySameUrlAndUserCanRetainAnOmittedPassword() {
        val old = ConfigSubscription("https://example.com/", username = "one", password = "secret")
        assertEquals("secret", old.copy(url = "https://EXAMPLE.com:443/", password = null).retainPassword(old).password)
        assertNull(old.copy(url = "https://other.example.com/", password = null).retainPassword(old).password)
        assertNull(old.copy(username = "two", password = null).retainPassword(old).password)
        assertEquals("", old.copy(password = "").retainPassword(old).password)
        val passwordOnly = old.copy(username = "")
        assertEquals("secret", passwordOnly.copy(username = null, password = null).retainPassword(passwordOnly).password)
    }

    private class Connection(private val body: ByteArray, private val status: Int = 200, private val encoding: String? = null) :
        HttpURLConnection(URL("https://feed.example.com/")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentEncoding() = encoding
    }

    @Test fun requestsUseUtf8AuthAndStableHeadersWithoutRedirectsOrCaching() {
        val settings = ConfigSubscription("https://feed.example.com/", username = "читатель", password = "пароль")
        val connection = Connection("body".toByteArray())
        assertEquals("body", downloadConfiguration(settings, settings.url, { connection }))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("читатель:пароль".toByteArray()), connection.getRequestProperty("Authorization"))
        assertEquals("android", connection.getRequestProperty("X-MegaProxy-Client"))
        assertEquals(BuildConfig.VERSION_NAME, connection.getRequestProperty("X-MegaProxy-Version"))
        assertEquals("", connection.getRequestProperty("Cookie"))
        assertNull(connection.getRequestProperty("Referer"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.disconnected)
        val public = Connection(byteArrayOf())
        downloadConfiguration(settings.copy(username = null, password = null), settings.url, { public })
        assertNull(public.getRequestProperty("Authorization"))
    }

    @Test fun redirectsHttpErrorsInvalidUtf8AndDecodedOversizeBodiesFail() {
        val settings = ConfigSubscription("https://feed.example.com/")
        for (code in listOf(301, 302, 307, 304, 401, 403, 404, 429, 500)) {
            val connection = Connection(byteArrayOf(), code)
            assertTrue(runCatching { downloadConfiguration(settings, settings.url, { connection }) }.isFailure)
            assertTrue(connection.disconnected)
        }
        val bytes = ByteArray(MAX_CONFIG_FILE_BYTES + 1) { 97 }
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write(bytes) }
        for (connection in listOf(Connection(bytes), Connection(output.toByteArray(), encoding = "gzip"), Connection(byteArrayOf(0xc3.toByte())))) {
            assertTrue(runCatching { downloadConfiguration(settings, settings.url, { connection }) }.isFailure)
            assertTrue(connection.disconnected)
        }
        assertTrue(runCatching { downloadConfiguration(settings, "https://unlisted.example/", { error("must not open") }) }.isFailure)
    }

    @Test fun legacySnapshotsReuseOnlyUniqueOwnedMatchesAndDoNotImportPreferences() {
        val old = ProxyProfile("stable", "Old name", 0, "", ProxyConfig(type = ProxyType.SOCKS5, host = "proxy.example", port = 1080))
        val parsed = parseSubscriptionSnapshot("socks5://proxy.example?title=New+name", listOf(old))
        assertEquals("stable", parsed.profiles.single().id)
        assertNull(parsed.globalConnectionSettings)
        val ambiguous = parseSubscriptionSnapshot("socks5://proxy.example\nsocks5://proxy.example", listOf(old))
        assertEquals(2, ambiguous.profiles.map { it.id }.distinct().size)
        assertFalse(ambiguous.profiles.any { it.id == "stable" })
    }

    @Test fun downloadedSubscriptionCannotChangeSettingsAndInvalidReferencesFail() {
        val valid = """{"schema":"net.megaproxy487.config","version":8,"profiles":[{"id":"one","proxy":{"type":"SOCKS5","host":"proxy.example","port":1080}}],"subscription":{"url":"http://evil.example","username":123}}"""
        val parsed = parseSubscriptionSnapshot(valid, emptyList())
        assertFalse(parsed.subscriptionPresent)
        assertNull(parsed.subscription)
        val malformed = JSONObject(valid).apply {
            getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("port", "1080")
        }
        assertTrue(runCatching { parseSubscriptionSnapshot(malformed.toString(), emptyList()) }.isFailure)
        val root = JSONObject(valid).put("activeProfileId", "missing")
        assertTrue(runCatching { parseSubscriptionSnapshot(root.toString(), emptyList()) }.isFailure)
        assertTrue(runCatching { parseSubscriptionSnapshot("<html>login</html>", emptyList()) }.isFailure)
        assertTrue(runCatching { parseSubscriptionSnapshot("", emptyList()) }.isFailure)
    }

    @Test fun schedulingRespectsIntervalAndClockRollback() {
        val state = ConfigSubscriptionState(ConfigSubscription("https://example.com", intervalMinutes = 1), lastAttempt = 100_000)
        assertEquals(0, subscriptionDelay(state.copy(lastAttempt = 0), 1))
        assertEquals(50_000, subscriptionDelay(state, 110_000))
        assertEquals(0, subscriptionDelay(state, 200_000))
        assertEquals(60_000, subscriptionDelay(state, 10_000))
    }
}
