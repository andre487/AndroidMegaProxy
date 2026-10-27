package net.megaproxy487.vpn

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
import org.junit.Assert.*
import org.junit.Test

class ConnectionTestTargetTest {
    @Test fun diagnosticHostUpdatesImmediatelyWithoutApplyingDraftCredentialsOrAnotherEndpoint() {
        val config = ProxyConfig(host = "runtime.example", password = "runtime")
        val target = ConnectionTestTarget("runtime", config)
        val stored = ProxyProfile(id = "runtime", colorIndex = 0, config = config.copy(password = "draft", authProbeHost = "knock.example"))
        assertEquals(config.copy(authProbeHost = "knock.example"), target.withStoredDiagnosticSettings(stored).config)
        assertEquals(config, target.withStoredDiagnosticSettings(stored.copy(config = stored.config.copy(host = "new.example"))).config)
    }

    @Test fun failoverTestKeepsRuntimeConfigAndTrustDestinationTogether() {
        val runtime = ConnectionTestTarget("fallback", ProxyConfig(host = "fallback.example"))
        val target = connectionTestTarget(runtime) { error("selected draft must not be read") }
        assertSame(runtime, target)
        assertEquals("fallback", target.profileId)
        assertEquals("fallback.example", target.config.host)
    }

    @Test fun disconnectedTestCapturesSelectedProfileOnce() {
        var reads = 0
        val selected = ConnectionTestTarget("selected", ProxyConfig(host = "selected.example"))
        assertSame(selected, connectionTestTarget(null) { reads++; selected })
        assertEquals(1, reads)
    }

    @Test fun approvingRuntimeHostKeyDoesNotKeepRetestingOldPinOrApplyDraftPassword() {
        val config = ProxyConfig(type = ProxyType.SSH, host = "ssh.example", port = 22,
            password = "runtime", trustedHostKey = "old")
        val target = ConnectionTestTarget("runtime", config)
        val stored = ProxyProfile(id = "runtime", colorIndex = 0, config = config.copy(password = "draft", trustedHostKey = "approved"))
        assertEquals(config.copy(trustedHostKey = "approved"), target.withStoredDiagnosticSettings(stored).config)
        assertEquals(config, target.withStoredDiagnosticSettings(stored.copy(id = "selected")).config)
    }

    @Test fun editedHostCannotSupplyTrustForRuntimeEndpoint() {
        val config = ProxyConfig(type = ProxyType.SSH, host = "old.example", trustedHostKey = "old")
        val target = ConnectionTestTarget("runtime", config)
        val stored = ProxyProfile(id = "runtime", colorIndex = 0, config = config.copy(host = "new.example", trustedHostKey = "new"))
        assertEquals(config, target.withStoredDiagnosticSettings(stored).config)
    }
}
