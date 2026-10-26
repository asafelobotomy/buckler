package dev.solon.bucklerhome

import android.content.Context

private const val PREFS = "bucklerhome"
private const val KEY_DOCK_PINS = "dock_pins"

object DockPrefs {
    fun loadPins(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOCK_PINS, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_DOCK_PACKAGES
        }
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePins(context: Context, pins: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOCK_PINS, pins.joinToString(","))
            .apply()
    }
}
