package net.megaproxy487.vpn

import org.junit.Assert.*
import org.junit.Test

class NativeCallbackTest {
    interface Reporter { fun report(message: String) }

    @Test fun objectMethodsAreSafeWithoutInvokingReporter() {
        val received = mutableListOf<String>()
        val reporter = nativeCallback(Reporter::class.java, "report") {
            received += it!![0] as String
            null
        } as Reporter
        val other = nativeCallback(Reporter::class.java, "report") { null }
        assertEquals(reporter, reporter)
        assertNotEquals(reporter, other)
        assertFalse(reporter.equals(null))
        assertEquals(System.identityHashCode(reporter), reporter.hashCode())
        assertTrue(reporter.toString().contains("Reporter"))
        assertTrue(received.isEmpty())
        reporter.report("connected")
        assertEquals(listOf("connected"), received)
    }
}
