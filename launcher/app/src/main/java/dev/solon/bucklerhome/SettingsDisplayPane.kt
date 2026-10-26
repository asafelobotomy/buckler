package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

/** Settings → Header: clock, date, weather, news and Now Playing for the Home header. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayPane() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    var pickCity by remember { mutableStateOf(false) }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Header")
    SettingsRow(
        if (cfg.headerVisible) {
            "Pick what the top bar shows (● = shown). Settings is also under ◀ on any shelf."
        } else {
            "Header off: Home shows only shelves. Settings is under ◀ on the first tile of any shelf."
        },
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Header: ${if (cfg.headerVisible) "On" else "Off"}") {
            DisplayPrefs.setHeaderVisible(context, !cfg.headerVisible)
            tick++
        }
        if (cfg.headerVisible) {
            HeaderElement.entries.forEach { element ->
                SettingsAction(element.label, selected = element !in cfg.hidden) {
                    val hidden = if (element in cfg.hidden) cfg.hidden - element else cfg.hidden + element
                    DisplayPrefs.setHidden(context, hidden)
                    tick++
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsRow("Header size: logo, clock, icons and Now Playing scale together")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        HeaderSize.entries.forEach { size ->
            SettingsAction(size.label, selected = size == cfg.headerSize) {
                DisplayPrefs.setHeaderSize(context, size)
                tick++
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsRow(
        "Header shows: ${DisplayFormat.time(LocalTime.now(), cfg.use24h)}  ·  " +
            DisplayFormat.date(LocalDate.now(), cfg.dateStyle),
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Clock: ${if (cfg.use24h) "24-hour" else "12-hour"}") {
            DisplayPrefs.setUse24h(context, !cfg.use24h)
            tick++
        }
        SettingsAction("Date: ${DisplayFormat.date(LocalDate.now(), cfg.dateStyle)}") {
            DisplayPrefs.setDateStyle(context, cfg.dateStyle.next())
            tick++
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsRow("Weather: ${cfg.place?.name ?: "no city set"} (Open-Meteo)")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction(if (cfg.place == null) "Set city" else "Change city") { pickCity = true }
        SettingsAction("Clear city") {
            DisplayPrefs.setPlace(context, null)
            tick++
        }
        SettingsAction("Units: ${if (cfg.fahrenheit) "°F" else "°C"}") {
            DisplayPrefs.setFahrenheit(context, !cfg.fahrenheit)
            tick++
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    val newsLabel = when {
        cfg.newsShuffle -> "shuffled across every source"
        cfg.news != null -> cfg.news.label
        else -> "off"
    }
    SettingsRow("News: $newsLabel (public RSS). Shuffle takes each next story from a different source.")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        NewsSource.entries.forEach { source ->
            SettingsAction(source.label, selected = !cfg.newsShuffle && cfg.news == source) {
                DisplayPrefs.setNews(context, source)
                DisplayPrefs.setNewsShuffle(context, false)
                tick++
            }
        }
        SettingsAction("Shuffle", selected = cfg.newsShuffle) {
            DisplayPrefs.setNewsShuffle(context, !cfg.newsShuffle)
            tick++
        }
        SettingsAction("Off", selected = cfg.news == null && !cfg.newsShuffle) {
            DisplayPrefs.setNews(context, null)
            DisplayPrefs.setNewsShuffle(context, false)
            tick++
        }
    }
    if (cfg.news != null || cfg.newsShuffle) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            TickerSpeed.entries.forEach { speed ->
                SettingsAction("Speed: ${speed.label}", selected = speed == cfg.tickerSpeed) {
                    DisplayPrefs.setTickerSpeed(context, speed)
                    tick++
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    val access = nowPlayingAccessGranted(context)
    SettingsRow(
        "Now Playing: ${if (cfg.nowPlaying) "on" else "off"} · media access " +
            if (access) "granted" else "not granted",
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Now Playing: ${if (cfg.nowPlaying) "On" else "Off"}") {
            DisplayPrefs.setNowPlaying(context, !cfg.nowPlaying)
            tick++
        }
        SettingsAction(if (access) "Media access settings" else "Grant media access") {
            launchFirstThatWorks(
                context,
                "notification access",
                listOf(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                ),
            )
        }
    }

    if (pickCity) {
        WeatherLocationDialog(
            onDismiss = { pickCity = false },
            onPicked = { place ->
                DisplayPrefs.setPlace(context, place)
                pickCity = false
                tick++
            },
        )
    }
}
