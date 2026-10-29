package dev.solon.bucklerhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Google's calendar sync adapter: the Extras clean-up turns it off, and the calendar needs it. */
const val GOOGLE_CALENDAR_SYNC = "com.google.android.syncadapters.calendar"

/** Whether Google's calendar sync is installed but turned off on this TV. */
fun googleCalendarSyncOff(context: Context): Boolean = runCatching {
    val state = context.packageManager.getApplicationEnabledSetting(GOOGLE_CALENDAR_SYNC)
    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}.getOrDefault(false)

/**
 * Settings for At a Glance and Calendar: calendar access, Google's sync if a clean-up turned it
 * off, which calendars show, and Buckler's own reminders. Other providers come in through the
 * apps that sync them into Android: DAVx⁵ (iCloud, Nextcloud, Fastmail and other CalDAV, with
 * their reminders via OpenTasks) and ICSx⁵ for calendar links (Proton, Outlook, any .ics).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val flow = Arrangement.spacedBy(12.dp)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val canRead = remember(tick) { Agenda.canReadCalendar(context) }
    val syncOff = remember(tick) { googleCalendarSyncOff(context) }

    SectionTitle("Calendar and reminders")
    SettingsRow(
        "From the accounts the TV already syncs: the Google account on the TV, DAVx⁵ for iCloud, Nextcloud, " +
            "Fastmail and other CalDAV (with their reminders through OpenTasks), and ICSx⁵ for calendar links " +
            "such as Proton's or Outlook's. Buckler signs in to nothing itself.",
    )
    if (!canRead) {
        SettingsAction("Allow calendar access") {
            ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Agenda.TASKS_PERMISSION))
        }
    }
    if (syncOff) {
        Spacer(modifier = Modifier.height(10.dp))
        SettingsRow("Google Calendar Sync is off (Buckler's Extras clean-up turns it off), so Google events stay as they were.")
        SettingsAction(status ?: "Turn Google Calendar Sync on") {
            status = "Turning it on…"
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbSelf.connect(context) { status = "Allow debugging on the TV's prompt…" }.use { adb ->
                            if (!adb.shell("pm enable $GOOGLE_CALENDAR_SYNC").ok) error("not turned on")
                        }
                        ShieldJournal.remove(context, GOOGLE_CALENDAR_SYNC)
                        SetupState.setIntendedOff(context, SetupState.intendedOff(context) - GOOGLE_CALENDAR_SYNC)
                        "Google Calendar Sync is on."
                    }.getOrElse { if (it is AdbSelf.Failure.NotListening) "Turn on Network debugging first." else "Not turned on: ${it.message}" }
                }
                tick++
            }
        }
    }

    if (canRead) {
        val calendars = remember(tick) { Agenda.calendars(context) }
        val hidden = remember(tick) { StagePrefs.hiddenCalendars(context) }
        Spacer(modifier = Modifier.height(16.dp))
        PickerTitle("Calendars shown")
        if (calendars.isEmpty()) SettingsRow("No calendars on this TV yet.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            calendars.forEach { c ->
                val shown = c.id !in hidden
                SettingsAction(c.name.ifBlank { c.account }, selected = shown) {
                    StagePrefs.setCalendarShown(context, c.id, !shown)
                    tick++
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    RemindersEditor(tick) { tick++ }
}

/** Buckler's own reminders: add one with a quick due choice, tick off or remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersEditor(tick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(DuePreset.TODAY) }
    val flow = Arrangement.spacedBy(12.dp)
    val all = remember(tick) { LocalReminders.all(context) }
    PickerTitle("Buckler's reminders")
    FocusableTextField(value = text, onValueChange = { text = it }, hint = "Add a reminder", onDone = {})
    Spacer(modifier = Modifier.height(10.dp))
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        DuePreset.entries.forEach { p -> SettingsAction(p.label, selected = p == due) { due = p } }
        SettingsAction("Add") {
            val (at, timed) = due.resolve(LocalDateTime.now())
            LocalReminders.add(context, text, at, timed)
            text = ""
            onChanged()
        }
    }
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    all.forEach { r ->
        Spacer(modifier = Modifier.height(8.dp))
        val whenText = r.due?.let { if (r.dueHasTime) it.format(dateFmt) else it.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM")) }
            ?: "Someday"
        SettingsRow("${if (r.done) "✓ " else ""}${r.title}  ·  $whenText")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction(if (r.done) "Not done" else "Done") {
                LocalReminders.setDone(context, r.id, !r.done)
                onChanged()
            }
            SettingsAction("Remove") {
                LocalReminders.remove(context, r.id)
                onChanged()
            }
        }
    }
}

/** Quick due choices, since typing a date with a remote is slow. */
enum class DuePreset(val label: String) {
    TODAY("Today"),
    TONIGHT("Tonight 20:00"),
    TOMORROW("Tomorrow 09:00"),
    WEEKEND("Saturday"),
    SOMEDAY("Someday"),
    ;

    /** The due time and whether it has a time of day. */
    fun resolve(now: LocalDateTime): Pair<LocalDateTime?, Boolean> = when (this) {
        TODAY -> now.toLocalDate().atStartOfDay() to false
        TONIGHT -> now.toLocalDate().atTime(LocalTime.of(20, 0)) to true
        TOMORROW -> now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)) to true
        WEEKEND -> LocalDate.from(now).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).atStartOfDay() to false
        SOMEDAY -> null to false
    }
}
