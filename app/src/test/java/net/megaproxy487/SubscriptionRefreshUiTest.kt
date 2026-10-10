package net.megaproxy487

import android.view.WindowManager
import net.megaproxy487.data.ConfigSubscription
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import net.megaproxy487.data.*
import net.megaproxy487.vpn.*
import org.json.JSONObject
import org.json.JSONArray

class SubscriptionRefreshUiTest : MainUiTestBase() {
    @Test fun subscriptionCredentialsMustBeProtectedFromScreenshots() {
        store.saveSubscription(ConfigSubscription("https://feed.example/config?token=private", username="private-user", password="private-pass", enabled=false))
        content { ConfigSubscriptionScreen(activity, {}) }
        drainConfigIo()
        assertTrue("URL token and credentials screen lacks FLAG_SECURE", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
    @Test fun subscriptionUpdateMustShowPendingReconnectWithoutLeavingMainScreen() {
        val profile = store.activeProfile()
        store.importConfiguration(PortableConfiguration(listOf(profile), profile.id, profile.id,
            subscriptionPresent=true, subscription=ConfigSubscription("https://feed.example/config", enabled=false)))
        VpnRuntimeState.update(VpnConnectionState.CONNECTED)
        content { MainScreen(activity, {}, {}, {}, readConnectionStats = { null }) }
        drainConfigIo()
        val document = JSONObject().put("schema", ConfigTransfer.SCHEMA_ID).put("version", 8)
            .put("profiles", JSONArray(listOf(ConfigTransfer.encodeProfile(profile.copy(config=profile.config.copy(port=444)), true, false))))
        assertTrue(runBlocking { ConfigSubscriptions.refreshNow(activity, true, { _, _ -> document.toString() }, { true }) })
        assertTrue(store.hasPendingReconnect())
        drainConfigIo()
        node(R.string.apply_new_settings).assertExists()
    }

    @Test fun openEditorRenamePreservesRefreshedEndpointAndSecrets() {
        val profile = store.activeProfile()
        content { ProfileEditorScreen(activity, profile.id, {}) }
        drainConfigIo()
        val refreshed = profile.copy(config = profile.config.copy(host = "new.example", port = 8443,
            password = "new-secret", allowInvalidProxyCertificate = true))
        store.saveProfile(refreshed)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.profile_name_optional)))
        node(R.string.profile_name_optional).performTextReplacement("Renamed")
        saved()
        assertEquals(refreshed.copy(name = "Renamed"), store.profile(profile.id))
    }

    @Test fun profilesRefreshWhileScreenStaysOpen() {
        val profile = store.activeProfile()
        store.importConfiguration(PortableConfiguration(listOf(profile), profile.id, profile.id,
            subscriptionPresent = true, subscription = ConfigSubscription("https://feed.example/config", enabled = false)))
        content { ProfilesScreen(activity, {}, {}) }
        drainConfigIo()
        val document = JSONObject().put("schema", ConfigTransfer.SCHEMA_ID).put("version", 8)
            .put("profiles", JSONArray(listOf(ConfigTransfer.encodeProfile(profile.copy(name = "Refreshed profile"), true, false))))
        assertTrue(runBlocking { ConfigSubscriptions.refreshNow(activity, true, { _, _ -> document.toString() }, { false }) })
        drainConfigIo()
        compose.onNodeWithText("Refreshed profile").assertExists()
    }

    @Test fun secureFlagSurvivesOverlappingScreensAndIsClearedWhenTheyLeave() {
        val stage = androidx.compose.runtime.mutableStateOf(0)
        content {
            if (stage.value < 2) SecureScreen(activity)
            if (stage.value == 1) SecureScreen(activity)
        }
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.runOnIdle { stage.value = 1 }
        compose.runOnIdle { stage.value = 2 }
        compose.runOnIdle { assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @Test fun openFingerprintScreenPreservesRefreshedGlobalRouting() {
        content { TlsFingerprintScreen(activity, {}) }
        drainConfigIo()
        val refreshed = store.globalConnectionSettings().copy(bypassLocalNetworks = false,
            selectedPackages = setOf("new.package"), failoverMode = net.megaproxy487.model.FailoverMode.ALL)
        store.saveGlobalConnectionSettings(refreshed)
        node(R.string.https_tls_ja3_profile).performClick()
        node(net.megaproxy487.model.TlsProfile.FIREFOX_ANDROID.titleRes).performClick()
        saved()
        assertEquals(refreshed.copy(tlsProfile = net.megaproxy487.model.TlsProfile.FIREFOX_ANDROID), store.globalConnectionSettings())
    }
}
