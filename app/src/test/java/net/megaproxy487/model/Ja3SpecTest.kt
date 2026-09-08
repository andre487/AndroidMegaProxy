package net.megaproxy487.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ja3SpecTest {
    @Test fun parsesCanonicalJa3() {
        val spec = Ja3Spec.parse("771,4865-4866,0-10-11,29-23,0")!!
        assertEquals(771, spec.tlsVersion)
        assertEquals(listOf(4865, 4866), spec.cipherSuites)
    }

    @Test fun rejectsValuesRejectedByNativeParser() {
        listOf(
            "-1,4865,0,29,0", "770,4865,0,29,0", "773,4865,0,29,0",
            "771,,0,29,0", "771,4865,0,29,256", "771,+4865,0,29,0",
            "771,4865, ,29,0", "771," + List(257) { "4865" }.joinToString("-") + ",0,29,0",
            "771,4865,0,29,0" + " ".repeat(8192),
        ).forEach { assertNull(it, Ja3Spec.parse(it)) }
    }

    @Test fun rejectsMalformedJa3() {
        assertNull(Ja3Spec.parse("771,4865,0"))
        assertNull(Ja3Spec.parse("771,70000,0,29,0"))
    }
}
