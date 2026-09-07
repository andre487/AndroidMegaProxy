package net.megaproxy487

import java.util.Locale

/** Translated templates never override the system formatting locale. */
internal fun formatUiText(template: String, vararg args: Any, locale: Locale = Locale.getDefault()): String =
    if (args.isEmpty()) template else String.format(locale, template, *args)

internal fun formatConnectionStartedAt(startedAtMillis: Long, locale: Locale = Locale.getDefault()): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM, locale,
    ).format(java.util.Date(startedAtMillis))
