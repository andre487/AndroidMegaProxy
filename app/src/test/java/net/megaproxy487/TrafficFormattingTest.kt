package net.megaproxy487

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormattingTest {
    @Test
    fun formatsByteTotalsAcrossUnits() {
        assertEquals("0 B", formatTrafficBytes(0, Locale.US))
        assertEquals("999 B", formatTrafficBytes(999, Locale.US))
        assertEquals("1.0 KB", formatTrafficBytes(1_000, Locale.US))
        assertEquals("1.5 MB", formatTrafficBytes(1_500_000, Locale.US))
        assertEquals("2.5 GB", formatTrafficBytes(2_500_000_000, Locale.US))
        assertEquals("3.0 TB", formatTrafficBytes(3_000_000_000_000, Locale.US))
    }

    @Test
    fun usesTheSelectedLocaleForFractions() {
        assertEquals("1,5 MB", formatTrafficBytes(1_500_000, Locale.forLanguageTag("ru")))
    }

    @Test
    fun formatsRatesAndClampsInvalidNegativeCounters() {
        assertEquals("1.5 MB/s", formatTrafficRate(1_500_000.0, Locale.US))
        assertEquals("0 B/s", formatTrafficRate(-1.0, Locale.US))
        assertEquals("0 B", formatTrafficBytes(-1, Locale.US))
    }
}
