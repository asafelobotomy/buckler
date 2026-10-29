package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/*
 * The quick options shown in Home's hold menus under the widget choice: only the active widget's,
 * and only what fits a remote (Settings keeps the rest, one press away). Each writes its setting
 * straight away and calls `onChanged`, so Home reloads and shows it behind the menu.
 */

private val Flow = Arrangement.spacedBy(12.dp)

/** A hold menu's title and the line under it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HoldMenuTitle(title: String, hint: String) {
    Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
    Text(
        text = hint,
        fontSize = 13.sp,
        color = ColorMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
}

/** The heading over a group of choices in a hold menu. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HoldMenuHeading(text: String) {
    Text(text = text, fontSize = 14.sp, color = ColorSection, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionsNote(text: String) {
    Text(text = text, fontSize = 14.sp, color = ColorMuted, modifier = Modifier.padding(bottom = 4.dp))
}

/** The Main Stage widget's own options, headed by its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageOptions(stage: MainStage, onChanged: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    fun changed() {
        tick++
        onChanged()
    }
    HoldMenuHeading(stage.label)
    when (stage) {
        MainStage.NEWS -> if (cfg.stageNews.isEmpty()) {
            OptionsNote("No news sources on the stage yet: choose them in Settings.")
        } else {
            OptionsNote("A new story every:")
            FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                NewsPace.entries.forEach { pace ->
                    SettingsAction(pace.label, selected = pace == cfg.newsPace) {
                        DisplayPrefs.setNewsPace(context, pace)
                        changed()
                    }
                }
            }
        }
        MainStage.NOW_PLAYING -> {
            MediaAccessOption("Shows what is playing, with its controls.")
            ScrollTitlesOption(cfg.scrollTitles) { changed() }
        }
        MainStage.LYRICS -> LyricsTiming(tick) { changed() }
        MainStage.WEATHER -> FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            WeatherSpan.entries.forEach { span ->
                SettingsAction(span.label, selected = span == cfg.weatherSpan) {
                    DisplayPrefs.setWeatherSpan(context, span)
                    changed()
                }
            }
        }
        MainStage.SUGGESTED -> OptionsNote("Hold OK on a card to pin or unpin it.")
        MainStage.GLANCE, MainStage.CALENDAR -> CalendarsShown(tick) { changed() }
        MainStage.WEATHER_MAP -> OptionsNote("◀ ▶ on the map step through the hours.")
        MainStage.COMPANION -> CompanionChoice(cfg.companion) { changed() }
    }
}

/** Lyrics timing for where the sound is going: earlier, later, back to automatic. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsTiming(tick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val route = rememberAudioRoute()
    val offset = remember(tick, route.kind) { LyricsPrefs.offset(context, route.kind) }
    OptionsNote("Timing for ${route.kind.label}: ${LyricsPrefs.describe(offset)}.")
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Earlier") {
            LyricsPrefs.setOffset(context, route.kind, offset + LyricsPrefs.STEP_MS)
            onChanged()
        }
        SettingsAction("Later") {
            LyricsPrefs.setOffset(context, route.kind, offset - LyricsPrefs.STEP_MS)
            onChanged()
        }
        if (offset != 0L) {
            SettingsAction("Reset") {
                LyricsPrefs.setOffset(context, route.kind, 0)
                onChanged()
            }
        }
    }
}

/** Which calendars Home shows; asking for access stays in Settings, where it explains itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarsShown(tick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    if (!Agenda.canReadCalendar(context)) {
        OptionsNote("Allow calendar access in Settings to show your events.")
        return
    }
    val calendars = remember(tick) { Agenda.calendars(context) }
    val hidden = remember(tick) { StagePrefs.hiddenCalendars(context) }
    if (calendars.isEmpty()) {
        OptionsNote("No calendars on this TV yet.")
        return
    }
    OptionsNote("Calendars shown:")
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        calendars.forEach { c ->
            val shown = c.id !in hidden
            SettingsAction(c.name.ifBlank { c.account }, selected = shown) {
                StagePrefs.setCalendarShown(context, c.id, !shown)
                onChanged()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionChoice(current: CompanionKind, onChanged: () -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        CompanionKind.entries.forEach { kind ->
            SettingsAction(kind.label, selected = kind == current) {
                StagePrefs.setCompanion(context, kind)
                onChanged()
            }
        }
    }
}

/** Switches whether Now Playing's long song, artist and album lines scroll; off cuts them short. */
@Composable
fun ScrollTitlesToggle(on: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    SettingsAction("Scroll long titles", selected = on) {
        StagePrefs.setScrollTitles(context, !on)
        onChanged()
    }
}

/** [ScrollTitlesToggle] on a line of its own, under a note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrollTitlesOption(on: Boolean, onChanged: () -> Unit) {
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow, modifier = Modifier.padding(top = 10.dp)) {
        ScrollTitlesToggle(on, onChanged)
    }
}

/** A line about the widget, and a way to grant media access while it is missing. */
@Composable
private fun MediaAccessOption(about: String) {
    val context = LocalContext.current
    if (nowPlayingAccessGranted(context)) {
        OptionsNote(about)
    } else {
        OptionsNote("Needs media access to see what is playing.")
        MediaAccessSection()
    }
}

/**
 * Hold OK on the header's Side Stage: whether it shows what is playing, what it shows while
 * nothing plays, then that widget's options.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SideStageDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    fun changed() {
        tick++
        onChanged()
    }
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { currentFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        // Opened by a hold: the held OK's repeats and release must not pick whatever has focus.
        DialogPanel(modifier = Modifier.swallowOrphanSelect(), minWidth = 460.dp) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HoldMenuTitle("Side Stage", "Hold OK on the Side Stage to open this again.")
                FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                    SettingsAction("Now Playing", selected = cfg.nowPlaying) {
                        DisplayPrefs.setNowPlaying(context, !cfg.nowPlaying)
                        changed()
                    }
                    if (cfg.nowPlaying) ScrollTitlesToggle(cfg.scrollTitles) { changed() }
                }
                HoldMenuHeading("While nothing plays")
                FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                    SideIdle.entries.forEach { idle ->
                        SettingsAction(
                            idle.label,
                            focusRequester = currentFocus.takeIf { idle == cfg.sideIdle },
                            selected = idle == cfg.sideIdle,
                        ) {
                            StagePrefs.setSideIdle(context, idle)
                            changed()
                        }
                    }
                }
                when (cfg.sideIdle) {
                    SideIdle.GLANCE -> {
                        HoldMenuHeading(SideIdle.GLANCE.label)
                        CalendarsShown(tick) { changed() }
                    }
                    SideIdle.COMPANION -> {
                        HoldMenuHeading(SideIdle.COMPANION.label)
                        CompanionChoice(cfg.companion) { changed() }
                    }
                    SideIdle.NOTHING -> if (cfg.nowPlaying) {
                        HoldMenuHeading("Now Playing")
                        MediaAccessOption("Shows what is playing, with its controls; empty while nothing plays.")
                    }
                }
                HoldMenuFooter(SettingsPane.HEADER, onDismiss)
            }
        }
    }
}

/** "More in Settings" (that page, with every option) and Close. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HoldMenuFooter(pane: SettingsPane, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow, modifier = Modifier.padding(top = 18.dp)) {
        extra()
        SettingsAction("More in Settings") {
            onDismiss()
            BucklerSettingsActivity.open(context, pane)
        }
        SettingsAction("Close", onClick = onDismiss)
    }
}
