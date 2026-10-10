package net.megaproxy487

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.ProxyType
import net.megaproxy487.vpn.ConnectionStatsReader
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.vpn.VpnTransportProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnDeviceTest : DeviceTestBase() {
    @Test fun trafficStopAndRestart() {
        directOriginUnavailable()
        connect()
        roundTrip()
        click(R.string.disconnect)
        stopped()
        directOriginUnavailable()
        connect()
        roundTrip()
    }

    @Test fun masqueTrafficStopAndRestart() {
        useMasque(TlsProfile.CHROME_ANDROID)
        saved()
        directOriginUnavailable()
        repeat(2) {
            connect()
            roundTrip()
            udpRoundTrip(1200)
            await("QUIC metrics did not arrive through JNI") {
                ConnectionStatsReader.snapshot()?.let {
                    it.quicRttMillis?.let { rtt -> rtt > 0 } == true && it.quicPacketsLost != null &&
                        it.tcpRttMillis == null && it.tcpRetransmits == null
                } == true
            }
            appNode(androidx.test.uiautomator.By.text(text(R.string.quic_rtt)))
            await("HTTP/3 badge did not update") { VpnRuntimeState.transportProtocol.value == VpnTransportProtocol.HTTP_3 }
            click(R.string.disconnect)
            stopped()
            directOriginUnavailable()
        }
    }

    @Test fun masqueFirefoxTraffic() {
        useMasque(TlsProfile.FIREFOX_ANDROID)
        saved()
        connect()
        roundTrip()
        udpRoundTrip(512)
        await("HTTP/3 badge did not update") { VpnRuntimeState.transportProtocol.value == VpnTransportProtocol.HTTP_3 }
    }

    @Test fun masqueRandomizedTraffic() = masqueFingerprintTraffic(TlsProfile.RANDOMIZED)

    @Test fun masqueCustomTraffic() = masqueFingerprintTraffic(TlsProfile.CUSTOM)

    private fun masqueFingerprintTraffic(fingerprint: TlsProfile) {
        useMasque(fingerprint)
        saved()
        directOriginUnavailable()
        connect()
        roundTrip()
        udpRoundTrip(512)
        await("HTTP/3 badge did not update") { VpnRuntimeState.transportProtocol.value == VpnTransportProtocol.HTTP_3 }
    }

    @Test fun masqueChromeOversizedUdpPreservesFlow() = oversizedUdpPreservesFlow(TlsProfile.CHROME_ANDROID, 4096)

    @Test fun masqueFirefoxOversizedUdpPreservesFlow() = oversizedUdpPreservesFlow(TlsProfile.FIREFOX_ANDROID, 1200)

    private fun oversizedUdpPreservesFlow(fingerprint: TlsProfile, oversized: Int) {
        useMasque(fingerprint)
        // Background Google traffic must not contend with this controlled UDP fixture.
        io { store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(
            routeAllApps = false, selectedPackages = setOf(context.packageName))) }
        saved()
        PersistentDiagnosticLog.clear()
        connect()
        DatagramSocket().use { socket ->
            socket.soTimeout = 15_000
            udpRoundTrip(512, socket)
            val payload = ByteArray(oversized)
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(argument("originHost")), 8081))
            // Same socket and five-tuple: creating a fresh socket would hide a broken association.
            repeat(3) { udpRoundTrip(512, socket) }
        }
        roundTrip()
        await("Oversized UDP drop missing from diagnostics") {
            PersistentDiagnosticLog.readTail(64 * 1024).contains("reason=datagram_too_large")
        }
        val associations = PersistentDiagnosticLog.readTail(64 * 1024).lineSequence().count {
            "stage=tunnel result=established" in it && "udp=true" in it
        }
        assertEquals("Oversized UDP must preserve the original association, not silently reopen it", 1, associations)
    }

    @Test fun masqueWrongCredentialsRejectedAndCorrected() {
        useMasque(TlsProfile.CHROME_ANDROID)
        io { store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(password = "wrong-device-password")) }) }
        saved()
        PersistentDiagnosticLog.clear()
        connect() // QUIC handshake precedes CONNECT authentication.
        assertTrue("Wrong credentials reached the private origin", runCatching { roundTrip() }.isFailure)
        await("Authentication failure missing from diagnostics") {
            PersistentDiagnosticLog.readTail(64 * 1024).let { it.contains("status=407") && it.contains("reason=proxy_authentication") }
        }
        val logs = PersistentDiagnosticLog.readTail(64 * 1024)
        assertFalse(logs.contains("wrong-device-password"))
        assertFalse(logs.contains(argument("proxyPassword")))
        ProxyVpnService.stop(context)
        stopped()
        io { store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(password = argument("proxyPassword"))) }) }
        saved()
        connect()
        roundTrip()
        udpRoundTrip(512)
    }

    @Test fun masqueUntrustedCertificateRejected() {
        useMasque(TlsProfile.CHROME_ANDROID)
        io { store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(allowInvalidProxyCertificate = false)) }) }
        saved()
        PersistentDiagnosticLog.clear()
        click(R.string.connect)
        if (VpnService.prepare(context) != null) systemButton("android:id/button1")
        await("Untrusted certificate was not rejected") {
            PersistentDiagnosticLog.readTail(64 * 1024).contains("reason=certificate")
        }
        assertNotEquals(VpnConnectionState.CONNECTED, VpnRuntimeState.connection.value)
        assertFalse(ProxyVpnService.isRunning)
        ProxyVpnService.stop(context)
        stopped()
        directOriginUnavailable()
        io { store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(allowInvalidProxyCertificate = true)) }) }
        saved()
        connect()
        roundTrip()
        udpRoundTrip(512)
    }

    @Test fun masqueSplitRoutingIncludesAndExcludesApplication() {
        useMasque(TlsProfile.CHROME_ANDROID)
        io { store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(routeAllApps = false, selectedPackages = setOf(context.packageName))) }
        saved()
        connect()
        roundTrip()
        udpRoundTrip(512)
        click(R.string.disconnect)
        stopped()
        assertNotNull(context.packageManager.getApplicationInfo("com.android.settings", 0))
        io { store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(selectedPackages = setOf("com.android.settings"))) }
        saved()
        connect()
        // This process is now excluded by the real Android VPN builder.
        directOriginUnavailable()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_000
            val host = InetAddress.getByName(argument("originHost"))
            socket.send(DatagramPacket(byteArrayOf(1), 1, host, 8081))
            assertThrows(java.net.SocketTimeoutException::class.java) { socket.receive(DatagramPacket(ByteArray(16), 16)) }
        }
    }

    @Test fun masqueCustomFailoverToHttps() {
        useMasque(TlsProfile.CUSTOM)
        var fallbackId = ""
        io {
            val primary = store.activeProfile()
            store.saveProfile(primary.copy(config = primary.config.copy(port = argument("blackholeMasquePort").toInt())))
            val fallback = store.addProfile()
            fallbackId = fallback.id
            store.saveProfile(fallback.copy(name = "HTTPS fallback", config = primary.config.copy(
                type = ProxyType.HTTPS, port = argument("proxyPort").toInt(), customJa3 = "")))
            store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(
                failoverMode = FailoverMode.SELECTED, failoverProfileIds = listOf(fallback.id)))
        }
        saved()
        directOriginUnavailable()
        click(R.string.connect)
        if (VpnService.prepare(context) != null) systemButton("android:id/button1")
        await("Real MASQUE timeout did not fail over to custom HTTPS", timeout = 90_000) {
            store.connectionProfileId() == fallbackId && ProxyVpnService.isRunning &&
                VpnRuntimeState.connection.value == VpnConnectionState.CONNECTED
        }
        saved()
        assertNotEquals(fallbackId, store.activeProfileId())
        roundTrip()
        assertNotEquals(VpnTransportProtocol.HTTP_3, VpnRuntimeState.transportProtocol.value)
    }

    private fun useMasque(fingerprint: TlsProfile) = io {
        store.saveProfile(store.activeProfile().let {
            it.copy(config = it.config.copy(type = ProxyType.MASQUE, port = argument("masquePort").toInt(),
                customJa3 = if (fingerprint == TlsProfile.CUSTOM) "771,4865-4866-4867,0-10-13-16-43-51-57,29-23,0" else ""))
        })
        store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(tlsProfile = fingerprint,
            customJa3 = if (fingerprint == TlsProfile.CUSTOM) "771,4865-4866-4867,0-10-13-16-43-51,29-23,0" else ""))
    }

    private fun udpRoundTrip(size: Int) = DatagramSocket().use { socket ->
        socket.soTimeout = 15_000
        udpRoundTrip(size, socket)
    }

    private fun udpRoundTrip(size: Int, socket: DatagramSocket) {
        val payload = ByteArray(size) { (it % 251).toByte() }
        java.nio.ByteBuffer.wrap(payload).putLong(System.nanoTime())
        val host = InetAddress.getByName(argument("originHost"))
        val request = DatagramPacket(payload, payload.size, host, 8081)
        val timeout = socket.soTimeout
        val deadline = SystemClock.elapsedRealtime() + timeout
        try {
            socket.send(request)
            // UDP/QUIC DATAGRAM has no delivery guarantee. Acknowledge a unique
            // idempotent probe within the original deadline; never rerun the test.
            while (true) {
                socket.soTimeout = minOf(1_000, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt())
                val reply = DatagramPacket(ByteArray(size + 1), size + 1)
                try {
                    socket.receive(reply)
                } catch (error: java.net.SocketTimeoutException) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw error
                    socket.send(request)
                    continue
                }
                // Late duplicate replies belong to earlier probes on this socket.
                if (!reply.data.copyOfRange(0, 8).contentEquals(payload.copyOfRange(0, 8))) {
                    check(SystemClock.elapsedRealtime() < deadline) { "UDP probe was not acknowledged" }
                    continue
                }
                assertEquals(host, reply.address)
                assertEquals(8081, reply.port)
                assertArrayEquals(payload, reply.data.copyOf(reply.length))
                return
            }
        } finally {
            socket.soTimeout = timeout
        }
    }

    @Test fun deniedVpnConsentDoesNotStartTunnel() {
        assertNotNull("Runner must revoke VPN consent before this test", VpnService.prepare(context))
        click(R.string.connect)
        systemButton("android:id/button2")
        appNode(androidx.test.uiautomator.By.text(text(R.string.vpn_permission_denied)))
        stopped()
        assertNotNull(VpnService.prepare(context))
        directOriginUnavailable()
    }

    @Test fun notificationActionStopsRealService() {
        connect()
        roundTrip()
        device.openNotification()
        val action = device.wait(androidx.test.uiautomator.Until.findObject(
            androidx.test.uiautomator.By.desc(text(R.string.disconnect))), 10_000)
        assertNotNull("VPN notification must expose Disconnect", action)
        action.click()
        stopped()
        device.pressBack()
        directOriginUnavailable()
    }

    @Test fun stopDuringSshHandshakeCannotReviveVpn() {
        io { store.saveProfile(store.activeProfile().let {
            it.copy(config = it.config.copy(type = ProxyType.SSH, port = argument("stallPort").toInt(), username = "proxy", acceptAnyHostKey = true))
        }) }
        // Real JNI Start waits for this fixture's SSH banner; the host records acceptance.
        if (VpnService.prepare(context) != null) {
            scenario.onActivity { it.startActivity(VpnService.prepare(it)) }
            systemButton("android:id/button1")
        }
        ProxyVpnService.start(context)
        await("JNI startup did not reach SSH fixture") {
            device.executeShellCommand("ls ${argument("sshAccepted")}").trim() == argument("sshAccepted")
        }
        assertEquals(VpnConnectionState.CONNECTING, VpnRuntimeState.connection.value)
        ProxyVpnService.stop(context)
        stopped()
        device.executeShellCommand("touch ${argument("sshRelease")}")
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertFalse("A stale native start revived the VPN", ProxyVpnService.isRunning)
            assertEquals(VpnConnectionState.DISCONNECTED, VpnRuntimeState.connection.value)
            assertFalse(store.isConnectionDesired())
            SystemClock.sleep(100)
        }
        directOriginUnavailable()
    }

    @Test fun deniedNotificationsStillAllowVpn() {
        if (Build.VERSION.SDK_INT < 33) return // Permission does not exist on API 26.
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        click(R.string.connect)
        systemButton("com.android.permissioncontroller:id/permission_deny_button")
        if (VpnService.prepare(context) != null) systemButton("android:id/button1")
        await("VPN failed after notification denial") { ProxyVpnService.isRunning && vpnPresent() }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        roundTrip()
    }
}
