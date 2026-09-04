package net.megaproxy487

import java.util.Locale

private val trafficUnits = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

internal fun formatTrafficBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unitIndex = 0
    while (value >= 1_000 && unitIndex < trafficUnits.lastIndex) {
        value /= 1_000
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${trafficUnits[unitIndex]}"
    } else {
        String.format(locale, "%.1f %s", value, trafficUnits[unitIndex])
    }
}

internal fun formatTrafficRate(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String =
    "${formatTrafficBytes(bytesPerSecond.coerceAtLeast(0.0).toLong(), locale)}/s"
