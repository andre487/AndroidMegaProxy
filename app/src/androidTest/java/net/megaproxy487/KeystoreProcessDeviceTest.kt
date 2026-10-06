package net.megaproxy487

import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.SshAuthMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreProcessDeviceTest : DeviceTestBase() {
    @Test fun credentialsSurviveProcessRestart() {
        val privateKey = Base64.decode(argument("sshKey"), Base64.NO_WRAP).toString(Charsets.UTF_8)
        val marker = File(context.filesDir, "device-test-process")
        if (argument("phase") == "seed") {
            io {
                store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(
                    type = ProxyType.SSH, port = argument("sshPort").toInt(), username = "proxy",
                    password = argument("sshPassword"), privateKey = privateKey,
                    trustedHostKey = argument("sshFingerprint"), allowInvalidProxyCertificate = false,
                )) })
                store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(sshAuthMode = SshAuthMode.KEY_ONLY))
            }
            saved()
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/proxy_config.xml").readText()
            assertFalse("Plaintext password on disk", preferences.contains(argument("sshPassword")))
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
            assertEquals(argument("sshFingerprint"), config.trustedHostKey)
            directOriginUnavailable()
            connect()
            roundTrip() // KEY_ONLY proves the recovered key works with real OpenSSH.
            marker.delete()
        }
    }
}
