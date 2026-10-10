package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeConnectionStatsTest {
    @Test fun distinguishesUnavailableFromZeroRetransmits() {
        val unavailable = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":null,"tcpRetransmits":null}""")
        assertNull(unavailable.tcpRttMillis)
        assertNull(unavailable.tcpRetransmits)
        assertNull(unavailable.quicRttMillis)
        assertNull(unavailable.quicPacketsLost)
        val available = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":42.5,"tcpRetransmits":0}""")
        assertEquals(NativeConnectionStats(5, 3, 42.5, 0), available)
    }
    @Test fun parsesQuicMetricsWithoutPretendingTheyAreTcp() {
        val available = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":null,"tcpRetransmits":null,"quicRttMillis":42.5,"quicPacketsLost":0}""")
        assertEquals(NativeConnectionStats(5, 3, null, null, 42.5, 0), available)
        val unavailable = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":null,"tcpRetransmits":null,"quicRttMillis":null,"quicPacketsLost":null}""")
        assertNull(unavailable.quicRttMillis)
        assertNull(unavailable.quicPacketsLost)
    }
}
