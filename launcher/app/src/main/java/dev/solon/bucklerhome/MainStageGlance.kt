package dev.solon.bucklerhome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SIDE_ROTATE_MS = 8_000L

/** The TV's locale, read so a language change recomposes the dates. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** The time, re-read every 30 seconds, so At a Glance's "in 25 min" stays right. */
@Composable
fun rememberNow(): State<ZonedDateTime> = produceState(ZonedDateTime.now()) {
    while (true) {
        delay(30_000)
        value = ZonedDateTime.now()
    }
}

/** At a Glance for [place]: its lines are empty until something is known. */
@Composable
fun rememberGlance(place: Place?, use24h: Boolean, fahrenheit: Boolean): Glance {
    val weather by rememberGlanceWeather(place)
    val agenda by rememberAgenda(days = 2)
    val now by rememberNow()
    return Glance(now, weather, GlanceRules.lines(now, agenda?.events.orEmpty(), agenda?.tasks.orEmpty(), weather, use24h, fahrenheit))
}

/** What At a Glance shows: the time it was worked out, the weather, and its lines. */
data class Glance(val now: ZonedDateTime, val weather: GlanceWeather?, val lines: List<GlanceLine>)

/**
 * Main Stage → At a Glance: the date and the weather now on the left, then what matters next
 * (an event, reminders, a change in the weather, the sun). OK opens the stage's menu.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlanceStage(
    place: Place?,
    fahrenheit: Boolean,
    use24h: Boolean,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val (now, weather, lines) = rememberGlance(place, use24h, fahrenheit)
    val tight = metrics.cardHeight < 130.dp
    val shown = lines.take(if (tight) 2 else if (metrics.cardHeight < 190.dp) 3 else 4)
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("At a Glance")
        Row(
            modifier = Modifier
                .stageTarget(stageFocus, onDown, shown.firstOrNull()?.text ?: "At a Glance", onLongPress = onHold) { onHold() }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .padding(horizontal = 24.dp, vertical = if (tight) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.widthIn(min = 220.dp).padding(end = 28.dp)) {
                Text(
                    text = now.format(DateTimeFormatter.ofPattern("EEEE", currentLocale())),
                    color = ColorSection,
                    fontSize = if (tight) 14.sp else 16.sp,
                )
                Text(
                    text = now.format(DateTimeFormatter.ofPattern("d MMMM", currentLocale())),
                    color = ColorText,
                    fontSize = if (tight) 24.sp else 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (weather != null) {
                    val (glyph, summary) = WeatherClient.describe(weather.now.code, weather.now.isDay)
                    Text(
                        text = "$glyph  ${WeatherClient.formatTemperature(weather.now.temperatureC, fahrenheit)}  ·  $summary",
                        color = ColorMuted,
                        fontSize = if (tight) 14.sp else 17.sp,
                        maxLines = 1,
                    )
                } else if (place == null) {
                    Text("Set a city for the weather", color = ColorHint, fontSize = 14.sp)
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 10.dp, Alignment.CenterVertically),
            ) {
                if (shown.isEmpty()) {
                    Text("Nothing coming up.", color = ColorMuted, fontSize = 17.sp)
                }
                shown.forEach { line -> GlanceRow(line, if (tight) 16.sp else 19.sp) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GlanceRow(line: GlanceLine, size: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(line.glyph, color = ColorBrand, fontSize = size, modifier = Modifier.width(30.dp))
        Text(line.text, color = ColorText, fontSize = size, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The Side Stage's At a Glance: one line at a time, turning over every few seconds. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlanceSide(config: DisplayConfig, modifier: Modifier = Modifier) {
    val pool = rememberGlance(config.place, config.use24h, config.fahrenheit).lines
    val index by produceState(0, pool.size) {
        while (pool.size > 1) {
            delay(SIDE_ROTATE_MS)
            value = (value + 1) % pool.size
        }
    }
    val line = pool.getOrNull(index % pool.size.coerceAtLeast(1)) ?: return
    AnimatedContent(targetState = line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "glance", modifier = modifier) {
        GlanceRow(it, 17.sp)
    }
}
