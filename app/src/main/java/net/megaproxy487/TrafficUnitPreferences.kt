package net.megaproxy487

import android.content.Context

internal object TrafficUnitPreferences {
    private const val PREFERENCES = "display_preferences"
    private const val KEY_TRAFFIC_UNITS = "traffic_units"

    fun current(context: Context): TrafficUnitSystem {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_TRAFFIC_UNITS, null)
        return TrafficUnitSystem.entries.firstOrNull { it.name == stored } ?: TrafficUnitSystem.IEC
    }

    fun set(context: Context, unitSystem: TrafficUnitSystem) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRAFFIC_UNITS, unitSystem.name)
            .apply()
    }
}
