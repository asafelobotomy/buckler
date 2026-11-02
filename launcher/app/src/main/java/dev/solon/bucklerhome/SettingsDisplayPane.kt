package dev.solon.bucklerhome

import android.widget.Toast
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

/**
 * Settings → Appearance → Header: the row at the top of Home. Whether it shows, what is in it, its
 * size, and the Side Stage (the mini stage in the middle, where Now Playing appears). Each widget's
 * own options are its page under Widgets, linked from here.
 */
@Composable
fun HeaderPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val stage = remember(tick) { HomeLayout.stored(context).mainStage }

    SectionTitle("Header")
    SettingsRow(
        if (cfg.headerVisible) {
            "The row at the top of Home."
        } else {
            "Header off: Home shows the Main Stage and shelves, with a Home options card at the end for Settings."
        },
    )
    SettingsToggle("Header", cfg.headerVisible) {
        DisplayPrefs.setHeaderVisible(context, !cfg.headerVisible)
        tick++
    }
    if (!cfg.headerVisible) return

    val shown = HeaderElement.entries.count { it !in cfg.hidden }
    SettingsLink("Shows", value = "$shown of ${HeaderElement.entries.size}") { HeaderShowsList() }
    SettingsChoice(
        "Size",
        HeaderSize.entries,
        cfg.headerSize,
        { it.label },
        note = "Logo, clock, icons and the Side Stage scale together.",
    ) {
        DisplayPrefs.setHeaderSize(context, it)
        tick++
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Side Stage")
    SettingsRow("The mini stage in the middle of the header: what is playing, with its controls, while something plays.")
    SettingsToggle("Now Playing", cfg.nowPlaying) {
        DisplayPrefs.setNowPlaying(context, !cfg.nowPlaying)
        tick++
    }
    if (stage == MainStage.NOW_PLAYING && cfg.nowPlaying) {
        SettingsRow("Hidden while the Main Stage shows Now Playing, so it is not on screen twice.")
    }
    SettingsChoice("While nothing plays", StoreLibrary.sideIdles(context, cfg.sideIdle), cfg.sideIdle, { it.label }) {
        StagePrefs.setSideIdle(context, it)
        tick++
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Widgets in the header")
    if (cfg.shows(HeaderElement.WEATHER)) SettingsPaneLink("Weather", SettingsPane.WEATHER, value = cfg.place?.name ?: "No city")
    SettingsPaneLink("News ticker", SettingsPane.NEWS, value = if (cfg.showsNews) "${cfg.newsSources.size} sources" else "Off")
    if (cfg.nowPlaying) SettingsPaneLink("Now Playing", SettingsPane.NOW_PLAYING)
    when (cfg.sideIdle) {
        SideIdle.GLANCE -> SettingsPaneLink("Calendar & reminders", SettingsPane.CALENDAR)
        SideIdle.COMPANION -> SettingsPaneLink("Companion", SettingsPane.COMPANION, value = cfg.companion.label)
        SideIdle.NOTHING -> Unit
    }
}

/** What the header shows, each ticked when shown; Edit Home or Settings always stays. */
@Composable
private fun HeaderShowsList() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    HeaderElement.entries.forEach { element ->
        SettingsCheck(element.label, element !in cfg.hidden) {
            if (element !in cfg.hidden && !canHideHeaderElement(element, cfg.hidden)) {
                Toast.makeText(context, "Keep Edit Home or Settings in the header", Toast.LENGTH_SHORT).show()
                return@SettingsCheck
            }
            DisplayPrefs.setHidden(context, if (element in cfg.hidden) cfg.hidden - element else cfg.hidden + element)
            tick++
        }
    }
}
