package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportProtocolTest {
    @Test fun onlyExplicitHttpsFallbackShowsUdpWarning() {
        assertEquals(true, isHttp3FallbackDiagnostic("event=transport_selection preferred=http3 selected=https result=fallback reason=timeout udp=false"))
        assertEquals(false, isHttp3FallbackDiagnostic("event=transport_selection preferred=http3 selected=http3 result=selected udp=true"))
        assertEquals(false, isHttp3FallbackDiagnostic("event=connection protocol=http3 result=failed reason=certificate"))
    }

    @Test fun reportsHttp3FromMasqueTunnel() {
        assertEquals(VpnTransportProtocol.HTTP_3,
            transportProtocolFromDiagnostic("event=connection protocol=http3 stage=tunnel result=established"))
    }

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
