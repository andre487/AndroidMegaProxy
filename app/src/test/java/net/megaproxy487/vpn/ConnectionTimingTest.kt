package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTimingTest {
    @Test fun durationPrecisionChangesAtTheRequestedBoundaries() {
        val cases = listOf(
            Triple(-1L, 0L, 10_000L),
            Triple(9_999L, 0L, 1L),
            Triple(10_000L, 10L, 10_000L),
            Triple(59_999L, 50L, 1L),
            Triple(60_000L, 60L, 30_000L),
            Triple(89_999L, 60L, 1L),
            Triple(90_000L, 90L, 30_000L),
            Triple(599_999L, 570L, 1L),
            Triple(600_000L, 600L, 60_000L),
            Triple(659_999L, 600L, 1L),
            Triple(86_399_999L, 86_340L, 1L),
            Triple(86_400_000L, 86_400L, 3_600_000L),
            Triple(89_999_999L, 86_400L, 1L),
            Triple(90_000_000L, 90_000L, 3_600_000L),
        )
        cases.forEach { (elapsed, seconds, next) ->
            assertEquals("elapsed=$elapsed", ConnectionDuration(seconds, next), connectionDuration(elapsed))
        }
    }

    @Test fun sessionSurvivesRepeatedConnectedUpdatesButRestartsAfterReconnect() {
        val session = connectionSessionForState(null, true, 1_000_000, 10_000)!!
        assertEquals(session, connectionSessionForState(session, true, 2_000_000, 20_000))
        assertEquals(60_000L, session.elapsedMillis(70_000))
        assertEquals(1_000_000L, session.startedAtMillis)
        val disconnected = connectionSessionForState(session, false, 2_000_000, 80_000)
        assertNull(disconnected)
        assertEquals(ConnectionSession(3_000_000, 90_000), connectionSessionForState(disconnected, true, 3_000_000, 90_000))
    }
}
