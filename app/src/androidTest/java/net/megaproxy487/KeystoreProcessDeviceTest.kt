package net.megaproxy487

import android.os.Process
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.KeyPassphrase
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.SshAuthMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreProcessDeviceTest : DeviceTestBase() {
    @get:Rule(order = Int.MIN_VALUE) val compose = createEmptyComposeRule()

    private fun clickAppButton(id: Int, inDialog: Boolean = false) {
        val dialog = hasAnyAncestor(isDialog())
        val target = hasText(text(id)) and hasClickAction() and (if (inDialog) dialog else !dialog) and
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        compose.waitUntil(10_000) { compose.onAllNodes(target).fetchSemanticsNodes().size == 1 }
        compose.onNode(target)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test fun credentialsSurviveProcessRestart() {
        val privateKey = Base64.decode(argument("sshKey"), Base64.NO_WRAP).toString(Charsets.UTF_8)
        val phrase = argument("sshKeyPassphrase")
        val marker = File(context.filesDir, "device-test-process")
        if (argument("phase") == "seed") {
            io {
                store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(
                    type = ProxyType.SSH, port = argument("sshPort").toInt(), username = "proxy",
                    password = argument("sshPassword"), privateKey = privateKey,
                    privateKeyPassphrase = KeyPassphrase(phrase, remember = true),
                    jumpPrivateKey = privateKey, jumpPrivateKeyPassphrase = KeyPassphrase(phrase),
                    trustedHostKey = argument("sshFingerprint"), allowInvalidProxyCertificate = false,
                )) })
                store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(sshAuthMode = SshAuthMode.KEY_ONLY))
            }
            saved()
            assertTrue("Session-only phrase unavailable in same process", store.activeProfile().config.jumpPrivateKeyPassphrase.value == phrase)
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/proxy_config.xml").readText()
            assertFalse("Plaintext password on disk", preferences.contains(argument("sshPassword")))
            assertFalse("Plaintext key passphrase on disk", preferences.contains(phrase))
            assertFalse("Plaintext key on disk", preferences.contains("BEGIN OPENSSH PRIVATE KEY"))
            assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("megaproxy.proxy.credentials.v1"))
            marker.writeText(Process.myPid().toString())
        } else {
            assertEquals("verify", argument("phase"))
            assertNotEquals("Activity recreation is insufficient: require a fresh process", marker.readText(), Process.myPid().toString())
            val config = store.activeProfile().config
            // Avoid printing disposable private key material in assertion failures.
            assertTrue("Password did not survive process restart", config.password == argument("sshPassword"))
            assertTrue("SSH key did not survive process restart", config.privateKey == privateKey)
            assertTrue("Remembered key passphrase unavailable", config.privateKeyPassphrase.value == phrase)
            assertTrue("Session-only passphrase survived process restart", config.jumpPrivateKeyPassphrase.value.isEmpty())
            assertEquals(argument("sshFingerprint"), config.trustedHostKey)
            directOriginUnavailable()
            io { store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(sshAuthMode = SshAuthMode.PASSWORD_ONLY)) }
            connect { clickAppButton(R.string.connect) }
            roundTrip() // No key fallback: the recovered password must authenticate.
            clickAppButton(R.string.disconnect)
            stopped()
            io { store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(sshAuthMode = SshAuthMode.KEY_ONLY)) }
            connect { clickAppButton(R.string.connect) }
            roundTrip() // No password fallback: the recovered key must authenticate.
            clickAppButton(R.string.disconnect)
            stopped()
            io {
                val profile = store.activeProfile()
                store.saveProfile(profile.copy(config = profile.config.copy(privateKeyPassphrase = KeyPassphrase())))
            }
            scenario.recreate()
            clickAppButton(R.string.connect)
            clickAppButton(R.string.cancel, inDialog = true)
            stopped()
            clickAppButton(R.string.connect)
            val input = compose.onNode(hasAnyAncestor(isDialog()) and hasSetTextAction())
            input.performTextReplacement("incorrect")
            clickAppButton(R.string.connect, inDialog = true)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText(text(R.string.ssh_unlock_failed)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(store.isConnectionDesired())
            input.performTextReplacement(phrase)
            connect { clickAppButton(R.string.connect, inDialog = true) }
            roundTrip()
            assertTrue(store.activeProfile().config.privateKeyPassphrase == KeyPassphrase(phrase))
            marker.delete()
        }
    }
}
