package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The map drawn into a bitmap of [width] x [height] pixels, for the screensaver (plain views, no
 * composition). Blocking: reads the coastlines from disk, so call it off the main thread.
 */
fun weatherMapBitmap(
    context: Context,
    place: Place,
    grid: WeatherGrid,
    hour: Int,
    style: WeatherMapStyle,
    palette: Palette,
    fahrenheit: Boolean,
    width: Int,
    height: Int,
): Bitmap {
    val density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
    val size = Size(width.toFloat(), height.toFloat())
    val shown = hour.coerceIn(0, (grid.hours.size - 1).coerceAtLeast(0))
    val colours = MapColours.of(palette.tile, palette.text, palette.muted)
    val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
    val draw = MapCanvas(density, size).buildMap(
        place, grid, MapLayers.build(grid, shown), Coastlines.rings(context.applicationContext), colours, measurer,
        style, fahrenheit, shown,
    )
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size, draw)
    return image.asAndroidBitmap()
}

/**
 * Temperature as a colour wash over land and sea, the coast outlined on top, and the reading
 * written on an even lattice, so the warm and cold air reads at a glance.
 */
internal fun MapCanvas.heatMap(
    view: MapProjection,
    land: Path,
    temperature: ScalarField,
    place: Place,
    colours: MapColours,
    measurer: TextMeasurer,
    fahrenheit: Boolean,
): DrawScope.() -> Unit {
    val cell = 6.dp.toPx()
    val heat = heatImage(view, temperature, cell)
    val placeAt = view.point(place.lat, place.lon)
    val label = TextStyle(
        color = Color.White,
        fontSize = if (size.height < 240.dp.toPx()) 11.sp else 15.sp,
        fontWeight = FontWeight.Bold,
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f),
    )
    val spacing = (size.height / 3f).coerceIn(64.dp.toPx(), 150.dp.toPx())
    val cols = (size.width / spacing).toInt().coerceAtLeast(1)
    val rows = (size.height / spacing).toInt().coerceAtLeast(1)
    val stepX = size.width / cols
    val stepY = size.height / rows
    val readings = buildList {
        for (r in 0 until rows) for (c in 0 until cols) {
            val at = Offset(stepX * (c + 0.5f), stepY * (r + 0.5f))
            if ((at - placeAt).getDistance() < spacing * 0.4f) continue
            val t = temperature.sample(view.rowAt(at.y), view.colAt(at.x))
            if (t.isNaN()) continue
            val layout = measurer.measure(WeatherClient.formatTemperature(t.toDouble(), fahrenheit).dropLast(1), label)
            add(Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f) to layout)
        }
    }
    val hair = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round)
    return {
        drawRect(colours.sea)
        drawPath(land, colours.land)
        if (heat != null) {
            val dst = IntSize((heat.width * cell).roundToInt(), (heat.height * cell).roundToInt())
            drawImage(heat, dstSize = dst, alpha = 0.8f, filterQuality = FilterQuality.Low)
        }
        drawPath(land, Color.Black.copy(alpha = 0.45f), style = hair)
        readings.forEach { (at, layout) -> drawText(layout, topLeft = at) }
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = placeAt)
        drawCircle(Color.White, radius = 4.5.dp.toPx(), center = placeAt)
    }
}

/** Colour stops of the heat map, °C to colour, cold purple through mild yellow to hot red. */
private val HEAT_STOPS = listOf(
    -25f to Color(0xFF4B2C8F),
    -10f to Color(0xFF3A6FD8),
    0f to Color(0xFF6FC3E8),
    8f to Color(0xFF8FD6A0),
    15f to Color(0xFFF2E27A),
    22f to Color(0xFFF6A94F),
    30f to Color(0xFFE8573B),
    40f to Color(0xFF9E1B32),
)

/** The heat map's colour for [celsius]. */
fun heatColour(celsius: Float): Color {
    if (celsius <= HEAT_STOPS.first().first) return HEAT_STOPS.first().second
    for (i in 1 until HEAT_STOPS.size) {
        val (t1, c1) = HEAT_STOPS[i]
        if (celsius <= t1) {
            val (t0, c0) = HEAT_STOPS[i - 1]
            return lerp(c0, c1, (celsius - t0) / (t1 - t0))
        }
    }
    return HEAT_STOPS.last().second
}

/** Temperature as a coarse colour image, one pixel per [cell], drawn stretched and filtered like the rain. */
private fun heatImage(view: MapProjection, temperature: ScalarField, cell: Float): ImageBitmap? {
    if (temperature.values.all { it.isNaN() }) return null
    val w = ceil(view.size.width / cell).toInt().coerceAtLeast(1)
    val h = ceil(view.size.height / cell).toInt().coerceAtLeast(1)
    val pixels = IntArray(w * h) { i ->
        val t = temperature.sample(view.rowAt((i / w + 0.5f) * cell), view.colAt((i % w + 0.5f) * cell))
        if (t.isNaN()) 0 else heatColour(t).toArgb()
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}
