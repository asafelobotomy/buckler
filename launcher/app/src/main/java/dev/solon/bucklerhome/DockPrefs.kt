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

    fun isPinned(context: Context, packageName: String): Boolean =
        packageName in loadPins(context)

    fun pin(context: Context, packageName: String): List<String> {
        val pins = loadPins(context).toMutableList()
        if (packageName !in pins) {
            pins.add(packageName)
            savePins(context, pins)
        }
        return pins
    }

    fun unpin(context: Context, packageName: String): List<String> {
        val pins = loadPins(context).toMutableList()
        pins.removeAll { it == packageName }
        savePins(context, pins)
        return pins
    }

    fun moveLeft(context: Context, packageName: String): List<String> {
        val pins = loadPins(context).toMutableList()
        val i = pins.indexOf(packageName)
        if (i > 0) {
            val tmp = pins[i]
            pins[i] = pins[i - 1]
            pins[i - 1] = tmp
            savePins(context, pins)
        }
        return pins
    }

    fun moveRight(context: Context, packageName: String): List<String> {
        val pins = loadPins(context).toMutableList()
        val i = pins.indexOf(packageName)
        if (i >= 0 && i < pins.lastIndex) {
            val tmp = pins[i]
            pins[i] = pins[i + 1]
            pins[i + 1] = tmp
            savePins(context, pins)
        }
        return pins
    }
}
