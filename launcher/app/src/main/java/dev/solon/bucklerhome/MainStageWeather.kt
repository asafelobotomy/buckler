package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WeatherStage(
    forecast: Forecast?,
    place: Place?,
    fahrenheit: Boolean,
    use24h: Boolean,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    span: WeatherSpan,
    onHold: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .stageTarget(stageFocus, onDown, "Open weather", onLongPress = onHold) { launchWeatherApp(context) },
    ) {
        StageHeading("Weather", place?.name)
        when {
            place == null -> Text(
                text = "Set a city in Settings → Appearance → Main Stage to show the forecast.",
                color = ColorMuted,
                fontSize = 15.sp,
            )
            forecast == null -> Text(text = "…", color = ColorMuted, fontSize = 15.sp)
            else -> {
                val days = span == WeatherSpan.DAYS
                SlotRow(
                    title = if (days) "Next 10 days" else "Next hours",
                    slots = if (days) forecast.days.take(10) else forecast.hours.take(8),
                    fahrenheit = fahrenheit,
                    use24h = use24h,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SlotRow(
    title: String,
    slots: List<ForecastSlot>,
    fahrenheit: Boolean,
    use24h: Boolean,
) {
    if (slots.isEmpty()) return
    val crowded = slots.size >= 8
    val brief = slots.size >= 10
    Text(
        text = title,
        color = ColorHint,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
    ) {
        slots.forEach { slot ->
            ForecastChip(slot, fahrenheit, use24h, crowded, brief)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowScope.ForecastChip(
    slot: ForecastSlot,
    fahrenheit: Boolean,
    use24h: Boolean,
    crowded: Boolean,
    brief: Boolean,
) {
    val (glyph, summary) = WeatherClient.describe(slot.code, slot.isDay)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorTile)
            .padding(vertical = if (crowded) 6.dp else 10.dp, horizontal = 2.dp),
    ) {
        Text(text = slot.caption(use24h), color = ColorText, fontSize = if (crowded) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(text = glyph, fontSize = if (crowded) 14.sp else 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Text(
            text = temperatureLine(slot, fahrenheit, brief),
            color = ColorText,
            fontSize = if (crowded) 12.sp else 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!brief) {
            Text(
                text = summary,
                color = ColorMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun temperatureLine(slot: ForecastSlot, fahrenheit: Boolean, crowded: Boolean): String {
    val high = WeatherClient.formatTemperature(slot.temperatureC, fahrenheit)
    val low = slot.lowC ?: return high
    if (crowded) return high
    val lowNumber = WeatherClient.formatTemperature(low, fahrenheit).substringBefore('°')
    return "$lowNumber–$high"
}

private fun ForecastSlot.caption(use24h: Boolean): String {
    if (!allDay) {
        val time = runCatching { LocalTime.parse(label) }.getOrNull() ?: return label
        return DisplayFormat.time(time, use24h)
    }
    val date = runCatching { LocalDate.parse(label) }.getOrNull() ?: return label
    if (date == LocalDate.now()) return "Today"
    return date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
}
