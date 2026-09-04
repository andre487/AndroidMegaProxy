package net.megaproxy487

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFormattingTest {
    @Test
    fun formatsCountryForSelectedLocale() {
        assertEquals("🇳🇱 Netherlands (NL)", formatCountry("nl", Locale.ENGLISH))
        assertEquals("🇳🇱 Нидерланды (NL)", formatCountry("NL", Locale.forLanguageTag("ru")))
    }

    @Test
    fun leavesInvalidCountryCodeVisible() {
        assertEquals("unknown", formatCountry("unknown", Locale.ENGLISH))
    }
}
