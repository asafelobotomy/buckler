package dev.solon.bucklerhome

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credit line the map must show: Open-Meteo asks for attribution; Natural Earth gets it as thanks. */
const val WEATHER_MAP_CREDIT = "Weather: Open-Meteo · Map: Natural Earth"

private val RainBlue = Color(0xFF4F9DFF)

/** The theme colours the map draws with, read once per composition. */
private class MapColours(
    val sea: Color,
    val land: Color,
    val coast: Color,
    val isobar: Color,
    val text: Color,
    val high: Color,
    val low: Color,
)

/**
 * A synoptic-style map around [place] for hour [hour] of [grid]: land, rain shading, isobars every
 * 4 hPa with H and L centres, wind arrows, and a dot on the place. [grid] comes from
 * [WeatherMapClient.fetch], which the caller runs off the main thread; while it is null the map
 * says it is loading. Works at any size, and is tuned for a wide, short Main Stage card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WeatherMap(place: Place, grid: WeatherGrid?, hour: Int, modifier: Modifier = Modifier) {
    if (grid == null || grid.hours.isEmpty() || grid.rows < 2 || grid.cols < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(text = "Loading the map…", color = ColorMuted, fontSize = 15.sp)
        }
        return
    }
    val context = LocalContext.current
    val coast by produceState(emptyList<CoastRing>()) {
        value = withContext(Dispatchers.IO) { Coastlines.rings(context.applicationContext) }
    }
    val shown = hour.coerceIn(0, grid.hours.size - 1)
    val layers = remember(grid, shown) { MapLayers.build(grid, shown) }
    val sea = ColorTile
    val colours = MapColours(
        sea = sea,
        land = lerp(sea, ColorText, 0.10f),
        coast = lerp(sea, ColorText, 0.24f),
        isobar = ColorMuted.copy(alpha = 0.85f),
        text = ColorText,
        // The weather-map convention (blue H, red L) reads on every scheme, even where the
        // theme's brand and section colours are the same.
        high = Color(0xFF4F8EF7),
        low = Color(0xFFE5534B),
    )
    val measurer = rememberTextMeasurer()
    Box(modifier.clipToBounds()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache { buildMap(place, grid, layers, coast, colours, measurer) },
        )
        Text(
            text = WEATHER_MAP_CREDIT,
            color = ColorHint,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Projects every layer for the current size once; redraws reuse the result. */
private fun CacheDrawScope.buildMap(
    place: Place,
    grid: WeatherGrid,
    layers: MapLayers,
    coast: List<CoastRing>,
    colours: MapColours,
    measurer: TextMeasurer,
): DrawResult {
    val view = MapProjection(grid, size)
    val land = landPath(view, Coastlines.visible(coast, view.south, view.north, view.west, view.east))
    val rainCell = 8.dp.toPx()
    val rain = rainImage(view, layers.rain, rainCell)
    val isobars = layers.isobars.map { (level, lines) ->
        level to lines.map { line ->
            List(line.size) { view.gridPoint(line.ys[it] / layers.upsample, line.xs[it] / layers.upsample) }
        }
    }
    val isobarPath = Path().apply { isobars.forEach { (_, lines) -> lines.forEach { smoothLine(this, it) } } }
    val placeAt = view.point(place.lat, place.lon)
    val taken = ArrayList<Rect>()
    taken += Rect(placeAt, 14.dp.toPx())
    // Keep arrows and labels off the credit line in the bottom-right corner.
    taken += Rect(size.width - 230.dp.toPx(), size.height - 20.dp.toPx(), size.width, size.height)
    val centres = centreLabels(view, layers.extrema, colours, measurer, taken)
    val labels = isobarLabels(isobars, colours, measurer, taken)
    val arrows = windArrows(view, layers, taken)
    // Isobars break around their labels, which reads cleanly over land and sea alike.
    val labelGaps = Path().apply {
        labels.forEach { (at, layout) -> addRect(Rect(at, Size(layout.size.width.toFloat(), layout.size.height.toFloat())).inflate(3f)) }
    }
    val thin = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val hair = Stroke(width = 0.75.dp.toPx(), join = StrokeJoin.Round)
    val arrowWidth = 1.2.dp.toPx()
    val head = 3.5.dp.toPx()
    return onDrawBehind {
        drawRect(colours.sea)
        drawPath(land, colours.land)
        drawPath(land, colours.coast, style = hair)
        if (rain != null) {
            // Each pixel stands for one cell, so stretch by the cell, not to the canvas.
            val dst = IntSize((rain.width * rainCell).roundToInt(), (rain.height * rainCell).roundToInt())
            drawImage(rain, dstSize = dst, filterQuality = FilterQuality.Low)
        }
        clipPath(labelGaps, ClipOp.Difference) { drawPath(isobarPath, colours.isobar, style = thin) }
        arrows.forEach { (from, to) -> drawArrow(from, to, colours.text.copy(alpha = 0.6f), arrowWidth, head) }
        labels.forEach { (at, layout) -> drawText(layout, topLeft = at) }
        centres.forEach { (at, layout) -> drawText(layout, topLeft = at) }
        drawCircle(colours.high.copy(alpha = 0.3f), radius = 9.dp.toPx(), center = placeAt)
        drawCircle(colours.sea, radius = 5.5.dp.toPx(), center = placeAt)
        drawCircle(colours.text, radius = 4.dp.toPx(), center = placeAt)
    }
}

