package net.megaproxy487

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficUnitPreferencesTest {
    @Test
    fun iecIsTheDefaultForMissingOrInvalidPreferences() {
        assertEquals(TrafficUnitSystem.IEC, TrafficUnitPreferences.fromStoredValue(null))
        assertEquals(TrafficUnitSystem.IEC, TrafficUnitPreferences.fromStoredValue("unknown"))
    }

    @Test
    fun everyUnitSystemRoundTripsThroughItsStoredValue() {
        TrafficUnitSystem.entries.forEach { unitSystem ->
            val stored = TrafficUnitPreferences.toStoredValue(unitSystem)
            assertEquals(unitSystem, TrafficUnitPreferences.fromStoredValue(stored))
        }
    }
}
