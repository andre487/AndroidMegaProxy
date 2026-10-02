package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeConnectionStatsTest {
    @Test fun distinguishesUnavailableFromZeroRetransmits() {
        val unavailable = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":null,"tcpRetransmits":null}""")
        assertNull(unavailable.tcpRttMillis)
        assertNull(unavailable.tcpRetransmits)
        val available = parseNativeConnectionStats("""{"downloadBytes":5,"uploadBytes":3,"tcpRttMillis":42.5,"tcpRetransmits":0}""")
        assertEquals(NativeConnectionStats(5, 3, 42.5, 0), available)
    }
}
