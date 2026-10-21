package net.megaproxy487.data

import net.megaproxy487.model.*
import org.junit.Assert.*
import org.junit.Test

class ProfileSecretRetentionTest {
    private fun original(type: ProxyType) = ProxyProfile("stable", "Original", 0, config = ProxyConfig(
        type = type, host = "proxy.example", port = type.defaultPort, username = "user",
        password = "saved-password", privateKey = "saved-key", jumpHost = "jump.example",
        jumpPort = type.defaultPort, sameJumpAuthentication = false, jumpUsername = "jump-user",
        jumpPassword = "saved-jump-password", jumpPrivateKey = "saved-jump-key",
    ))
    private fun omitted(profile: ProxyProfile) = profile.copy(name = "Renamed", config = profile.config.copy(
        password = "", privateKey = "", jumpPassword = "", jumpPrivateKey = "", unreadableSecrets = emptyMap()))

    @Test fun omittedSecretsStayOnlyWithTheSameProtocolEndpointAndAccount() {
        for (type in ProxyType.entries) {
            val current = original(type)
            val incoming = omitted(current)
            val same = retainProfileSecrets(incoming, current, ProfileSecretPresence()).config
            assertEquals(current.config.password, same.password)
            assertEquals(current.config.privateKey, same.privateKey)
            for (changed in listOf(
                incoming.config.copy(type = if (type == ProxyType.SOCKS5) ProxyType.HTTPS else ProxyType.SOCKS5),
                incoming.config.copy(host = "other.example"),
                incoming.config.copy(port = incoming.config.port + 1),
                incoming.config.copy(username = "other-user"),
                incoming.config.copy(username = ""),
            )) {
                val result = retainProfileSecrets(incoming.copy(config = changed), current, ProfileSecretPresence()).config
                assertEquals("$type: $changed", "", result.password)
                assertEquals("", result.privateKey)
                assertFalse("password" in result.unreadableSecrets)
            }
            val cleared = retainProfileSecrets(incoming, current, ProfileSecretPresence(password = true, privateKey = true)).config
            assertEquals("", cleared.password)
            assertEquals("", cleared.privateKey)
            val supplied = incoming.copy(config = incoming.config.copy(host = "new.example", password = "new-secret"))
            assertEquals("new-secret", retainProfileSecrets(supplied, current, ProfileSecretPresence(password = true)).config.password)
        }
    }

    @Test fun normalizedHostRetainsCredentialsButChangedUsernameDoesNot() {
        val current = original(ProxyType.HTTPS).let { it.copy(config = it.config.copy(host = "xn--e1afmkfd.example")) }
        val incoming = omitted(current).let { it.copy(config = it.config.copy(host = "ПРИМЕР.example.")) }
        assertEquals(current.config.password, retainProfileSecrets(incoming, current, ProfileSecretPresence()).config.password)
        assertEquals("", retainProfileSecrets(incoming.copy(config = incoming.config.copy(username = "USER")), current,
            ProfileSecretPresence()).config.password)
    }

    @Test fun jumpSecretsFollowTheirOwnRecipientAndSharedAuthRequiresBothEndpoints() {
        for (type in listOf(ProxyType.HTTPS_JUMP, ProxyType.SSH_JUMP)) {
            val current = original(type)
            val incoming = omitted(current)
            for (changed in listOf(incoming.config.copy(jumpHost = "other.example"),
                incoming.config.copy(jumpPort = incoming.config.jumpPort + 1),
                incoming.config.copy(jumpUsername = "other-user"))) {
                val result = retainProfileSecrets(incoming.copy(config = changed), current, ProfileSecretPresence()).config
                assertEquals(current.config.password, result.password)
                assertEquals("", result.jumpPassword)
                assertEquals("", result.jumpPrivateKey)
                assertFalse("jumpPassword" in result.unreadableSecrets)
            }
            val shared = current.copy(config = current.config.copy(sameJumpAuthentication = true))
            val changed = omitted(shared).let { it.copy(config = it.config.copy(jumpHost = "new-jump.example")) }
            assertEquals("", retainProfileSecrets(changed, shared, ProfileSecretPresence()).config.password)
        }
    }

    @Test fun unreadableCiphertextRequiresExplicitRecoveryEvenWhenRecipientChanges() {
        val current = original(ProxyType.HTTPS).let { it.copy(config = it.config.copy(
            password = "", unreadableSecrets = mapOf("password" to "opaque-recovery"))) }
        val incoming = omitted(current).let { it.copy(config = it.config.copy(host = "new.example")) }
        val retained = retainProfileSecrets(incoming, current, ProfileSecretPresence()).config
        assertEquals("", retained.password)
        assertEquals("opaque-recovery", retained.unreadableSecrets["password"])
        val explicit = retainProfileSecrets(incoming, current, ProfileSecretPresence(password = true)).config
        assertFalse("password" in explicit.unreadableSecrets)
    }

    @Test fun omittedSecretsCannotBeReintroducedByLegacyProfileCopies() {
        val current = original(ProxyType.HTTPS_JUMP)
        val incoming = current.copy(config = current.config.copy(host = "new.example", jumpHost = "new-jump.example"))
        val result = retainProfileSecrets(incoming, current, ProfileSecretPresence()).config
        assertEquals("", result.password)
        assertEquals("", result.privateKey)
        assertEquals("", result.jumpPassword)
        assertEquals("", result.jumpPrivateKey)
    }
}
