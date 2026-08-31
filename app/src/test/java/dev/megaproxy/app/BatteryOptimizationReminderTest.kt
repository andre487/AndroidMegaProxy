package dev.megaproxy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationReminderTest {
    @Test
    fun firstRequestIsDue() {
        assertTrue(BatteryOptimizationReminder.isRequestDue(now = 1_000L, lastRequestAt = 0L))
    }

    @Test
    fun requestIsSuppressedDuringDailyInterval() {
        val lastRequestAt = 1_000L
        assertFalse(
            BatteryOptimizationReminder.isRequestDue(
                now = lastRequestAt + BatteryOptimizationReminder.REQUEST_INTERVAL_MS - 1,
                lastRequestAt = lastRequestAt,
            ),
        )
    }

    @Test
    fun requestIsDueAfterOneDay() {
        val lastRequestAt = 1_000L
        assertTrue(
            BatteryOptimizationReminder.isRequestDue(
                now = lastRequestAt + BatteryOptimizationReminder.REQUEST_INTERVAL_MS,
                lastRequestAt = lastRequestAt,
            ),
        )
    }

    @Test
    fun requestIsDueIfWallClockMovesBackwards() {
        assertTrue(BatteryOptimizationReminder.isRequestDue(now = 1_000L, lastRequestAt = 2_000L))
    }
}
