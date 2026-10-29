package dev.solon.bucklerhome

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of At a Glance: a glyph and a short sentence. Lower [rank] shows first. */
data class GlanceLine(val glyph: String, val text: String, val rank: Int)

/**
 * At a Glance's rules: what is worth a line right now, from the calendar, reminders and weather.
 * Plain rules, no ranking model and nothing promoted: an event under way or within the next hours,
 * reminders due, a change in the weather, the sun when it is near, and tomorrow's first event in
 * the evening. Pure, so it is tested without a TV.
 */
object GlanceRules {
    private const val SOON_HOURS = 12L
    private const val RAIN_LIKELY = 50
    private const val RAIN_LOOKAHEAD = 8
    private const val SUN_NEAR_MINUTES = 120L

    fun lines(
        now: ZonedDateTime,
        events: List<AgendaEvent>,
        tasks: List<AgendaTask>,
        weather: GlanceWeather?,
        use24h: Boolean,
        fahrenheit: Boolean,
    ): List<GlanceLine> = buildList {
        val clock = DisplayFormat.clock(use24h)
        val today = now.toLocalDate()

        // Under way now, then the next timed event within the next hours.
        events.firstOrNull { !it.allDay && !it.start.isAfter(now) && it.end.isAfter(now) }?.let {
            add(GlanceLine("●", "Now: ${it.title}, until ${clock.format(it.end)}", 0))
        }
        events.firstOrNull { !it.allDay && it.start.isAfter(now) && Duration.between(now, it.start).toHours() < SOON_HOURS }?.let {
            add(GlanceLine("◷", "${it.title} at ${clock.format(it.start)} · ${inTime(now, it.start)}", 1))
        }
        events.filter { it.allDay && it.start.toLocalDate() == today }.take(2).forEach {
            add(GlanceLine("★", "Today: ${it.title}", 3))
        }

        val due = Agenda.dueBy(tasks, today)
        if (due.isNotEmpty()) {
            val overdue = due.count { it.due!!.toLocalDate().isBefore(today) || (it.dueHasTime && it.due.isBefore(now)) }
            val names = due.take(2).joinToString(", ") { it.title } + if (due.size > 2) " and ${due.size - 2} more" else ""
            val head = when {
                overdue > 0 && overdue == due.size -> if (due.size == 1) "Overdue" else "${due.size} overdue"
                due.size == 1 -> "Reminder"
                else -> "${due.size} reminders today"
            }
            add(GlanceLine("✓", "$head: $names", 2))
        }

        if (weather != null) add(weatherLine(now, weather, clock, fahrenheit))
        sunLine(now, weather, clock)?.let(::add)

        // In the evening, what tomorrow starts with.
        if (now.hour >= 18) {
            val tomorrow = today.plusDays(1)
            events.firstOrNull { it.start.toLocalDate() == tomorrow }?.let {
                val time = if (it.allDay) "" else " at ${clock.format(it.start)}"
                add(GlanceLine("→", "Tomorrow: ${it.title}$time", 5))
            }
        }
    }.sortedBy { it.rank }

    /** "Rain likely from 15:00", "Dry after 14:00", or today's summary with the range. */
    fun weatherLine(now: ZonedDateTime, w: GlanceWeather, clock: DateTimeFormatter, fahrenheit: Boolean): GlanceLine {
        val ahead = w.hours.filter { it.at.isAfter(now.toLocalDateTime().minusHours(1)) }.take(RAIN_LOOKAHEAD)
        val wetNow = isWet(w.now.code)
        val (glyph, summary) = WeatherClient.describe(w.now.code, w.now.isDay)
        if (!wetNow) {
            ahead.firstOrNull { it.rainChance >= RAIN_LIKELY || isWet(it.code) }?.let { h ->
                val what = if (h.code in 71..77 || h.code in 85..86) "Snow" else "Rain"
                return GlanceLine("☂", "$what likely from ${clock.format(h.at)} (${h.rainChance}%)", 4)
            }
        } else {
            ahead.drop(1).firstOrNull { it.rainChance < RAIN_LIKELY && !isWet(it.code) }?.let { h ->
                return GlanceLine(glyph, "$summary now, drying up around ${clock.format(h.at)}", 4)
            }
        }
        val range = listOfNotNull(
            w.highC?.let { "high ${WeatherClient.formatTemperature(it, fahrenheit)}" },
            w.lowC?.let { "low ${WeatherClient.formatTemperature(it, fahrenheit)}" },
        ).joinToString(", ")
        return GlanceLine(glyph, if (range.isEmpty()) summary else "Today: ${range.replaceFirstChar { it.uppercase() }}", 4)
    }

    private fun sunLine(now: ZonedDateTime, w: GlanceWeather?, clock: DateTimeFormatter): GlanceLine? {
        val t = now.toLocalTime()
        fun near(at: LocalTime?) = at != null && at.isAfter(t) && Duration.between(t, at).toMinutes() <= SUN_NEAR_MINUTES
        return when {
            near(w?.sunset) -> GlanceLine("☀", "Sunset at ${clock.format(w!!.sunset)}", 6)
            near(w?.sunrise) -> GlanceLine("☀", "Sunrise at ${clock.format(w!!.sunrise)}", 6)
            else -> null
        }
    }

    fun isWet(code: Int): Boolean = code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99

    /** "in 25 min", "in 2 h", "in 2 h 30". */
    fun inTime(now: ZonedDateTime, at: ZonedDateTime): String {
        val minutes = Duration.between(now, at).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 60 -> "in $minutes min"
            minutes % 60 == 0L || minutes >= 6 * 60 -> "in ${(minutes + 30) / 60} h"
            else -> "in ${minutes / 60} h ${minutes % 60}"
        }
    }
}
