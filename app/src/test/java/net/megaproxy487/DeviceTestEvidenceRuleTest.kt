package net.megaproxy487

import org.junit.Assert.*
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

class DeviceTestEvidenceRuleTest {
    private fun run(events: MutableList<String>, failures: Map<String, Throwable> = emptyMap()) {
        fun step(name: String) {
            events.add(name)
            failures[name]?.let { throw it }
        }
        DeviceTestEvidenceRule(
            prepare = { step("prepare") }, cleanup = { step("cleanup") },
            close = { step("close") }, evidence = { step("evidence") },
            diagnostics = { step("diagnostics") },
        ).apply(object : Statement() {
            override fun evaluate() = step("body")
        }, Description.EMPTY).evaluate()
    }

    @Test fun successStillCollectsDiagnostics() {
        val events = mutableListOf<String>()
        run(events)
        assertEquals(listOf("prepare", "body", "cleanup", "close", "diagnostics"), events)
    }

    @Test fun eachFailureIsCapturedBeforeTheNextPhaseAndStillRunsCleanup() {
        for (phase in listOf("prepare", "body", "cleanup", "close", "diagnostics")) {
            val events = mutableListOf<String>()
            val failure = IllegalStateException(phase)
            val thrown = runCatching { run(events, mapOf(phase to failure)) }.exceptionOrNull()
            assertSame(phase, failure, thrown)
            val expected = mutableListOf("prepare", "body", "cleanup", "close", "diagnostics")
            if (phase == "prepare") expected.remove("body")
            expected.add(expected.indexOf(phase) + 1, "evidence")
            assertEquals(phase, expected, events)
        }
    }

    @Test fun evidenceAndCleanupFailuresDoNotHideTheOriginalFailure() {
        val events = mutableListOf<String>()
        val failures = listOf("body", "evidence", "cleanup", "close", "diagnostics")
            .associateWith { IllegalStateException(it) }
        val thrown = runCatching { run(events, failures) }.exceptionOrNull()
        assertTrue(thrown is MultipleFailureException)
        assertEquals(failures.values.toList(), (thrown as MultipleFailureException).failures)
        assertEquals(1, events.count { it == "evidence" })
    }

    @Test fun skippedBodyStillCleansUpWithoutFailureEvidence() {
        val events = mutableListOf<String>()
        val skipped = AssumptionViolatedException("unsupported API")
        assertSame(skipped, runCatching { run(events, mapOf("body" to skipped)) }.exceptionOrNull())
        assertEquals(listOf("prepare", "body", "cleanup", "close", "diagnostics"), events)
    }
}
