package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportProtocolTest {
    @Test fun recognizesEstablishedTransportsOnly() {
        assertEquals(
            VpnTransportProtocol.HTTP_2,
            transportProtocolFromDiagnostic("event=connection mode=proxy protocol=http2 stage=tunnel result=established"),
        )
        assertEquals(
            VpnTransportProtocol.HTTP_1_1,
            transportProtocolFromDiagnostic("event=connection mode=proxy stage=tunnel result=established"),
        )
        assertEquals(
            VpnTransportProtocol.SSH_MULTIPLEXED,
            transportProtocolFromDiagnostic("event=transport_capability transport=ssh multiplexed=true"),
        )
        assertNull(transportProtocolFromDiagnostic("event=transport_capability transport=https_proxy h2_negotiated=true"))
    }
}
