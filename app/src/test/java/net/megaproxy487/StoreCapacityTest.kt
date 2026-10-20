package net.megaproxy487

import android.app.Application
import net.megaproxy487.data.ConfigStore
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security
import net.megaproxy487.data.ConfigTransfer
import net.megaproxy487.data.MAX_CONFIG_FILE_BYTES
import net.megaproxy487.data.MAX_IMPORTED_PROFILES
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StoreCapacityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ConfigStore(context)
    @Before fun installKeys() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
        store.activeProfile()
    }
    @After fun removeKeys() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
    }

    @Test fun aggregateLimitPreservesPreviousConfiguration() {
        repeat(2) { batch ->
            val profiles = JSONArray()
            repeat(MAX_IMPORTED_PROFILES - 1) { index ->
                val profile = ProxyProfile(id = "review-$batch-$index", colorIndex = 0,
                    config = ProxyConfig(host = "proxy.example", username = "u", password = "p"))
                profiles.put(ConfigTransfer.encodeProfile(profile, true, false))
            }
            val text = JSONObject().put("schema", ConfigTransfer.SCHEMA_ID)
                .put("version", ConfigTransfer.SCHEMA_VERSION).put("profiles", profiles).toString()
            assertTrue(text.toByteArray().size < MAX_CONFIG_FILE_BYTES)
            val before = store.profiles()
            if (batch == 0) store.importConfiguration(ConfigTransfer.importJson(text))
            else {
                assertThrows(UiException::class.java) { store.importConfiguration(ConfigTransfer.importJson(text)) }
                assertEquals(before, store.profiles())
            }
        }
        assertEquals(MAX_IMPORTED_PROFILES, store.profiles().size)
        assertThrows(UiException::class.java) { store.cloneProfile(store.profiles().first().id) }
        val before = store.profiles()
        val profile = before.first()
        assertThrows(UiException::class.java) {
            store.saveProfile(profile.copy(config = profile.config.copy(privateKey = "x".repeat(MAX_CONFIG_FILE_BYTES + 1))))
        }
        assertEquals(before, store.profiles())
    }
    @Test fun encodedSizeLimitPreservesPreviousConfiguration() {
        val before = store.profiles()
        assertThrows(UiException::class.java) {
            store.saveProfile(before.first().copy(name = "\u0000".repeat(1_500_000)))
        }
        assertEquals(before, store.profiles())
    }

    @Test fun legacyOversizedStorageRemainsReadableAndCanBeReduced() {
        val profiles = JSONArray()
        repeat(MAX_IMPORTED_PROFILES + 1) { index ->
            profiles.put(JSONObject().put("id", "legacy-$index").put("config", JSONObject()))
        }
        val original = profiles.toString()
        val preferences = context.getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE)
        preferences.edit().putString("profiles_v2", original).putInt("flag_color_version", 0).commit()
        assertEquals(MAX_IMPORTED_PROFILES + 1, store.profiles().size)
        assertEquals(original, preferences.getString("profiles_v2", null))
        store.deleteProfiles(setOf("legacy-0", "legacy-1"))
        assertEquals(MAX_IMPORTED_PROFILES - 1, store.profiles().size)
    }

}
