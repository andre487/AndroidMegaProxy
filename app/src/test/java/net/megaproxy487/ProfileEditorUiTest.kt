package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.GlobalConnectionSettings
import org.junit.Assert.*
import org.junit.Test

class ProfileEditorUiTest : MainUiTestBase() {
    private fun field(id: Int): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(id)))
        return node(id)
    }
    @Test fun socks5UsesItsPortAndHidesTlsAndSshControls() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.profile_type).performClick()
        node(ProxyType.SOCKS5.titleRes).performClick()
        saved()
        assertEquals(ProxyType.SOCKS5, store.profile(id)!!.config.type)
        assertEquals(1080, store.profile(id)!!.config.port)
        field(R.string.socks5_proxy_hostname).assertExists()
        field(R.string.socks5_username_optional).assertExists()
        node(R.string.prefer_http3).assertDoesNotExist()
        node(R.string.allow_proxy_certificate).assertDoesNotExist()
        node(R.string.private_key_optional).assertDoesNotExist()
    }

    @Test fun editsPersistButInvalidPortDoesNotReplaceSavedValue() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.profile_name_optional).performTextReplacement("Renamed")
        field(R.string.port).performTextReplacement("70000")
        saved()
        assertEquals("Renamed", store.profile(id)!!.name)
        assertEquals(443, store.profile(id)!!.config.port)
        field(R.string.port).performTextReplacement("8443")
        saved()
        assertEquals(8443, store.profile(id)!!.config.port)
    }

    @Test fun switchingProtocolShowsJumpFieldsAndUsesProtocolPort() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.profile_type).performClick()
        node(ProxyType.SSH_JUMP.titleRes).performClick()
        saved()
        assertEquals(ProxyType.SSH_JUMP, store.profile(id)!!.config.type)
        assertEquals(22, store.profile(id)!!.config.port)
        field(R.string.jump_host).assertExists()
    }

    @Test fun http3PreferencePersistsAndIsOnlyOfferedForHttps() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.prefer_http3).performClick()
        saved()
        assertTrue(store.profile(id)!!.config.preferHttp3)
        field(R.string.profile_type).performClick()
        node(ProxyType.MASQUE.titleRes).performClick()
        saved()
        node(R.string.prefer_http3).assertDoesNotExist()
        assertEquals(ProxyType.MASQUE, store.profile(id)!!.config.type)
        field(R.string.profile_type).performClick()
        node(ProxyType.HTTPS_JUMP.titleRes).performClick()
        saved()
        field(R.string.prefer_http3).assertExists()
    }

    @Test fun certificateBypassRequiresExplicitConfirmation() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.allow_proxy_certificate).performClick()
        node(R.string.allow_untrusted_certificate_title).assertIsDisplayed()
        node(R.string.cancel).performClick()
        saved()
        assertFalse(store.profile(id)!!.config.allowInvalidProxyCertificate)
        field(R.string.allow_proxy_certificate).performClick()
        node(R.string.ok).performClick()
        saved()
        assertTrue(store.profile(id)!!.config.allowInvalidProxyCertificate)
    }

    @Test fun httpsJumpSettingsAreSavedSeparatelyFromDestination() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.profile_type).performClick()
        node(ProxyType.HTTPS_JUMP.titleRes).performClick()
        field(R.string.https_jump_hostname).performTextReplacement("jump.example")
        saved()
        assertEquals(ProxyType.HTTPS_JUMP, store.profile(id)!!.config.type)
        assertEquals("proxy.example", store.profile(id)!!.config.host)
        assertEquals("jump.example", store.profile(id)!!.config.jumpHost)
    }

    @Test fun masqueCustomJa3PersistsIndependentlyOfGlobalHttpsJa3() {
        val tls = "771,4865,0-10-13-16-43-51,29,0"
        val quic = "771,4865,0-10-13-16-43-51-57,29,0"
        store.saveGlobalConnectionSettings(GlobalConnectionSettings(tlsProfile = TlsProfile.CUSTOM, customJa3 = tls))
        val original = store.activeProfile()
        store.saveProfile(original.copy(config = original.config.copy(type = ProxyType.MASQUE)))
        content { ProfileEditorScreen(activity, original.id, {}) }
        field(R.string.masque_custom_ja3).performTextReplacement(quic)
        saved()
        assertEquals(quic, store.profile(original.id)!!.config.customJa3)
        assertEquals(tls, store.globalConnectionSettings().customJa3)
    }

    @Test fun typingInNewDraftCreatesExactlyOneProfile() {
        content { ProfileEditorScreen(activity, "new", {}) }
        field(R.string.profile_name_optional).performTextReplacement("New profile")
        saved()
        field(R.string.https_proxy_hostname).performTextReplacement("new.example")
        saved()
        assertEquals(2, store.profiles().size)
        assertEquals("new.example", store.profiles().single { it.name == "New profile" }.config.host)
    }
}
