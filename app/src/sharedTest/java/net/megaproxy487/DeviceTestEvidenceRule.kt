package net.megaproxy487

import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/** Test-only lifecycle shared by instrumentation and plain JVM regression tests. */
internal class DeviceTestEvidenceRule(
    private val prepare: () -> Unit,
    private val cleanup: () -> Unit,
    private val close: () -> Unit,
    private val evidence: (Throwable) -> Unit,
    private val diagnostics: () -> Unit,
) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val errors = mutableListOf<Throwable>()
            var captured = false
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    errors.add(error)
                    if (error !is AssumptionViolatedException && !captured) {
                        captured = true
                        try { evidence(error) } catch (captureError: Throwable) { errors.add(captureError) }
                    }
                }
            }
            attempt(prepare)
            if (errors.isEmpty()) attempt { base.evaluate() }
            attempt(cleanup)
            attempt(close)
            attempt(diagnostics)
            MultipleFailureException.assertEmpty(errors)
        }
    }
}
