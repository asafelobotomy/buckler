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
 * Settings → Header. What the top bar shows, then a section for each thing that is on: size,
 * date style, weather city, ticker speed, media access. Hidden pieces take their options with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Header")
    SettingsRow(
        if (cfg.headerVisible) {
            "The row above the Main Stage. ● = shown."
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
            SettingsAction("Now Playing", selected = cfg.nowPlaying) {
                DisplayPrefs.setNowPlaying(context, !cfg.nowPlaying)
                tick++
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Size")
        SettingsRow("Logo, clock, icons and Now Playing scale together.")
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

    if (cfg.shows(HeaderElement.WEATHER)) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Weather")
        WeatherPlaceSection(cfg, onChanged = { tick++ })
    }

    if (cfg.showsNews) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("News ticker")
        SettingsRow("Sources are in Settings → News.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            TickerSpeed.entries.forEach { speed ->
                SettingsAction(speed.label, selected = speed == cfg.tickerSpeed) {
                    DisplayPrefs.setTickerSpeed(context, speed)
                    tick++
                }
            }
        }
    }

    if (cfg.showsNowPlaying) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Now Playing")
        MediaAccessSection()
    }
}

/** Settings → News: feeds for the header ticker and the News stage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val flow = Arrangement.spacedBy(12.dp)
    val summary = when {
        cfg.newsSources.isEmpty() -> "off"
        cfg.newsSources.size <= 3 -> cfg.newsSources.joinToString(", ") { it.label }
        else -> "${cfg.newsSources.size} sources, one story from each in turn"
    }

    SectionTitle("News")
    SettingsRow("For the header ticker and the News stage. Now: $summary.")
    PickerTitle("Region")
    SettingsRow("Swaps the world headlines for that region and keeps gaming, business and tech.")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        NewsRegion.entries.forEach { region ->
            SettingsAction(region.label, selected = region == cfg.newsRegion) {
                DisplayPrefs.setNewsRegion(context, region)
                tick++
            }
        }
    }
    NewsCategory.entries.forEach { category ->
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle(category.label)
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            NewsSource.entries.filter { source ->
                source.category == category && (
                    category != NewsCategory.WORLD ||
                        cfg.newsRegion in source.regions ||
                        source in cfg.newsSources
                    )
            }.forEach { source ->
                SettingsAction(source.label, selected = source in cfg.newsSources) {
                    val next = if (source in cfg.newsSources) cfg.newsSources - source else cfg.newsSources + source
                    DisplayPrefs.setNewsSources(context, NewsSource.entries.filter { it in next })
                    tick++
                }
            }
        }
    }
    if (cfg.newsSources.isNotEmpty()) {
        Spacer(modifier = Modifier.height(18.dp))
        SettingsAction("Turn all news off") {
            DisplayPrefs.setNewsSources(context, emptyList())
            tick++
        }
    }
}
