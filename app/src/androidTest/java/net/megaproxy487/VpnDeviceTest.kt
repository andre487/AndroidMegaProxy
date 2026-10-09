package net.megaproxy487

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.ProxyType
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
            assertEquals(VpnTransportProtocol.HTTP_3, VpnRuntimeState.transportProtocol.value)
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
        assertEquals(VpnTransportProtocol.HTTP_3, VpnRuntimeState.transportProtocol.value)
    }

    private fun useMasque(fingerprint: TlsProfile) = io {
        store.saveProfile(store.activeProfile().let {
            it.copy(config = it.config.copy(type = ProxyType.MASQUE, port = argument("masquePort").toInt()))
        })
        store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(tlsProfile = fingerprint))
    }

    private fun udpRoundTrip(size: Int) {
        val payload = ByteArray(size) { (it % 251).toByte() }
        DatagramSocket().use { socket ->
            socket.soTimeout = 15_000
            val host = InetAddress.getByName(argument("originHost"))
            socket.send(DatagramPacket(payload, payload.size, host, 8081))
            val reply = DatagramPacket(ByteArray(size + 1), size + 1)
            socket.receive(reply)
            assertEquals(host, reply.address)
            assertEquals(8081, reply.port)
            assertArrayEquals(payload, reply.data.copyOf(reply.length))
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
