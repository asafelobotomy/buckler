package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

/**
 * Settings → Appearance → Header: the row at the top of Home. What it shows, its size and clock,
 * then a section for each widget in it that is on: Weather, the News ticker, and the Side Stage
 * (the mini stage in the middle, where Now Playing appears). Hidden pieces take their options with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeaderPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val stage = remember(tick) { HomeLayout.load(context).mainStage }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Header")
    SettingsRow(
        if (cfg.headerVisible) {
            "The row at the top of Home. ● = shown."
        } else {
            "Header off: Home shows the Main Stage and shelves, with a Home options card at the end for Settings."
        },
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Header: ${if (cfg.headerVisible) "On" else "Off"}") {
            DisplayPrefs.setHeaderVisible(context, !cfg.headerVisible)
            tick++
        }
    }

    if (cfg.headerVisible) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Shows")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            HeaderElement.entries.forEach { element ->
                SettingsAction(element.label, selected = element !in cfg.hidden) {
                    if (element !in cfg.hidden && !canHideHeaderElement(element, cfg.hidden)) {
                        Toast.makeText(context, "Keep Edit Home or Settings in the header", Toast.LENGTH_SHORT).show()
                        return@SettingsAction
                    }
                    val hidden = if (element in cfg.hidden) cfg.hidden - element else cfg.hidden + element
                    DisplayPrefs.setHidden(context, hidden)
                    tick++
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Size")
        SettingsRow("Logo, clock, icons and the Side Stage scale together.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            HeaderSize.entries.forEach { size ->
                SettingsAction(size.label, selected = size == cfg.headerSize) {
                    DisplayPrefs.setHeaderSize(context, size)
                    tick++
                }
            }
        }
    }

    // The 12/24-hour choice also drives the screensaver clock and weather times, so it stays.
    val showsClock = cfg.shows(HeaderElement.CLOCK)
    val showsDate = cfg.shows(HeaderElement.DATE)
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle(if (showsDate) "Time and date" else "Time")
    SettingsRow(
        listOfNotNull(
            DisplayFormat.time(LocalTime.now(), cfg.use24h).takeIf { showsClock || !cfg.headerVisible },
            DisplayFormat.date(LocalDate.now(), cfg.dateStyle).takeIf { showsDate },
        ).joinToString("  ·  ").ifEmpty { "Used by the screensaver clock and weather times." },
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Clock: ${if (cfg.use24h) "24-hour" else "12-hour"}") {
            DisplayPrefs.setUse24h(context, !cfg.use24h)
            tick++
        }
        if (showsDate) {
            SettingsAction("Date: ${DisplayFormat.date(LocalDate.now(), cfg.dateStyle)}") {
                DisplayPrefs.setDateStyle(context, cfg.dateStyle.next())
                tick++
            }
        }
    }
    if (!cfg.headerVisible) return

    if (cfg.shows(HeaderElement.WEATHER)) {
        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle("Weather")
        WeatherSection(HomeSection.HEADER, cfg, onChanged = { tick++ })
    }

    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle("News ticker")
    SettingsRow("A dim line of headlines under the header. No sources turns it off.")
    if (cfg.showsNews) {
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            TickerSpeed.entries.forEach { speed ->
                SettingsAction(speed.label, selected = speed == cfg.tickerSpeed) {
                    DisplayPrefs.setTickerSpeed(context, speed)
                    tick++
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
    NewsSection(HomeSection.HEADER, cfg, onChanged = { tick++ })

    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle("Side Stage")
    SettingsRow(
        "The mini stage in the middle of the header. It shows what is playing, with previous, play/pause and " +
            "next, while something plays.",
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Now Playing", selected = cfg.nowPlaying) {
            DisplayPrefs.setNowPlaying(context, !cfg.nowPlaying)
            tick++
        }
    }
    if (stage == MainStage.NOW_PLAYING && cfg.nowPlaying) {
        SettingsRow("Hidden while the Main Stage shows Now Playing, so it is not on screen twice.")
    }
    if (cfg.nowPlaying) MediaAccessSection()
    Spacer(modifier = Modifier.height(14.dp))
    PickerTitle("While nothing plays")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SideIdle.entries.forEach { idle ->
            SettingsAction(idle.label, selected = idle == cfg.sideIdle) {
                StagePrefs.setSideIdle(context, idle)
                tick++
            }
        }
    }
    when (cfg.sideIdle) {
        SideIdle.GLANCE -> {
            Spacer(modifier = Modifier.height(14.dp))
            CalendarSection()
        }
        SideIdle.COMPANION -> {
            Spacer(modifier = Modifier.height(14.dp))
            CompanionPicker(cfg.companion) { tick++ }
        }
        SideIdle.NOTHING -> Unit
    }
}
