package net.megaproxy487

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProxyType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class DnsDeviceTest : DeviceTestBase() {
    @Test fun resolverModesThroughRealTun() {
        for (type in listOf(ProxyType.HTTPS, ProxyType.SSH, ProxyType.SOCKS5, ProxyType.MASQUE)) {
            for (mode in listOf("https", "tcp", "udp")) {
                val direct = mode == "udp" && type in listOf(ProxyType.HTTPS, ProxyType.SSH)
                // A host-native proxy can use its own origin on macOS, where Docker's
                // dynamically allocated SOCKS UDP relay is not host-routable.
                val dnsHost = arguments.getString("${type.name.lowercase()}DnsHost") ?: argument("originHost")
                val endpoint = when {
                    mode == "https" -> "https://${dnsHost}:8444/dns-query"
                    direct -> "udp://10.0.2.2:${argument("directDnsPort")}"
                    else -> "$mode://$dnsHost:10053"
                }
                android.util.Log.i("DnsDeviceTest", "Testing $type $mode direct=$direct")
                io {
                    val profile = store.activeProfile()
                    store.saveProfile(profile.copy(config = profile.config.copy(
                        type = type,
                        port = argument(when(type) {
                            ProxyType.SSH -> "sshPort"
                            ProxyType.SOCKS5 -> "socksPort"
                            ProxyType.MASQUE -> "masquePort"
                            else -> "proxyPort"
                        }).toInt(),
                        username = if(type == ProxyType.SSH) "proxy" else "exit",
                        password = if (arguments.containsKey("${type.name.lowercase()}DnsHost")) argument("localProxyPassword") else argument("proxyPassword"), acceptAnyHostKey = true,
                        allowInvalidProxyCertificate = true, dnsProvider = DnsProvider.CUSTOM,
                        customDohUrl = endpoint, allowInsecureDns = mode != "https",
                        allowInvalidDohCertificate = mode == "https",
                    )))
                    store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(
                        routeAllApps = false, selectedPackages = setOf(context.packageName), bypassLocalNetworks = true))
                }
                connect()
                dnsQuery(true)
                dnsQuery(false)
                click(R.string.disconnect)
                stopped()
            }
        }
        // The same certificate must fail without the explicit DoH exception.
        io {
            val profile = store.activeProfile()
            store.saveProfile(profile.copy(config = profile.config.copy(type = ProxyType.SOCKS5,
                port = argument("socksPort").toInt(), dnsProvider = DnsProvider.CUSTOM,
                customDohUrl = "https://${arguments.getString("socks5DnsHost") ?: argument("originHost")}:8444/dns-query",
                allowInvalidDohCertificate = false, allowInsecureDns = false)))
        }
        net.megaproxy487.vpn.PersistentDiagnosticLog.clear()
        connect()
        try {
            // TCP retransmits during Android's UID route installation. A one-shot UDP
            // packet can be lost before it reaches TUN and never exercise TLS validation.
            dnsQuery(true)
            fail("Untrusted DoH certificate unexpectedly accepted")
        } catch (_: IOException) {
            await("Expected certificate failure in DNS diagnostics") {
                net.megaproxy487.vpn.PersistentDiagnosticLog.readTail(128 * 1024).contains("event=doh result=failed provider_index=0 reason=certificate")
            }
        }
        click(R.string.disconnect)
        stopped()
    }

    private fun dnsQuery(tcp: Boolean) {
        val query = byteArrayOf(0x48, 0x73, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) +
            "private".toByteArray(Charsets.US_ASCII) + byteArrayOf(4) +
            "test".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 1, 0, 1)
        val response = if (tcp) Socket().use { socket ->
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress("10.77.0.2", 53), 10_000)
            DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
            DataInputStream(socket.getInputStream()).let { input -> ByteArray(input.readUnsignedShort()).also { input.readFully(it) } }
        } else DatagramSocket().use { socket ->
            socket.soTimeout = 5_000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName("10.77.0.2"), 53))
            val packet = DatagramPacket(ByteArray(512), 512)
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        }
        assertEquals(0x48, response[0].toInt())
        assertEquals(0x73, response[1].toInt())
        assertEquals(1, response[7].toInt())
        assertArrayEquals(byteArrayOf(203.toByte(), 0, 113, 42), response.takeLast(4).toByteArray())
    }
}
