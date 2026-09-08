package net.megaproxy487.vpn

import org.junit.Assert.*
import org.junit.Test

class NativeCallbackTest {
    @Test fun protectorFailsClosedAndReportsExceptions() {
        val failure = SecurityException("denied")
        val failures = mutableListOf<Exception>()
        val protector = BridgeProtector({ true }, { throw failure }, failures::add)
        assertFalse(protector.protect(42L))
        assertEquals(listOf(failure), failures)
    }

    @Test fun descriptorRangeIsCheckedBeforeNarrowingLongToInt() {
        val calls = mutableListOf<Int>()
        val protector = BridgeProtector({ true }, { calls += it; true }, { throw it })
        assertFalse(protector.protect(-1L))
        assertFalse(protector.protect(1L shl 32))
        assertTrue(protector.protect(0L))
        assertTrue(protector.protect(Int.MAX_VALUE.toLong()))
        assertEquals(listOf(0, Int.MAX_VALUE), calls)
    }

    @Test fun stoppedCallbacksCannotProtectOrPublish() {
        var enabled = true
        var calls = 0
        val protector = BridgeProtector({ enabled }, { calls++; true }, { throw it })
        val reporter = BridgeReporter({ enabled }, { calls++ }, { throw it })
        reporter.report("active")
        assertTrue(protector.protect(1))
        enabled = false
        reporter.report("late")
        assertFalse(protector.protect(1))
        assertEquals(2, calls)
    }

    @Test fun reportingFailureDoesNotEscapeAndNextEventStillWorks() {
        var attempts = 0
        val failures = mutableListOf<Exception>()
        val reporter = BridgeReporter({ true }, {
            if (attempts++ == 0) throw IllegalStateException("notification unavailable")
        }, failures::add)
        reporter.report("first")
        reporter.report("second")
        assertEquals(2, attempts)
        assertEquals(1, failures.size)
        assertEquals(reporter, reporter)
        assertEquals(System.identityHashCode(reporter), reporter.hashCode())
    }

    @Test fun fatalErrorsAreNotDisguisedAsSuccessfulCallbacks() {
        val reporter = BridgeReporter({ true }, { throw OutOfMemoryError("synthetic") }, { throw it })
        assertThrows(OutOfMemoryError::class.java) { reporter.report("event") }
    }
}