/**
 * All visible rings in one even-odd path: a lake ring inside a continent cuts a hole, an island in
 * that lake fills again, without the file having to say which ring is which.
 */
private fun landPath(view: MapProjection, rings: List<VisibleRing>): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    for (ring in rings) {
        moveTo(view.x(ring.lons[0].toDouble()), view.y(ring.lats[0].toDouble()))
        for (i in 1 until ring.lats.size) lineTo(view.x(ring.lons[i].toDouble()), view.y(ring.lats[i].toDouble()))
        close()
    }
}

/**
 * Rain as a coarse alpha image, one pixel per [cell] of screen, sampled bilinearly from the grid.
 * Drawn stretched with filtering, its pixels blend into a soft wash with no visible blocks.
 */
private fun rainImage(view: MapProjection, rain: ScalarField, cell: Float): ImageBitmap? {
    if (rain.values.all { it < MIN_RAIN_MM }) return null
    val w = ceil(view.size.width / cell).toInt().coerceAtLeast(1)
    val h = ceil(view.size.height / cell).toInt().coerceAtLeast(1)
    val pixels = IntArray(w * h) { i ->
        val mm = rain.sample(view.rowAt((i / w + 0.5f) * cell), view.colAt((i % w + 0.5f) * cell))
        // Square root so drizzle shows, yet a downpour stays see-through over the isobars.
        val alpha = if (mm < MIN_RAIN_MM) 0f else 0.5f * sqrt((mm / 4f).coerceAtMost(1f))
        RainBlue.copy(alpha = alpha).toArgb()
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/** Less than this in an hour is not worth shading. */
private const val MIN_RAIN_MM = 0.05f

/** Adds [points] to [path], rounding each corner with a quadratic curve through the midpoints. */
private fun smoothLine(path: Path, points: List<Offset>) {
    if (points.size < 2) return
    val closed = points.size > 3 && points.first() == points.last()
    val pts = if (closed) points.dropLast(1) else points
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    if (closed) {
        val start = mid(pts.last(), pts.first())
        path.moveTo(start.x, start.y)
        for (i in pts.indices) {
            val m = mid(pts[i], pts[(i + 1) % pts.size])
            path.quadraticTo(pts[i].x, pts[i].y, m.x, m.y)
        }
        path.close()
        return
    }
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size - 1) {
        val m = mid(pts[i], pts[i + 1])
        path.quadraticTo(pts[i].x, pts[i].y, m.x, m.y)
    }
    path.lineTo(pts.last().x, pts.last().y)
}

private fun Rect.overlapsAny(others: List<Rect>) = others.any { it.overlaps(this) }

private fun CacheDrawScope.insideMargins(r: Rect, margin: Float) =
    r.left >= margin && r.top >= margin && r.right <= size.width - margin && r.bottom <= size.height - margin

/** "H" or "L" with the centre's pressure underneath, for each centre on screen. */
private fun CacheDrawScope.centreLabels(
    view: MapProjection,
    extrema: List<Extremum>,
    colours: MapColours,
    measurer: TextMeasurer,
    taken: MutableList<Rect>,
): List<Pair<Offset, TextLayoutResult>> {
    val letterSize = if (size.height < 240.dp.toPx()) 18.sp else 22.sp
    val out = ArrayList<Pair<Offset, TextLayoutResult>>()
    for (e in extrema) {
        val centre = view.gridPoint(e.y, e.x)
        val letter = measurer.measure(
            if (e.high) "H" else "L",
            TextStyle(color = if (e.high) colours.high else colours.low, fontSize = letterSize, fontWeight = FontWeight.Bold),
        )
        val value = measurer.measure("${e.value.roundToInt()}", TextStyle(color = colours.text, fontSize = 10.sp))
        val height = letter.size.height + value.size.height - 2f
        val width = maxOf(letter.size.width, value.size.width).toFloat()
        // Centred on the centre when there is room, else just above or below it (the place dot
        // often sits right on a centre, since the grid is centred on the place).
        val box = listOf(0f, -height * 0.75f, height * 0.75f)
            .map { Rect(Offset(centre.x - width / 2, centre.y - height / 2 + it), Size(width, height)) }
            .firstOrNull { insideMargins(it, 2f) && !it.overlapsAny(taken) } ?: continue
        taken += box
        val letterAt = Offset(box.center.x - letter.size.width / 2f, box.top)
        val valueAt = Offset(box.center.x - value.size.width / 2f, box.top + letter.size.height - 2f)
        out += letterAt to letter
        out += valueAt to value
    }
    return out
}

/**
 * A pressure label on a few isobars: at the middle of each level's longest line, when the line is
 * long enough to carry it and the label clears the edges and everything placed before it.
 */
private fun CacheDrawScope.isobarLabels(
    isobars: List<Pair<Float, List<List<Offset>>>>,
    colours: MapColours,
    measurer: TextMeasurer,
    taken: MutableList<Rect>,
): List<Pair<Offset, TextLayoutResult>> {
    val style = TextStyle(color = colours.isobar, fontSize = 10.sp)
    val minLength = 160.dp.toPx()
    val out = ArrayList<Pair<Offset, TextLayoutResult>>()
    for ((level, lines) in isobars) {
        if (out.size >= MAX_ISOBAR_LABELS) break
        val (line, length) = lines.map { it to onScreenLength(it) }.maxByOrNull { it.second } ?: continue
        if (length < minLength) continue
        val at = pointAlong(line, length / 2) ?: continue
        val layout = measurer.measure("${level.roundToInt()}", style)
        val topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
        val box = Rect(topLeft, Size(layout.size.width.toFloat(), layout.size.height.toFloat())).inflate(4f)
        if (!insideMargins(box, 4.dp.toPx()) || box.overlapsAny(taken)) continue
        taken += box
        out += topLeft to layout
    }
    return out
}

private const val MAX_ISOBAR_LABELS = 4

private fun CacheDrawScope.onScreen(p: Offset) = p.x in 0f..size.width && p.y in 0f..size.height

/** Length of the parts of [line] whose both ends are on the canvas. */
private fun CacheDrawScope.onScreenLength(line: List<Offset>): Float =
    (1 until line.size).sumOf { i ->
        if (onScreen(line[i - 1]) && onScreen(line[i])) (line[i] - line[i - 1]).getDistance().toDouble() else 0.0
    }.toFloat()

/** The point [distance] along the on-canvas parts of [line]. */
private fun CacheDrawScope.pointAlong(line: List<Offset>, distance: Float): Offset? {
    var left = distance
    for (i in 1 until line.size) {
        if (!onScreen(line[i - 1]) || !onScreen(line[i])) continue
        val step = (line[i] - line[i - 1]).getDistance()
        if (step >= left && step > 0f) return line[i - 1] + (line[i] - line[i - 1]) * (left / step)
        left -= step
    }
    return null
}

/**
 * Wind arrows on an even screen lattice, so a wide card gets more of them rather than stretched
 * ones. Each is centred on its lattice point, points where the air is going, and grows with speed.
 * Arrows that would sit on a label or the place dot are left out.
 */
private fun CacheDrawScope.windArrows(view: MapProjection, layers: MapLayers, taken: List<Rect>): List<Pair<Offset, Offset>> {
    val spacing = (size.height / 3f).coerceIn(52.dp.toPx(), 110.dp.toPx())
    val cols = (size.width / spacing).toInt().coerceAtLeast(1)
    val rows = (size.height / spacing).toInt().coerceAtLeast(1)
    val stepX = size.width / cols
    val stepY = size.height / rows
    val perKmh = 0.4.dp.toPx()
    val shortest = 7.dp.toPx()
    val longest = spacing * 0.55f
    return buildList {
        for (r in 0 until rows) for (c in 0 until cols) {
            val at = Offset(stepX * (c + 0.5f), stepY * (r + 0.5f))
            if (taken.any { it.inflate(6.dp.toPx()).contains(at) }) continue
            val row = view.rowAt(at.y)
            val col = view.colAt(at.x)
            val east = layers.windEast.sample(row, col)
            val north = layers.windNorth.sample(row, col)
            val speed = hypot(east, north)
            if (speed < 1f) continue
            val length = (shortest + speed * perKmh).coerceAtMost(longest)
            val dir = Offset(east / speed, -north / speed) * (length / 2)
            add(at - dir to at + dir)
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, colour: Color, width: Float, head: Float) {
    drawLine(colour, from, to, strokeWidth = width, cap = StrokeCap.Round)
    val d = to - from
    val unit = d / d.getDistance()
    // Two barbs swept back about 30 degrees from the shaft.
    val back = unit * (-head * 0.87f)
    val side = Offset(-unit.y, unit.x) * (head * 0.5f)
    drawLine(colour, to, to + back + side, strokeWidth = width, cap = StrokeCap.Round)
    drawLine(colour, to, to + back - side, strokeWidth = width, cap = StrokeCap.Round)
}
