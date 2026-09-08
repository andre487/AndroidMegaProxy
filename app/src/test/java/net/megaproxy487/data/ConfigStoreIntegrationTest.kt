package net.megaproxy487.data

import android.app.Application
import android.content.Context
import net.megaproxy487.UiTestKeyStore
import net.megaproxy487.UiTestKeyStoreProvider
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
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
