package net.megaproxy487

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormattingTest {
    @Test
    fun formatsByteTotalsUsingIecUnitsByDefault() {
        assertEquals("0 B", formatTrafficBytes(0, locale = Locale.US))
        assertEquals("1023 B", formatTrafficBytes(1_023, locale = Locale.US))
        assertEquals("1.0 KiB", formatTrafficBytes(1_024, locale = Locale.US))
        assertEquals("1.5 MiB", formatTrafficBytes(1_572_864, locale = Locale.US))
        assertEquals("2.5 GiB", formatTrafficBytes(2_684_354_560, locale = Locale.US))
    }

    @Test
    fun formatsByteTotalsUsingOptionalSiUnits() {
        assertEquals("999 B", formatTrafficBytes(999, TrafficUnitSystem.SI, Locale.US))
        assertEquals("1.0 KB", formatTrafficBytes(1_000, TrafficUnitSystem.SI, Locale.US))
        assertEquals("1.5 MB", formatTrafficBytes(1_500_000, TrafficUnitSystem.SI, Locale.US))
        assertEquals("2.5 GB", formatTrafficBytes(2_500_000_000, TrafficUnitSystem.SI, Locale.US))
    }

    @Test
    fun usesTheSelectedLocaleForFractions() {
        assertEquals("1,5 MiB", formatTrafficBytes(1_572_864, locale = Locale.forLanguageTag("ru")))
    }

    @Test
    fun formatsRatesAndClampsInvalidNegativeCounters() {
        assertEquals("1.5 MiB/s", formatTrafficRate(1_572_864.0, locale = Locale.US))
        assertEquals("1.5 MB/s", formatTrafficRate(1_500_000.0, TrafficUnitSystem.SI, Locale.US))
        assertEquals("0 B/s", formatTrafficRate(-1.0, locale = Locale.US))
        assertEquals("0 B", formatTrafficBytes(-1, locale = Locale.US))
    }
}
