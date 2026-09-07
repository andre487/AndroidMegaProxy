package net.megaproxy487

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFormattingTest {
    @Test
    fun translatedWordsKeepSystemNumberAndDateFormatting() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("Значение: 1,234.5", formatUiText("Значение: %,.1f", 1234.5))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Value: 1.234,5", formatUiText("Value: %,.1f", 1234.5))
            val date = java.time.LocalDate.of(2026, 3, 7)
            assertEquals("Дата: März", formatUiText("Дата: %tB", date))
            assertEquals(Locale.GERMANY, Locale.getDefault())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun connectionDateUsesSystemLocale() {
        val originalLocale = Locale.getDefault()
        val originalZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val timestamp = java.time.Instant.parse("2026-03-07T12:00:00Z").toEpochMilli()
            Locale.setDefault(Locale.US)
            assertTrue(formatConnectionStartedAt(timestamp).contains("3/7/26"))
            Locale.setDefault(Locale.GERMANY)
            assertTrue(formatConnectionStartedAt(timestamp).contains("07.03.26"))
        } finally {
            Locale.setDefault(originalLocale)
            java.util.TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun explicitSystemLocaleWinsOverAppProcessLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            assertEquals("Значение: 1,234.5",
                formatUiText("Значение: %,.1f", 1234.5, locale = Locale.US))
            assertEquals("ru", Locale.getDefault().language)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun unformattedTextMayContainLiteralPercent() {
        assertEquals("100%", formatUiText("100%"))
    }
}
