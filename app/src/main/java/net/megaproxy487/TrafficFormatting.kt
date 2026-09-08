package net.megaproxy487

import java.util.Locale

internal enum class TrafficUnitSystem {
    IEC,
    SI,
}

internal fun formatTrafficBytes(
    bytes: Long,
    unitSystem: TrafficUnitSystem = TrafficUnitSystem.IEC,
    locale: Locale = Locale.getDefault(),
): String {
    val base = if (unitSystem == TrafficUnitSystem.IEC) 1_024 else 1_000
    val units = if (unitSystem == TrafficUnitSystem.IEC) {
        arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    } else {
        arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    }
    var value = bytes.coerceAtLeast(0).toDouble()
    var unitIndex = 0
    while (value >= base && unitIndex < units.lastIndex) {
        value /= base
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(locale, "%.1f %s", value, units[unitIndex])
    }
}

internal fun formatTrafficRate(
    bytesPerSecond: Double,
    unitSystem: TrafficUnitSystem = TrafficUnitSystem.IEC,
    locale: Locale = Locale.getDefault(),
): String = "${formatTrafficBytes(bytesPerSecond.coerceAtLeast(0.0).toLong(), unitSystem, locale)}/s"

/** Scheduler delays and slow JNI sampling must not inflate bytes per second. */
internal fun sampledTrafficRate(currentBytes: Long, previousBytes: Long, elapsedMillis: Long): Double =
    if (elapsedMillis <= 0 || currentBytes < previousBytes) 0.0
    else (currentBytes - previousBytes).toDouble() * 1000.0 / elapsedMillis
