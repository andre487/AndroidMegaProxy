package net.megaproxy487

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import net.megaproxy487.model.DnsProvider
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
    private fun selectOption(label: Int, option: Int) {
        val selector = hasText(text(label)) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(selector)
        compose.onNode(selector).performSemanticsAction(SemanticsActions.OnClick) { it() }
        node(option).assertIsDisplayed().performClick()
    }

    @Test fun authenticationProbeFieldSavesAndValidatesWithoutReconnect() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        val input = hasText(text(R.string.auth_probe_host)) and hasSetTextAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(input)
        compose.onNode(input).performTextReplacement("knock.example")
        saved()
        assertEquals("knock.example", store.profile(id)!!.config.authProbeHost)
        assertFalse(store.hasPendingReconnect())
        compose.onNode(input).performTextReplacement("https://knock.example/path")
        saved()
        node(R.string.validation_auth_probe_host).assertExists()
    }

    @Test fun newSshProfileSavesCustomDohAndClearsValidationAfterCorrection() {
        content { ProfileEditorScreen(activity, "new", {}) }
        field(R.string.profile_name_optional).performTextReplacement("Custom DNS")
        saved()
        selectOption(R.string.profile_type, ProxyType.SSH.titleRes)
        field(R.string.destination_ssh_hostname).performTextReplacement("proxy.example")
        field(R.string.ssh_username).performTextReplacement("user")
        fun selectDns(provider: DnsProvider) {
            selectOption(R.string.dns_over_https, provider.titleRes)
        }
        selectDns(DnsProvider.CUSTOM)
        val input = hasText(text(R.string.custom_doh_url)) and hasSetTextAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(input)
        compose.onNode(input).performTextReplacement("192.0.2.1")
        saved()
        node(R.string.validation_doh_https_required).assertExists()
        for (url in listOf("https://dns.example", "HTTPS://dns.example/query", "https://192.0.2.1",
            "https://[2001:db8::1]:8443/dns-query")) {
            compose.onNode(input).performTextReplacement("  $url  ")
            saved()
            node(R.string.validation_doh_url).assertDoesNotExist()
            node(R.string.validation_doh_https_required).assertDoesNotExist()
            val profile = store.profiles().single { it.name == "Custom DNS" }
            assertEquals(ProxyType.SSH, profile.config.type)
            assertEquals(DnsProvider.CUSTOM, profile.config.dnsProvider)
            assertEquals(url, profile.config.customDohUrl)
            assertNull(profile.config.connectionValidationError())
        }
        compose.onNode(input).performTextReplacement("invalid")
        saved()
        selectDns(DnsProvider.CLOUDFLARE)
        saved()
        node(R.string.validation_doh_url).assertDoesNotExist()
        node(R.string.validation_doh_https_required).assertDoesNotExist()
        assertNull(store.profiles().single { it.name == "Custom DNS" }.config.connectionValidationError())
    }

    @Test fun dnsSecurityExceptionsRequireConfirmationAndPersistLocally() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        field(R.string.allow_invalid_doh_certificate).performClick()
        node(R.string.cancel).performClick()
        saved()
        assertFalse(store.profile(id)!!.config.allowInvalidDohCertificate)
        field(R.string.allow_invalid_doh_certificate).performClick()
        node(R.string.ok).performClick()
        saved()
        assertTrue(store.profile(id)!!.config.allowInvalidDohCertificate)
        assertFalse(store.profile(id)!!.config.allowInvalidProxyCertificate)
        field(R.string.allow_insecure_dns).performClick()
        node(R.string.cancel).performClick()
        saved()
        assertFalse(store.profile(id)!!.config.allowInsecureDns)
        field(R.string.allow_insecure_dns).performClick()
        node(R.string.ok).performClick()
        saved()
        assertTrue(store.profile(id)!!.config.allowInsecureDns)
        selectOption(R.string.dns_transport, R.string.dns_transport_tcp)
        node(R.string.ok).performClick()
        field(R.string.dns_server_ip).performTextReplacement("192.0.2.1:5353")
        saved()
        assertEquals("tcp://192.0.2.1:5353", store.profile(id)!!.config.customDohUrl)
        field(R.string.allow_insecure_dns).performClick()
        saved()
        assertFalse(store.profile(id)!!.config.allowInsecureDns)
        assertEquals(DnsProvider.CLOUDFLARE, store.profile(id)!!.config.dnsProvider)
    }

    @Test fun socks5UsesItsPortAndHidesTlsAndSshControls() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        selectOption(R.string.profile_type, ProxyType.SOCKS5.titleRes)
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
        selectOption(R.string.profile_type, ProxyType.SSH_JUMP.titleRes)
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
        selectOption(R.string.profile_type, ProxyType.MASQUE.titleRes)
        saved()
        node(R.string.prefer_http3).assertDoesNotExist()
        assertEquals(ProxyType.MASQUE, store.profile(id)!!.config.type)
        selectOption(R.string.profile_type, ProxyType.HTTPS_JUMP.titleRes)
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
        selectOption(R.string.profile_type, ProxyType.HTTPS_JUMP.titleRes)
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
