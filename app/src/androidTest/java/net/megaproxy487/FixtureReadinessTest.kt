package net.megaproxy487

import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureReadinessTest {
    @Test fun sshBanner() {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("sshPort")).toInt()
        val endpoint = InetSocketAddress("10.0.2.2", port)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (true) {
            Socket().use { socket ->
                try {
                    socket.connect(endpoint, 10_000)
                } catch (error: ConnectException) {
                    // A cold emulator can report validated Wi-Fi before the app has a route.
                    if ((error.cause as? ErrnoException)?.errno != OsConstants.ENETUNREACH ||
                        SystemClock.elapsedRealtime() >= deadline) throw error
                    return@use
                }
                socket.soTimeout = 10_000
                val prefix = ByteArray(4)
                DataInputStream(socket.getInputStream()).readFully(prefix)
                assertArrayEquals("SSH fixture banner", "SSH-".toByteArray(), prefix)
                return
            }
            SystemClock.sleep(250)
        }
    }
}
