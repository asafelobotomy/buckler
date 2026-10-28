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

/**
 * Settings → Main Stage: which stage, how tall, then only the chosen stage's own options (story
 * pace for News, city and span for Weather, media access for Now Playing and Lyrics).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainStagePane() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val layout = remember(tick) { HomeLayout.load(context) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val flow = Arrangement.spacedBy(12.dp)

    fun persist(next: HomeLayout) {
        HomeLayout.save(context, next)
        tick++
    }

    SectionTitle("Main Stage")
    SettingsRow("The band under the header. On Home, hold OK on it to switch.")
    MainStagePicker(layout.mainStage, title = "Shows") { persist(layout.copy(mainStage = it)) }
    Spacer(modifier = Modifier.height(18.dp))
    StageSizePicker(layout.stageSize, title = "Size") { persist(layout.copy(stageSize = it)) }
    Spacer(modifier = Modifier.height(18.dp))

    when (layout.mainStage) {
        MainStage.NEWS -> {
            PickerTitle("News")
            if (cfg.newsSources.isEmpty()) {
                SettingsRow("No sources chosen yet. Pick some in Settings → News.")
            } else {
                SettingsRow("◀ ▶ on the stage step through stories. Otherwise it changes every:")
                FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                    NewsPace.entries.forEach { pace ->
                        SettingsAction(pace.label, selected = pace == cfg.newsPace) {
                            DisplayPrefs.setNewsPace(context, pace)
                            tick++
                        }
                    }
                }
            }
        }
        MainStage.WEATHER -> {
            PickerTitle("Weather")
            FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                WeatherSpan.entries.forEach { span ->
                    SettingsAction(span.label, selected = span == cfg.weatherSpan) {
                        DisplayPrefs.setWeatherSpan(context, span)
                        tick++
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            WeatherPlaceSection(cfg, onChanged = { tick++ })
        }
        MainStage.NOW_PLAYING -> {
            PickerTitle("Now Playing")
            SettingsRow("The playing app with its controls. The header's mini player hides while this is on.")
            MediaAccessSection()
        }
        MainStage.LYRICS -> {
            PickerTitle("Lyrics")
            SettingsRow(
                "Lyrics for the song in the header's Now Playing, filled as they are sung with a bouncing dot. " +
                    "Word timing comes from NetEase when it has the song, otherwise LRCLIB lines with estimated words.",
            )
            val route = rememberAudioRoute()
            val offset = remember(tick, route.kind) { LyricsPrefs.offset(context, route.kind) }
            val songs = remember(tick) { LyricsPrefs.songOffsetCount(context) }
            SettingsRow(
                "Audio is going to ${route.kind.label}. Lyrics allow ${"%.1f".format(route.delayMs / 1000.0)} s " +
                    "for its delay automatically.",
            )
            SettingsRow(
                "${route.kind.label} fine-tune: ${LyricsPrefs.describe(offset)}. For the device; ◀ ▶ on the " +
                    "Lyrics stage nudge just the song playing and are remembered for it.",
            )
            FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                SettingsAction("Earlier") {
                    LyricsPrefs.setOffset(context, route.kind, offset + LyricsPrefs.STEP_MS)
                    tick++
                }
                SettingsAction("Later") {
                    LyricsPrefs.setOffset(context, route.kind, offset - LyricsPrefs.STEP_MS)
                    tick++
                }
                if (offset != 0L) {
                    SettingsAction("Reset") {
                        LyricsPrefs.setOffset(context, route.kind, 0)
                        tick++
                    }
                }
                if (songs > 0) {
                    SettingsAction("Clear $songs song nudge${if (songs == 1) "" else "s"}") {
                        LyricsPrefs.clearSongOffsets(context)
                        tick++
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            AutoSyncSection()
            Spacer(modifier = Modifier.height(12.dp))
            MediaAccessSection()
        }
        MainStage.SUGGESTED -> {
            PickerTitle("Suggested")
            SettingsRow("Cards for what you played recently. Hold OK on a card to pin or unpin it.")
            MediaAccessSection()
        }
    }
}

/** City and units for weather, shared by the header and the Weather stage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherPlaceSection(cfg: DisplayConfig, onChanged: () -> Unit) {
    val context = LocalContext.current
    var pickCity by remember { mutableStateOf(false) }
    val flow = Arrangement.spacedBy(12.dp)
    SettingsRow("City: ${cfg.place?.name ?: "not set — weather needs one"} (Open-Meteo)")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction(if (cfg.place == null) "Set city" else "Change city") { pickCity = true }
        if (cfg.place != null) {
            SettingsAction("Clear city") {
                DisplayPrefs.setPlace(context, null)
                onChanged()
            }
            SettingsAction("Units: ${if (cfg.fahrenheit) "°F" else "°C"}") {
                DisplayPrefs.setFahrenheit(context, !cfg.fahrenheit)
                onChanged()
            }
        }
    }
    if (pickCity) {
        WeatherLocationDialog(
            onDismiss = { pickCity = false },
            onPicked = { place ->
                DisplayPrefs.setPlace(context, place)
                pickCity = false
                onChanged()
            },
        )
    }
}

/** Media access status; the button only shows while access is missing. */
@Composable
fun MediaAccessSection() {
    val context = LocalContext.current
    val access = nowPlayingAccessGranted(context)
    SettingsRow(if (access) "Media access: granted." else "Media access: not granted, so nothing playing can be shown.")
    if (access) return
    SettingsAction("Grant media access") {
        launchFirstThatWorks(
            context,
            "notification access",
            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
        )
    }
}
