package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Main Stage → Calendar: the next days side by side, each with its events in order, and the
 * reminders due on the right. Everything comes from what the TV syncs (see [Agenda]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarStage(
    use24h: Boolean,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val context = LocalContext.current
    val agenda by rememberAgenda(days = 7)
    val now by rememberNow()
    val tight = metrics.cardHeight < 150.dp
    val dayCount = if (metrics.compact) 3 else 4
    val days = (0 until dayCount).map { now.toLocalDate().plusDays(it.toLong()) }
    val tasks = agenda?.tasks.orEmpty()
    val clock = DisplayFormat.clock(use24h, currentLocale())
    // Rows that fit under a day's title: roughly one per 26 dp.
    val rows = ((metrics.cardHeight.value - 60) / 26).toInt().coerceIn(1, 7)

    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Calendar")
        Row(
            modifier = Modifier
                // OK opens the calendar app; with none installed, the stage menu as before.
                .stageTarget(stageFocus, onDown, "Calendar", onLongPress = onHold) { if (!openCalendarApp(context)) onHold() }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .padding(horizontal = 20.dp, vertical = if (tight) 8.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                agenda?.canRead == false -> Text(
                    "Allow calendar access in Settings → Appearance → Main Stage → Calendar.",
                    color = ColorMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                else -> {
                    days.forEach { day ->
                        DayColumn(day, now, agenda?.events.orEmpty(), clock, rows, Modifier.weight(1f))
                    }
                    RemindersColumn(tasks, now, clock, rows, Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayColumn(
    day: LocalDate,
    now: ZonedDateTime,
    events: List<AgendaEvent>,
    clock: DateTimeFormatter,
    rows: Int,
    modifier: Modifier,
) {
    val today = now.toLocalDate()
    val title = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(shortDay(currentLocale()))
    }
    // Events touching this day: all-day ones first, then by start; ones already over today drop out.
    val mine = events.filter { e ->
        val first = e.start.toLocalDate()
        val last = (if (e.allDay) e.end.minusDays(1) else e.end).toLocalDate()
        !day.isBefore(first) && !day.isAfter(last) && (day != today || e.allDay || e.end.isAfter(now))
    }
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = if (day == today) ColorBrand else ColorSection, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (mine.isEmpty()) Text("Free", color = ColorHint, fontSize = 15.sp)
        mine.take(rows).forEach { e ->
            val time = if (e.allDay) "All day" else e.start.withZoneSameInstant(now.zone).format(clock)
            EntryRow(Color(e.color or 0xFF000000.toInt()), time, e.title, faded = !e.allDay && e.start.isBefore(now))
        }
        if (mine.size > rows) Text("+${mine.size - rows} more", color = ColorHint, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RemindersColumn(tasks: List<AgendaTask>, now: ZonedDateTime, clock: DateTimeFormatter, rows: Int, modifier: Modifier) {
    val today = now.toLocalDate()
    // Due by today first (overdue included), then dated ones ahead, then undated.
    val shown = Agenda.dueBy(tasks, today) + tasks.filter { it.due == null || it.due.toLocalDate().isAfter(today) }
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Reminders", color = ColorSection, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (shown.isEmpty()) Text("All done", color = ColorHint, fontSize = 15.sp)
        shown.take(rows).forEach { t ->
            val due = t.due
            val label = when {
                due == null -> "Someday"
                due.toLocalDate().isBefore(today) -> "Overdue"
                due.toLocalDate() == today -> if (t.dueHasTime) due.format(clock) else "Today"
                due.toLocalDate() == today.plusDays(1) -> "Tomorrow"
                else -> due.format(shortDay(currentLocale()))
            }
            EntryRow(ColorBrand, label, t.title, faded = false)
        }
        if (shown.size > rows) Text("+${shown.size - rows} more", color = ColorHint, fontSize = 13.sp)
    }
}

/** "Sat 31": a day within the coming week. */
private fun shortDay(locale: java.util.Locale): DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", locale)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EntryRow(dot: Color, time: String, title: String, faded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            text = time,
            color = ColorMuted,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).width(64.dp),
        )
        Text(
            text = title,
            color = if (faded) ColorMuted else ColorText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
