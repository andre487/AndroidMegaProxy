package net.megaproxy487

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalVpnVerdictTest {
    @Test fun headerValuesMapToVpnVerdicts() {
        assertEquals(ExternalVpnVerdict.NOT_DETECTED, parseExternalVpnVerdict("0"))
        assertEquals(ExternalVpnVerdict.DETECTED, parseExternalVpnVerdict("1"))
        assertEquals(ExternalVpnVerdict.UNKNOWN_FORMAT, parseExternalVpnVerdict(null))
        assertEquals(ExternalVpnVerdict.UNKNOWN_FORMAT, parseExternalVpnVerdict("unexpected"))
    }
}
