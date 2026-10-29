package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class GlanceTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private val now = ZonedDateTime.of(2026, 10, 29, 13, 0, 0, 0, zone)

    private fun event(title: String, start: ZonedDateTime, minutes: Long = 60, allDay: Boolean = false) =
        AgendaEvent(title, start, start.plusMinutes(minutes), allDay, "Me", 0)

    private fun weather(code: Int = 1, rainAt: Int? = null): GlanceWeather {
        val hours = (0 until 12).map { h ->
            val at = now.toLocalDateTime().plusHours(h.toLong())
            GlanceHour(at, 14.0, if (h == rainAt) 61 else code, if (h == rainAt) 80 else 10)
        }
        return GlanceWeather(Weather(14.0, code, true), hours, 16.0, 9.0, LocalTime.of(8, 10), LocalTime.of(17, 5))
    }

    @Test
    fun theNextEventComesFirstWithHowLongUntilIt() {
        val lines = GlanceRules.lines(now, listOf(event("Dentist", now.plusMinutes(150))), emptyList(), weather(), true, false)
        assertEquals("Dentist at 15:30 · in 2 h 30", lines.first().text)
    }

    @Test
    fun anEventUnderWaySaysUntilWhen() {
        val lines = GlanceRules.lines(now, listOf(event("Standup", now.minusMinutes(10), 40)), emptyList(), null, true, false)
        assertEquals("Now: Standup, until 13:30", lines.first().text)
    }

    @Test
    fun rainAheadIsCalledOut() {
        val lines = GlanceRules.lines(now, emptyList(), emptyList(), weather(rainAt = 3), true, false)
        assertTrue(lines.any { it.text == "Rain likely from 16:00 (80%)" })
    }

    @Test
    fun dryWeatherGivesTheRange() {
        val line = GlanceRules.lines(now, emptyList(), emptyList(), weather(), true, false).single { it.rank == 4 }
        assertEquals("Today: High 16°C, low 9°C", line.text)
    }

    @Test
    fun remindersDueTodayAndOverdueAreCounted() {
        val tasks = listOf(
            AgendaTask("Bins", now.minusDays(1), false, "Buckler"),
            AgendaTask("Call Mum", now.plusHours(2), true, "Buckler"),
            AgendaTask("Someday thing", null, false, "Buckler"),
        )
        val line = GlanceRules.lines(now, emptyList(), tasks, null, true, false).single { it.glyph == "✓" }
        assertEquals("2 reminders today: Bins, Call Mum", line.text)
    }

    @Test
    fun theSunShowsOnlyWhenNear() {
        val early = GlanceRules.lines(now, emptyList(), emptyList(), weather(), true, false)
        assertTrue(early.none { it.text.startsWith("Sunset") })
        val late = GlanceRules.lines(now.withHour(16), emptyList(), emptyList(), weather(), true, false)
        assertTrue(late.any { it.text == "Sunset at 17:05" })
    }

    @Test
    fun allDayEventsKeepTheirDateWestOfGreenwich() {
        // Stored at UTC midnight; in New York that instant is the evening before.
        val utcMidnight = LocalDate.of(2026, 11, 3).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val e = Agenda.event("Election day", utcMidnight, utcMidnight + 86_400_000, true, "Holidays", 0, "", ZoneId.of("America/New_York"))
        assertEquals(LocalDate.of(2026, 11, 3), e.start.toLocalDate())
    }

    @Test
    fun remindersSurviveTheirJsonRoundTrip() {
        val list = listOf(
            LocalReminder(1, "Bins", LocalDateTime.of(2026, 10, 30, 0, 0), false),
            LocalReminder(2, "Call", LocalDateTime.of(2026, 10, 30, 18, 30), true, doneAt = 5L),
            LocalReminder(3, "Someday", null, false),
        )
        assertEquals(list, LocalReminders.parse(LocalReminders.toJson(list)))
        assertEquals(emptyList<LocalReminder>(), LocalReminders.parse("not json"))
    }

    @Test
    fun duePresetsLandWhereTheySay() {
        val thursday = LocalDateTime.of(2026, 10, 29, 13, 0)
        assertEquals(LocalDateTime.of(2026, 10, 30, 9, 0), DuePreset.TOMORROW.resolve(thursday).first)
        assertEquals(LocalDateTime.of(2026, 10, 31, 0, 0), DuePreset.WEEKEND.resolve(thursday).first)
        assertNull(DuePreset.SOMEDAY.resolve(thursday).first)
    }

    @Test
    fun inTimeReadsNaturally() {
        assertEquals("in 25 min", GlanceRules.inTime(now, now.plusMinutes(25)))
        assertEquals("in 2 h", GlanceRules.inTime(now, now.plusHours(2)))
        assertEquals("in 8 h", GlanceRules.inTime(now, now.plusMinutes(470)))
    }
}
