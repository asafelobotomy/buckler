package dev.solon.bucklerhome

import android.content.Context

/** What the header's Side Stage shows while nothing is playing. */
enum class SideIdle(val label: String) {
    NOTHING("Nothing"),
    GLANCE("At a Glance"),
    COMPANION("Companion"),
}

/**
 * Settings of the newer stage widgets (At a Glance, Calendar, Weather map, Companion and the Side
 * Stage's idle choice), kept apart from [DisplayPrefs] so that file stays readable. They use the
 * same preferences file.
 */
object StagePrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_SIDE_IDLE = "side_stage_idle"
    private const val KEY_COMPANION = "stage_companion"
    private const val KEY_HIDDEN_CALENDARS = "stage_hidden_calendars"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sideIdle(context: Context): SideIdle =
        prefs(context).getString(KEY_SIDE_IDLE, null)?.let { raw -> SideIdle.entries.firstOrNull { it.name == raw } }
            ?: SideIdle.NOTHING

    fun setSideIdle(context: Context, idle: SideIdle) = prefs(context).edit().putString(KEY_SIDE_IDLE, idle.name).apply()

    fun companion(context: Context): CompanionKind = CompanionKind.fromWire(prefs(context).getString(KEY_COMPANION, null))

    fun setCompanion(context: Context, kind: CompanionKind) = prefs(context).edit().putString(KEY_COMPANION, kind.wire()).apply()

    /** Calendars left off Home, by id (all are shown by default). */
    fun hiddenCalendars(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY_HIDDEN_CALENDARS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun setCalendarShown(context: Context, id: Long, shown: Boolean) {
        val hidden = hiddenCalendars(context).let { if (shown) it - id else it + id }
        prefs(context).edit().putStringSet(KEY_HIDDEN_CALENDARS, hidden.map { it.toString() }.toSet()).apply()
    }
}
