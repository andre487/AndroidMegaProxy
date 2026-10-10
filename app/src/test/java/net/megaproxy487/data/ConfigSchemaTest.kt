package net.megaproxy487.data

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import net.megaproxy487.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

internal object ConfigSchemas {
    fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private val mapper = ObjectMapper()
    private val schemas = listOf("android-v8.schema.json", "megaproxy-v8.schema.json").map {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.readTree(bytes(it)))
    }
    fun assertValid(text: String) {
        schemas.forEach { schema ->
            val errors = schema.validate(mapper.readTree(text))
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
        }
    }
    fun rejects(text: String) = schemas.all { it.validate(mapper.readTree(text)).isNotEmpty() }
}

class ConfigSchemaTest {
    @Test fun upstreamConfigurationSubscriptionIsRecognizedAndUnknownKeysAreReported() {
        val root = JSONObject(String(ConfigSchemas.bytes("browser-v8.json")))
        root.remove("browser")
        val profiles = root.getJSONArray("profiles")
        for (index in 0 until profiles.length()) profiles.getJSONObject(index).remove("browser")
        assertEquals(ConfigImportNotice(), ConfigImportNotices.inspect(root))
        root.getJSONObject("subscription").put("future", "private-value")
        assertEquals(ConfigImportNotice(unknownFields = true), ConfigImportNotices.inspect(root))
    }

    @Test fun vendoredFilesMatchPinnedCommitAndChecksums() {
        val lock = JSONObject(String(ConfigSchemas.bytes("schema-lock.json")))
        assertEquals("https://github.com/andre487/MegaProxyConfig", lock.getString("repository"))
        assertTrue(lock.getString("commit").matches(Regex("[a-f0-9]{40}")))
        val files = lock.getJSONObject("files")
        assertEquals(setOf("android-v8.schema.json", "megaproxy-v8.schema.json", "android-v8.json", "browser-v8.json", "LICENSE"), files.keySet())
        files.keys().forEach { name ->
            val hash = MessageDigest.getInstance("SHA-256").digest(ConfigSchemas.bytes(name))
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, files.getString(name), hash)
        }
    }

    @Test fun upstreamExamplesValidateAndImportWithExpectedPlatformNotice() {
        for (name in listOf("android-v8.json", "browser-v8.json")) {
            val text = String(ConfigSchemas.bytes(name))
            ConfigSchemas.assertValid(text)
            val imported = ConfigTransfer.importJson(text)
            assertEquals(ConfigImportNotice(browserFields = name == "browser-v8.json"), imported.notice)
            assertFalse(imported.profiles.isEmpty())
            if (name == "android-v8.json") {
                val global = imported.globalConnectionSettings!!
                val masque = imported.profiles.single { it.config.type == ProxyType.MASQUE }.config
                assertEquals(TlsProfile.CUSTOM, global.tlsProfile)
                assertNotEquals(global.customJa3, masque.customJa3)
                assertTrue(validQuicJa3(masque.customJa3))
                assertEquals(masque.customJa3, global.applyTo(masque).customJa3)
                val https = imported.profiles.single { it.config.type == ProxyType.HTTPS }.config
                assertEquals(global.customJa3, global.applyTo(https).customJa3)
            }
        }
    }

    @Test fun schemaTracksEveryExportedEnumAndVersion() {
        for (name in listOf("android-v8.schema.json", "megaproxy-v8.schema.json")) {
            val schema = JSONObject(String(ConfigSchemas.bytes(name)))
            assertEquals(ConfigTransfer.SCHEMA_VERSION, schema.getJSONObject("properties").getJSONObject("version").getInt("const"))
            val defs = schema.getJSONObject("\$defs")
            fun check(definition: String, field: String, values: Set<String>) {
                val array = defs.getJSONObject(definition).getJSONObject("properties").getJSONObject(field).getJSONArray("enum")
                val supported = (0 until array.length()).map { array.getString(it) }.toSet()
                if (name == "megaproxy-v8.schema.json" && definition == "proxy" && field == "type") {
                    assertTrue("Shared schema must include every Android transport", supported.containsAll(values))
                } else assertEquals("$name: $definition.$field", values, supported)
            }
            check("proxy", "type", ProxyType.entries.map { it.name }.toSet())
            check("proxy", "sshProfile", SshProfile.entries.map { it.name }.toSet())
            check("ssh", "fingerprint", SshProfile.entries.map { it.name }.toSet())
            check("ssh", "authMode", SshAuthMode.entries.map { it.name }.toSet())
            check("tls", "fingerprint", TlsProfile.entries.map { it.name }.toSet())
            check("dns", "provider", DnsProvider.entries.map { it.name }.toSet())
            check("failover", "mode", FailoverMode.entries.map { it.name }.toSet())
        }
    }

    @Test fun schemasRejectMalformedCanonicalConfiguration() {
        val valid = String(ConfigSchemas.bytes("android-v8.json"))
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("version", 9) },
            { it.getJSONArray("profiles").getJSONObject(0).remove("id") },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("port", 65536) },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("type", "HTTP") },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("type", "SSH_JUMP").remove("jump") },
        )) {
            assertTrue(ConfigSchemas.rejects(JSONObject(valid).also(change).toString()))
        }
    }

    @Test fun noticesDistinguishBrowserSettingsAndUnknownKeysAtEveryObjectDepth() {
        val valid = String(ConfigSchemas.bytes("android-v8.json"))
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("future", true) },
            { it.getJSONArray("profiles").getJSONObject(0).put("future", true) },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("future", "secret") },
            { it.put("tls", JSONObject().put("future", true)) },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("jump", JSONObject().put("host", "jump.example").put("port", 22).put("future", true)) },
        )) {
            val imported = ConfigTransfer.importJson(JSONObject(valid).also(change).toString())
            assertEquals(ConfigImportNotice(unknownFields = true), imported.notice)
        }
        val root = JSONObject(valid).put("browser", JSONObject().put("theme", "dark"))
        assertEquals(ConfigImportNotice(browserFields = true), ConfigTransfer.importJson(root.toString()).notice)
        root.getJSONObject("browser").put("routing", JSONObject().put("assignments", org.json.JSONArray().put(
            JSONObject().put("domain", "example.com").put("profileId", "one").put("future", "secret"))))
        assertEquals(ConfigImportNotice(true, true), ConfigTransfer.importJson(root.toString()).notice)
        root.remove("browser")
        root.getJSONArray("profiles").getJSONObject(0).put("browser", JSONObject().put("knockHost", "knock.example"))
        assertEquals(ConfigImportNotice(browserFields = true), ConfigTransfer.importJson(root.toString()).notice)
    }
}
