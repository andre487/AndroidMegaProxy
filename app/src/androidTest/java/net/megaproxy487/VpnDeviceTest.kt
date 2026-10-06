package net.megaproxy487

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.ProxyType
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
            androidx.test.uiautomator.By.text(text(R.string.disconnect))), 10_000)
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
            SocketControl.accepted(context, argument("controlPort").toInt())
        }
        assertEquals(VpnConnectionState.CONNECTING, VpnRuntimeState.connection.value)
        ProxyVpnService.stop(context)
        stopped()
        SocketControl.release(context, argument("controlPort").toInt())
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

private object SocketControl {
    // Fixture coordination must remain reachable while the TUN is awaiting native startup.
    private fun request(context: android.content.Context, port: Int, path: String): String {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = manager.allNetworks.first {
            manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false
        }
        return network.socketFactory.createSocket("10.0.2.2", port).use {
            it.soTimeout = 2_000
            it.getOutputStream().write("GET /$path HTTP/1.0\r\n\r\n".toByteArray())
            it.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n")
        }
    }
    fun accepted(context: android.content.Context, port: Int) = request(context, port, "accepted") == "yes"
    fun release(context: android.content.Context, port: Int) { assertEquals("yes", request(context, port, "release")) }
}
