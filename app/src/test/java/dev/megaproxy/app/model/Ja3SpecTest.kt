package dev.megaproxy.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ja3SpecTest {
    @Test fun parsesCanonicalJa3() {
        val spec = Ja3Spec.parse("771,4865-4866,0-10-11,29-23,0")!!
        assertEquals(771, spec.tlsVersion)
        assertEquals(listOf(4865, 4866), spec.cipherSuites)
    }

    @Test fun rejectsMalformedJa3() {
        assertNull(Ja3Spec.parse("771,4865,0"))
        assertNull(Ja3Spec.parse("771,70000,0,29,0"))
    }
}
