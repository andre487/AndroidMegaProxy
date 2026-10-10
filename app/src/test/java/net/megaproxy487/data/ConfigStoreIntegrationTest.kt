package net.megaproxy487.data

import android.app.Application
import android.content.Context
import net.megaproxy487.UiTestKeyStore
import net.megaproxy487.UiTestKeyStoreProvider
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.TlsProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security

/** Real persistence/crypto with synthetic keys; no Compose, JNI or device Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfigStoreIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ConfigStore(context)
    private val prefs get() = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    @Before fun installKeys() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
    }

    @After fun removeKeys() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
    }

    private fun secretProfile(): ProxyProfile = store.activeProfile().let {
        it.copy(config = it.config.copy(
            type = ProxyType.SSH_JUMP, port = 22, jumpPort = 22,
            jumpHost = "jump.example", sameJumpAuthentication = false,
            host = "exit.example", password = "destination-secret",
            privateKey = "destination-private-key", jumpPassword = "jump-secret",
            jumpPrivateKey = "jump-private-key",
        ))
    }.also(store::saveProfile)

    private fun importProfile(profile: JSONObject) = store.importConfiguration(
        ConfigTransfer.importJson(JSONObject()
            .put("schema", ConfigTransfer.SCHEMA_ID)
            .put("version", ConfigTransfer.SCHEMA_VERSION)
            .put("profiles", JSONArray().put(profile)).toString()),
    )

    @Test fun http3PreferenceSurvivesStorageAndGlobalSettings() {
        val original = store.activeProfile()
        assertFalse(original.config.preferHttp3)
        store.saveProfile(original.copy(config = original.config.copy(preferHttp3 = true)))
        val reopened = ConfigStore(context).profile(original.id)!!.config
        assertTrue(reopened.preferHttp3)
        assertTrue(store.globalConnectionSettings().applyTo(reopened).preferHttp3)
    }

    @Test fun allCredentialFieldsSurviveReopeningAndAreEncryptedAtRest() {
        val original = secretProfile()
        assertEquals(original, store.profile(original.id))
        val raw = prefs.getString("profiles_v2", null)!!
        for (secret in listOf(original.config.password, original.config.privateKey,
            original.config.jumpPassword, original.config.jumpPrivateKey)) {
            assertFalse("Plaintext credential in preferences", raw.contains(secret))
        }
        store.saveProfile(original)
        assertNotEquals("AES-GCM must use fresh IVs", raw, prefs.getString("profiles_v2", null))
        assertEquals(original, store.profile(original.id))
    }

    @Test fun independentStoreInstancesDoNotLoseConcurrentProfileAdds() {
        val initial = store.activeProfile()
        val ready = java.util.concurrent.CountDownLatch(8)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val writes = (0 until 8).map { index -> pool.submit {
                val independent = ConfigStore(context)
                val added = initial.copy(id = "concurrent-$index", name = "Concurrent $index")
                ready.countDown()
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                independent.saveProfile(added, createIfMissing = true)
            } }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            writes.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(9, store.profiles().size)
            assertEquals((0 until 8).map { "concurrent-$it" }.toSet(),
                store.profiles().filter { it.id != initial.id }.map { it.id }.toSet())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun portableImportPreservesPerProfileIpv6BeforeGlobalSettingsAreRead() {
        val configuration = ConfigTransfer.importJson(
            """{"schema":"net.megaproxy487.config","version":8,"profiles":[{"id":"ipv6-import","proxy":{"type":"HTTPS","host":"proxy.example","port":443,"username":"user","password":"secret"},"routing":{"allowIpv6":true}}],"global":{}}"""
        )
        store.importConfiguration(configuration)
        store.globalConnectionSettings()
        assertTrue(store.profile("ipv6-import")!!.config.allowIpv6)
    }

    @Test fun malformedProfilesRemainUntouchedUntilExplicitBackupImport() {
        val original = secretProfile()
        val backup = ConfigTransfer.exportJson(store, true, true)
        val damaged = "[invalid"
        prefs.edit().putString("profiles_v2", damaged).commit()
        val placeholder = store.activeProfile()
        assertTrue(placeholder.config.storageUnavailable)
        assertEquals(placeholder.id, store.activeProfile().id)
        try {
            store.saveProfile(placeholder.copy(name = "Renamed"))
            fail("Corrupt storage must not be overwritten")
        } catch (error: net.megaproxy487.UiException) {
            assertEquals(net.megaproxy487.R.string.error_config_storage, error.textId)
        }
        try {
            ConfigTransfer.exportJson(store, false, false)
            fail("Corrupt storage must not be exported as an empty profile")
        } catch (error: net.megaproxy487.UiException) {
            assertEquals(net.megaproxy487.R.string.error_config_storage, error.textId)
        }
        assertEquals(damaged, prefs.getString("profiles_v2", null))
        store.importConfiguration(ConfigTransfer.importJson(backup))
        assertEquals(original, store.activeProfile())
        assertEquals(original.id, store.connectionProfile().id)
    }

    @Test fun unreadableCredentialSurvivesMetadataWrite() {
        val original = secretProfile()
        val packed = JSONArray(prefs.getString("profiles_v2", null))
        packed.getJSONObject(0).getJSONObject("config").put("password", "AAAA")
        prefs.edit().putString("profiles_v2", packed.toString()).commit()
        val loaded = store.profile(original.id)!!
        assertEquals(net.megaproxy487.R.string.error_credentials_unavailable, loaded.config.validationError())
        store.saveProfile(loaded.copy(name = "Renamed"))
        val saved = JSONArray(prefs.getString("profiles_v2", null))
        assertEquals("AAAA", saved.getJSONObject(0).getJSONObject("config").getString("password"))
        assertTrue(ConfigTransfer.exportJson(store, false, false).contains("Renamed"))
        try {
            ConfigTransfer.exportJson(store, true, false)
            fail("Unreadable passwords must not be exported as empty")
        } catch (error: net.megaproxy487.UiException) {
            assertEquals(net.megaproxy487.R.string.error_credentials_unavailable, error.textId)
        }
        // An omitted secret preserves the opaque value; an explicit empty value clears it.
        val exported = JSONObject(ConfigTransfer.exportJson(store, false, false))
        store.importConfiguration(ConfigTransfer.importJson(exported.toString()))
        assertEquals("AAAA", JSONArray(prefs.getString("profiles_v2", null))
            .getJSONObject(0).getJSONObject("config").getString("password"))
        exported.getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("password", "")
        store.importConfiguration(ConfigTransfer.importJson(exported.toString()))
        assertTrue(store.profile(original.id)!!.config.unreadableSecrets.isEmpty())
    }

    @Test fun unavailableKeyDoesNotCreateReplacementOrRewriteCiphertext() {
        val original = secretProfile()
        val encrypted = JSONObject(JSONArray(prefs.getString("profiles_v2", null)).getJSONObject(0)
            .getJSONObject("config").toString())
        val keys = UiTestKeyStore.keys.toMap()
        UiTestKeyStore.keys.clear()
        val loaded = store.profile(original.id)!!
        assertEquals(4, loaded.config.unreadableSecrets.size)
        store.saveProfile(loaded.copy(name = "Renamed"))
        val saved = JSONArray(prefs.getString("profiles_v2", null)).getJSONObject(0).getJSONObject("config")
        for (field in listOf("password", "privateKey", "jumpPassword", "jumpPrivateKey")) {
            assertEquals(encrypted.getString(field), saved.getString(field))
        }
        assertTrue("Reading an unavailable key must not replace it", UiTestKeyStore.keys.isEmpty())
        UiTestKeyStore.keys.putAll(keys)
        assertEquals(original.copy(name = "Renamed"), store.profile(original.id))
    }

    @Test fun actualExportsValidateForEveryTransportAndSecretChoice() {
        val original = secretProfile()
        for (type in ProxyType.entries) {
            store.saveProfile(original.copy(config = original.config.copy(type = type, port = type.defaultPort, jumpPort = type.defaultPort)))
            for (passwords in listOf(false, true)) for (keys in listOf(false, true)) {
                val text = ConfigTransfer.exportJson(store, passwords, keys)
                ConfigSchemas.assertValid(text)
                val imported = ConfigTransfer.importJson(text)
                assertEquals(ConfigImportNotice(), imported.notice)
                assertEquals(type, imported.profiles.single().config.type)
                assertEquals(passwords, text.contains("destination-secret"))
                assertEquals(keys, text.contains("destination-private-key"))
                assertEquals(passwords && type.hasJump, text.contains("jump-secret"))
                assertEquals(keys && type.hasJump, text.contains("jump-private-key"))
            }
        }
    }

    @Test fun mixedCustomFingerprintsSurvivePersistenceAndSchemaValidatedExport() {
        val tls = "771,4865,0-10-13-16-43-51,29,0"
        val quic = "771,4865,0-10-13-16-43-51-57,29,0"
        val original = store.activeProfile()
        val masque = original.copy(config = original.config.copy(type = ProxyType.MASQUE, host = "proxy.example", customJa3 = quic))
        store.saveProfile(masque)
        store.saveGlobalConnectionSettings(GlobalConnectionSettings(tlsProfile = TlsProfile.CUSTOM, customJa3 = tls))
        assertEquals(quic, store.profile(masque.id)!!.config.customJa3)
        val exported = ConfigTransfer.exportJson(store, false)
        ConfigSchemas.assertValid(exported)
        val imported = ConfigTransfer.importJson(exported)
        assertEquals(ConfigImportNotice(), imported.notice)
        val global = imported.globalConnectionSettings!!
        assertEquals(tls, global.customJa3)
        assertEquals(quic, global.applyTo(imported.profiles.single().config).customJa3)
    }

    @Test fun importWithoutSecretsPreservesAllExistingCredentials() {
        val original = secretProfile()
        val updated = original.copy(name = "Updated")
        val result = importProfile(ConfigTransfer.encodeProfile(updated, false, false))
        assertEquals(listOf(original.id), result.updated.map { it.id })
        assertEquals(updated, store.profile(original.id))
    }

    @Test fun explicitlyEmptyImportedSecretsClearAllExistingCredentials() {
        val original = secretProfile()
        val empty = original.copy(config = original.config.copy(
            password = "", privateKey = "", jumpPassword = "", jumpPrivateKey = "",
        ))
        importProfile(ConfigTransfer.encodeProfile(empty, true, true))
        assertEquals(empty, store.profile(original.id))
    }

    @Test fun deletedProfileRepairsAllReferencesAndFailoverState() {
        val original = secretProfile()
        val remaining = store.cloneProfile(original.id)!!
        store.setActiveProfile(original.id)
        store.setAlwaysOnProfile(original.id)
        store.setConnectionProfile(original.id)
        store.saveGlobalConnectionSettings(GlobalConnectionSettings(
            failoverMode = FailoverMode.SELECTED, failoverProfileIds = listOf(original.id, remaining.id),
        ))
        store.setFailoverState(true, "old failure")
        assertTrue(store.deleteProfile(original.id))
        assertEquals(remaining.id, store.activeProfileId())
        assertEquals(remaining.id, store.alwaysOnProfileId())
        assertEquals(remaining.id, store.connectionProfileId())
        assertEquals(listOf(remaining.id), store.globalConnectionSettings().failoverProfileIds)
        assertFalse(store.isFailoverActive())
        assertNull(store.failoverNotice())
        assertFalse(store.deleteProfile(remaining.id))
    }

    @Test fun staleReconnectCompletionCannotClearNewRequest() {
        store.markPendingReconnect()
        val old = store.pendingReconnectToken()
        store.markPendingReconnect()
        val current = store.pendingReconnectToken()
        assertNotNull(current)
        assertNotEquals(old, current)
        store.clearPendingReconnect(old)
        store.clearPendingReconnect(null)
        assertEquals(current, store.pendingReconnectToken())
        store.clearPendingReconnect(current)
        assertFalse(store.hasPendingReconnect())
    }

    @Test fun trustingJumpKeyDoesNotChangeDestinationOrAnotherProfile() {
        val original = secretProfile().let { it.copy(config = it.config.copy(
            trustedHostKey = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            jumpAcceptAnyHostKey = true,
        )) }.also(store::saveProfile)
        val other = store.cloneProfile(original.id)!!
        val pin = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        assertTrue(store.trustSshHostKey(original.id, "jump", pin))
        assertEquals(original.copy(config = original.config.copy(
            jumpTrustedHostKey = pin, jumpAcceptAnyHostKey = false,
        )), store.profile(original.id))
        assertEquals(other, store.profile(other.id))
        assertFalse(store.trustSshHostKey(original.id, "jump", "invalid"))
        assertFalse(store.trustSshHostKey("deleted", "jump", pin))
        assertEquals(pin, store.profile(original.id)!!.config.jumpTrustedHostKey)
    }
}
