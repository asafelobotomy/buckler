package dev.solon.bucklerhome

import android.content.Context

/** What the header's Side Stage shows while nothing is playing. */
enum class SideIdle(val label: String) {
    NOTHING("Nothing"),
    GLANCE("At a Glance"),
    COMPANION("Companion"),
}

/** How often the Comics and Spurious correlations stages move on to a new one. */
enum class StageTurn(val label: String, val millis: Long) {
    M1("1 min", 60_000L),
    M2("2 min", 2 * 60_000L),
    M5("5 min", 5 * 60_000L),
    M10("10 min", 10 * 60_000L),
    M30("30 min", 30 * 60_000L),
    ;

    companion object {
        fun fromName(raw: String?, default: StageTurn): StageTurn = entries.firstOrNull { it.name == raw } ?: default
    }
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
    private const val KEY_SCROLL_TITLES = "now_playing_scroll_titles"
    private const val KEY_COMIC_TURN = "stage_comic_turn"
    private const val KEY_SPURIOUS_TURN = "stage_spurious_turn"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sideIdle(context: Context): SideIdle =
        prefs(context).getString(KEY_SIDE_IDLE, null)?.let { raw -> SideIdle.entries.firstOrNull { it.name == raw } }
            ?: SideIdle.NOTHING

    fun setSideIdle(context: Context, idle: SideIdle) = prefs(context).edit().putString(KEY_SIDE_IDLE, idle.name).apply()

    fun companion(context: Context): CompanionKind = CompanionKind.fromWire(prefs(context).getString(KEY_COMPANION, null))

    fun setCompanion(context: Context, kind: CompanionKind) = prefs(context).edit().putString(KEY_COMPANION, kind.wire()).apply()

    /** Now Playing's song, artist and album scroll sideways when too long (else they are cut short). */
    fun scrollTitles(context: Context): Boolean = prefs(context).getBoolean(KEY_SCROLL_TITLES, true)

    fun setScrollTitles(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SCROLL_TITLES, on).apply()

    fun comicTurn(context: Context): StageTurn = StageTurn.fromName(prefs(context).getString(KEY_COMIC_TURN, null), StageTurn.M2)

    fun setComicTurn(context: Context, turn: StageTurn) = prefs(context).edit().putString(KEY_COMIC_TURN, turn.name).apply()

    fun spuriousTurn(context: Context): StageTurn = StageTurn.fromName(prefs(context).getString(KEY_SPURIOUS_TURN, null), StageTurn.M10)

    fun setSpuriousTurn(context: Context, turn: StageTurn) = prefs(context).edit().putString(KEY_SPURIOUS_TURN, turn.name).apply()

    /** Calendars left off Home, by id (all are shown by default). */
    fun hiddenCalendars(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY_HIDDEN_CALENDARS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun setCalendarShown(context: Context, id: Long, shown: Boolean) {
        val hidden = hiddenCalendars(context).let { if (shown) it - id else it + id }
        prefs(context).edit().putStringSet(KEY_HIDDEN_CALENDARS, hidden.map { it.toString() }.toSet()).apply()
    }
}
