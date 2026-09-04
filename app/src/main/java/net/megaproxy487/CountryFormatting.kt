package net.megaproxy487

import java.util.Locale

internal fun formatCountry(countryCode: String, locale: Locale = Locale.getDefault()): String {
    val code = countryCode.trim().uppercase(Locale.ROOT)
    if (!code.matches(Regex("[A-Z]{2}"))) return countryCode
    val name = Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
    val flag = code.map { character ->
        String(Character.toChars(0x1F1E6 + character.code - 'A'.code))
    }.joinToString("")
    return "$flag $name ($code)"
}
