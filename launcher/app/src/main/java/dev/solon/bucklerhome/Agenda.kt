package dev.solon.bucklerhome

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One occurrence of a calendar event. All-day events start at local midnight of their day. */
data class AgendaEvent(
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val calendar: String,
    val color: Int,
    val location: String = "",
)

/** A reminder or task that is not done. [due] is null for one with no date. */
data class AgendaTask(val title: String, val due: ZonedDateTime?, val dueHasTime: Boolean, val source: String)

/** A calendar on the TV, for choosing which ones Home shows. */
data class AgendaCalendar(val id: Long, val name: String, val account: String, val color: Int)

/**
 * Events and reminders from what the TV already syncs, with no sign-in of Buckler's own:
 * Android's calendar store (filled by the Google account on the TV, DAVx⁵ for CalDAV such as
 * iCloud, Nextcloud or Fastmail, and ICSx⁵ for calendar links such as Proton's), the OpenTasks
 * task store that DAVx⁵ fills with CalDAV reminders, and Buckler's own [LocalReminders].
 */
object Agenda {
    /** The OpenTasks provider's authority and read permission (DAVx⁵ syncs CalDAV tasks into it). */
    const val TASKS_AUTHORITY = "org.dmfs.tasks"
    const val TASKS_PERMISSION = "org.dmfs.permission.READ_TASKS"

    fun canReadCalendar(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun canReadTasks(context: Context): Boolean =
        context.packageManager.resolveContentProvider(TASKS_AUTHORITY, 0) != null &&
            context.checkSelfPermission(TASKS_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun calendars(context: Context): List<AgendaCalendar> {
        if (!canReadCalendar(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        add(AgendaCalendar(cur.getLong(0), cur.getString(1).orEmpty(), cur.getString(2).orEmpty(), cur.getInt(3)))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Occurrences from now (or [from]) for [days], earliest first; [hidden] calendars are left
     * out. Recurring events come expanded, from the store's own Instances table.
     */
    fun events(
        context: Context,
        days: Long = 7,
        hidden: Set<Long> = emptySet(),
        from: ZonedDateTime = ZonedDateTime.now(),
    ): List<AgendaEvent> {
        if (!canReadCalendar(context)) return emptyList()
        // Start at local midnight so today's all-day events and ones already under way are kept.
        val begin = from.toLocalDate().atStartOfDay(from.zone)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin.toInstant().toEpochMilli())
            ContentUris.appendId(it, from.plusDays(days).toInstant().toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS,
        )
        val rows = runCatching {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        if (cur.getLong(7) in hidden) continue
                        if (cur.getInt(8) == CalendarContract.Instances.STATUS_CANCELED) continue
                        add(
                            event(
                                title = cur.getString(0).orEmpty(),
                                begin = cur.getLong(1),
                                end = cur.getLong(2),
                                allDay = cur.getInt(3) == 1,
                                calendar = cur.getString(4).orEmpty(),
                                color = cur.getInt(5),
                                location = cur.getString(6).orEmpty(),
                                zone = from.zone,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return rows.filter { it.end.isAfter(from) || (it.allDay && !it.end.toLocalDate().isBefore(from.toLocalDate())) }
            .distinctBy { Triple(it.title, it.start, it.end) }
            .sortedWith(compareBy({ it.start }, { !it.allDay }))
    }

    /**
     * All-day events are stored at UTC midnight; read them as that calendar date in the TV's
     * zone, or they land a day early west of Greenwich.
     */
    fun event(
        title: String,
        begin: Long,
        end: Long,
        allDay: Boolean,
        calendar: String,
        color: Int,
        location: String,
        zone: ZoneId,
    ): AgendaEvent {
        fun at(millis: Long): ZonedDateTime = if (allDay) {
            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
        } else {
            Instant.ofEpochMilli(millis).atZone(zone)
        }
        return AgendaEvent(title.ifBlank { "(No title)" }, at(begin), at(end), allDay, calendar, color, location)
    }

    /** Open reminders: Buckler's own and any synced CalDAV tasks, soonest due first, undated last. */
    fun tasks(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<AgendaTask> {
        val synced = if (canReadTasks(context)) syncedTasks(context, zone) else emptyList()
        return (LocalReminders.open(context, zone) + synced)
            .sortedWith(compareBy(nullsLast()) { it.due })
    }

    private fun syncedTasks(context: Context, zone: ZoneId): List<AgendaTask> = runCatching {
        val uri = Uri.parse("content://$TASKS_AUTHORITY/tasks")
        // OpenTasks' columns: STATUS 0/1 are needs-action/in-process; 2 completed, 3 cancelled.
        val projection = arrayOf("title", "due", "is_allday", "status", "list_name")
        context.contentResolver.query(uri, projection, "status < 2", null, null)?.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    val due = if (cur.isNull(1)) null else cur.getLong(1)
                    val allDay = cur.getInt(2) == 1
                    add(
                        AgendaTask(
                            title = cur.getString(0).orEmpty().ifBlank { "(No title)" },
                            due = due?.let {
                                if (allDay) {
                                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
                                } else {
                                    Instant.ofEpochMilli(it).atZone(zone)
                                }
                            },
                            dueHasTime = due != null && !allDay,
                            source = cur.getString(4).orEmpty(),
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Due today or overdue (undated ones are not "due"). */
    fun dueBy(tasks: List<AgendaTask>, day: LocalDate): List<AgendaTask> =
        tasks.filter { t -> t.due != null && !t.due.toLocalDate().isAfter(day) }
}
