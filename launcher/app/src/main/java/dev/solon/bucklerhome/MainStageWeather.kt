package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Weather", place?.name)
        when {
            place == null -> Text(
                text = "Set a city in Settings → Header to show the forecast.",
                color = ColorMuted,
                fontSize = 15.sp,
            )
            forecast == null -> Text(text = "…", color = ColorMuted, fontSize = 15.sp)
            else -> {
                SlotRow("Next hours", forecast.hours, fahrenheit, use24h)
                SlotRow("Next days", forecast.days, fahrenheit, use24h)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SlotRow(title: String, slots: List<ForecastSlot>, fahrenheit: Boolean, use24h: Boolean) {
    if (slots.isEmpty()) return
    Text(
        text = title,
        color = ColorHint,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        items(slots, key = { "${it.allDay}-${it.label}" }) { slot ->
            ForecastChip(slot, fahrenheit, use24h)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ForecastChip(slot: ForecastSlot, fahrenheit: Boolean, use24h: Boolean) {
    val (glyph, summary) = WeatherClient.describe(slot.code, slot.isDay)
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorTile)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(text = slot.caption(use24h), color = ColorText, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(text = glyph, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Text(
            text = temperatureLine(slot, fahrenheit),
            color = ColorText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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

private fun temperatureLine(slot: ForecastSlot, fahrenheit: Boolean): String {
    val high = WeatherClient.formatTemperature(slot.temperatureC, fahrenheit)
    val low = slot.lowC ?: return high
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
