package net.megaproxy487

import android.app.Application
import android.content.Context
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.*
import net.megaproxy487.vpn.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfigurationRecoveryRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ConfigStore(context)
    @Before fun install() { Security.removeProvider("AndroidKeyStore"); UiTestKeyStore.keys.clear(); Security.addProvider(UiTestKeyStoreProvider()) }
    @After fun cleanup() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        VpnRuntimeState.updateNetworkWarning(null)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun failoverSkipsInvalidProfile() {
        val first = store.activeProfile().let { it.copy(config = ProxyConfig(type=ProxyType.SOCKS5,host="one.example",port=1080)) }
        store.saveProfile(first)
        val invalid = store.addProfile()
        val unsupported = store.addProfile().let { it.copy(config = first.config.copy(type = ProxyType.MASQUE, username = "user", password = "secret")) }
        store.saveProfile(unsupported)
        val unreadable = store.addProfile().let { it.copy(config = first.config.copy(
            username = "user", password = "", unreadableSecrets = mapOf("password" to "damaged ciphertext"))) }
        store.saveProfile(unreadable)
        assertEquals(R.string.error_credentials_unavailable, store.profile(unreadable.id)!!.config.validationError())
        val valid = store.addProfile().let { it.copy(config = first.config.copy(host="three.example")) }
        store.saveProfile(valid)
        store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(failoverMode=FailoverMode.ALL, tlsProfile=TlsProfile.CUSTOM, customJa3="invalid-ja3"))
        store.setConnectionProfile(first.id)
        val service = Robolectric.buildService(ProxyVpnService::class.java).get()
        @Suppress("UNCHECKED_CAST")
        val refreshed = ProxyVpnService::class.java.getDeclaredField("freshDnsRecoveryProfiles").apply { isAccessible=true }.get(service) as MutableSet<String>
        refreshed.add(first.id)
        ProxyVpnService::class.java.getDeclaredMethod("handleProbableBlocking",String::class.java,BlockingSignal::class.java)
            .apply { isAccessible=true }.invoke(service,first.id,BlockingSignal.TCP_TIMEOUT)
        assertNotEquals("Incomplete draft was chosen over a valid fallback",invalid.id,store.connectionProfileId())
        assertEquals(valid.id,store.connectionProfileId())
    }

    @Test fun corruptGlobalSettingsMustNotEnableAllAppsAndLocalBypass() {
        store.activeProfile()
        store.saveGlobalConnectionSettings(GlobalConnectionSettings(routeAllApps=false,selectedPackages=setOf("org.example.app"),bypassLocalNetworks=false))
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val profilesBefore = prefs.getString("profiles_v2", null)
        prefs.edit().remove("ipv6_profile_migrated_v1")
            .putString("global_connection_settings_v1", "{broken").commit()
        val result=runCatching { store.globalConnectionSettings() }
        assertTrue(result.exceptionOrNull() is UiException)
        assertEquals(profilesBefore, prefs.getString("profiles_v2", null))
        assertFalse(prefs.getBoolean("ipv6_profile_migrated_v1", false))
        assertEquals("{broken", context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            .getString("global_connection_settings_v1", null))
    }

    @Test fun malformedRoutingTypesFailClosed() {
        store.saveGlobalConnectionSettings(GlobalConnectionSettings())
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val valid = prefs.getString("global_connection_settings_v1", null)!!
        for ((key, value) in listOf("routeAllApps" to "broken", "bypassLocalNetworks" to 1, "packages" to "all")) {
            val damaged = org.json.JSONObject(valid).put(key, value).toString()
            prefs.edit().putString("global_connection_settings_v1", damaged).commit()
            assertTrue(runCatching { store.connectionConfig() }.exceptionOrNull() is UiException)
            assertEquals(damaged, prefs.getString("global_connection_settings_v1", null))
        }
    }
}
