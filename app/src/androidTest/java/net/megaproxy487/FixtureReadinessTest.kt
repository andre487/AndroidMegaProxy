package net.megaproxy487

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureReadinessTest {
    @Test fun sshBanner() {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("sshPort")).toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.0.2.2", port), 10_000)
            socket.soTimeout = 10_000
            val prefix = ByteArray(4)
            DataInputStream(socket.getInputStream()).readFully(prefix)
            assertArrayEquals("SSH fixture banner", "SSH-".toByteArray(), prefix)
        }
    }
}
