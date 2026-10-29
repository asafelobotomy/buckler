package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MAP_EVERY_MS = 60 * 60_000L
private const val MAP_RETRY_MS = 5 * 60_000L

/**
 * Main Stage → Weather map: pressure, wind and rain around [place] for the next day. ◀ ▶ step
 * through the hours; the map starts at the current hour.
 */
@Composable
fun WeatherMapStage(place: Place?, stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit, metrics: StageMetrics) {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    val grid by produceState<WeatherGrid?>(null, place, visible) {
        if (place == null || !visible) return@produceState
        while (true) {
            val fresh = withContext(Dispatchers.IO) { WeatherMapClient.fetch(place) }
            if (fresh != null) value = fresh
            delay(if (fresh != null) MAP_EVERY_MS else MAP_RETRY_MS)
        }
    }
    var hour by remember(grid) { mutableIntStateOf(0) }
    val hours = grid?.hours?.size ?: 0
    val note = grid?.hours?.getOrNull(hour)?.let { "${it.substringAfter('T').take(5)}  ◀ ▶" }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Weather map", listOfNotNull(place?.name, note).joinToString("  ·  ").ifEmpty { null })
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown && hours > 1) hour = (hour + step).coerceIn(0, hours - 1)
                    true
                }
                // OK opens the weather app; with none installed, the stage menu.
                .stageTarget(stageFocus, onDown, "Weather map", onLongPress = onHold) { if (!openWeatherApp(context)) onHold() }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile),
        ) {
            if (place == null) {
                StageHeading("Set a city in Settings → Appearance → Main Stage to show the map.")
            } else {
                WeatherMap(place, grid, hour, Modifier.fillMaxSize())
            }
        }
    }
}

/** Main Stage → Companion: the chosen companion going about its day, in the weather at [place]. */
@Composable
fun CompanionStage(
    kind: CompanionKind,
    place: Place?,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val weather by rememberGlanceWeather(place)
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading(kind.label)
        CompanionScene(
            kind = kind,
            weatherCode = weather?.now?.code,
            isDay = weather?.now?.isDay ?: true,
            modifier = Modifier
                .stageTarget(stageFocus, onDown, kind.label, onLongPress = onHold) { onHold() }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile),
        )
    }
}
