package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val WEATHER_EVERY_MS = 30 * 60_000L
private const val AGENDA_EVERY_MS = 5 * 60_000L
private const val RETRY_MS = 2 * 60_000L

/** Events and open reminders for the stages, as last read. */
data class AgendaSnapshot(val events: List<AgendaEvent>, val tasks: List<AgendaTask>, val canRead: Boolean)

/** Last results, so coming back to Home shows them at once instead of a blank. */
private object StageCache {
    var glance: Pair<Place, GlanceWeather>? = null
    var agenda: AgendaSnapshot? = null
    var glanceAt = 0L
}

/** At a Glance's weather for [place], fetched while Home is on screen (every 30 minutes). */
@Composable
fun rememberGlanceWeather(place: Place?): State<GlanceWeather?> {
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(StageCache.glance?.takeIf { it.first == place }?.second, place, visible) {
        if (place == null || !visible) return@produceState
        val age = System.currentTimeMillis() - StageCache.glanceAt
        if (StageCache.glance?.first == place && age < WEATHER_EVERY_MS) delay(WEATHER_EVERY_MS - age)
        while (true) {
            val fresh = withContext(Dispatchers.IO) { GlanceWeatherClient.fetch(place) }
            if (fresh != null) {
                StageCache.glance = place to fresh
                StageCache.glanceAt = System.currentTimeMillis()
                value = fresh
            }
            delay(if (fresh != null) WEATHER_EVERY_MS else RETRY_MS)
        }
    }
}

/**
 * The calendar and reminders, re-read every 5 minutes while Home is on screen and whenever
 * [refresh] changes (Home resuming, a reminder ticked off). Reading is local and quick.
 */
@Composable
fun rememberAgenda(refresh: Any?, days: Long = 7): State<AgendaSnapshot?> {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(StageCache.agenda, refresh, visible, days) {
        if (!visible) return@produceState
        while (true) {
            val fresh = withContext(Dispatchers.IO) { readAgenda(context, days) }
            StageCache.agenda = fresh
            value = fresh
            delay(AGENDA_EVERY_MS)
        }
    }
}

fun readAgenda(context: Context, days: Long): AgendaSnapshot {
    LocalReminders.tidy(context)
    return AgendaSnapshot(
        events = Agenda.events(context, days, StagePrefs.hiddenCalendars(context)),
        tasks = Agenda.tasks(context),
        canRead = Agenda.canReadCalendar(context),
    )
}
