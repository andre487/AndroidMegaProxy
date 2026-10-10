package net.megaproxy487

import net.megaproxy487.data.*
import net.megaproxy487.model.*
import org.junit.Assert.*
import org.junit.Test

class ConfigEditsTest {
    private val initial = ProxyProfile("one", "original", 0, config = ProxyConfig(host = "old.example", password = "old"))

    @Test fun untouchedCredentialsAndSecuritySurviveRenameWhileIntentionalConflictsUseUserValue() {
        val edits = ConfigEdits<ProxyProfile>()
        val renamed = initial.copy(name = "renamed")
        val patch = edits.profile(initial, renamed)
        val remote = initial.copy(config = initial.config.copy(host = "new.example", password = "new", allowInvalidProxyCertificate = true))
        var saved = remote
        patch.write { saved = it(saved) }
        assertEquals(remote.copy(name = "renamed"), saved)
        val changed = renamed.copy(config = renamed.config.copy(host = "user.example"))
        edits.profile(renamed, changed).write { saved = it(saved) }
        assertEquals("user.example", saved.config.host)
        assertEquals("new", saved.config.password)
    }

    @Test fun failedWritesRemainCompleteButCommittedFieldsNeverOverwriteLaterRefreshes() {
        val edits = ConfigEdits<ProxyProfile>()
        val renamed = initial.copy(name = "rename")
        val first = edits.profile(initial, renamed)
        assertTrue(runCatching { first.write { error("disk failure") } }.isFailure)
        val colored = renamed.copy(colorIndex = 2)
        val second = edits.profile(renamed, colored)
        var saved = initial
        second.write { saved = it(saved) }
        assertEquals(colored, saved)
        // A queued/failed older operation is now obsolete, even if retried after a remote update.
        saved = saved.copy(name = "remote-name")
        first.write { saved = it(saved) }
        assertEquals("remote-name", saved.name)
        // Reverting a previously edited field is still an explicit edit.
        edits.profile(colored, colored.copy(name = initial.name)).write { saved = it(saved) }
        assertEquals(initial.name, saved.name)
    }

    @Test fun globalFieldEditsPreserveNewRoutingFailoverAndFingerprintValues() {
        val initial = GlobalConnectionSettings()
        val remote = initial.copy(tlsProfile = TlsProfile.FIREFOX_ANDROID, bypassLocalNetworks = false,
            failoverMode = FailoverMode.ALL, customJa3 = "remote", selectedPackages = setOf("new.package"))
        var saved = remote
        ConfigEdits<GlobalConnectionSettings>().settings(initial, initial.copy(sshKeepaliveSeconds = 60))
            .write { saved = it(saved) }
        assertEquals(remote.copy(sshKeepaliveSeconds = 60), saved)
    }

    @Test fun notificationReconnectRequiresTheCurrentTokenAndAnActiveDesiredVpn() {
        assertTrue(canReconnectSubscription("current", "current", true, true))
        assertFalse(canReconnectSubscription(null, null, true, true))
        assertFalse(canReconnectSubscription("old", "current", true, true))
        assertFalse(canReconnectSubscription("current", "current", false, true))
        assertFalse(canReconnectSubscription("current", "current", true, false))
    }

    @Test fun replacingUnreadableSecretClearsOnlyThatFieldsRecoveryData() {
        val damaged = initial.copy(config = initial.config.copy(password = "",
            unreadableSecrets = mapOf("password" to "opaque", "privateKey" to "other-opaque")))
        var saved = damaged
        ConfigEdits<ProxyProfile>().profile(damaged, damaged.copy(config = damaged.config.copy(
            unreadableSecrets = damaged.config.unreadableSecrets - "password"))).write { saved = it(saved) }
        assertEquals(mapOf("privateKey" to "other-opaque"), saved.config.unreadableSecrets)
    }
}
