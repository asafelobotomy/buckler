package dev.solon.bucklerhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.cos
import kotlin.math.max

/**
 * Equirectangular projection for the weather map: longitude scaled by cos(latitude) at the centre
 * so shapes keep their proportions on the ground. The scale makes the data grid *cover* the
 * canvas, so a wide, short card shows a band of the grid and a square one shows all of its
 * height, and no corner ever lies outside the data.
 */
class MapProjection(grid: WeatherGrid, val size: Size) {
    private val lat0 = (grid.lats.first() + grid.lats.last()) / 2
    private val lon0 = (grid.lons.first() + grid.lons.last()) / 2
    private val shrink = cos(Math.toRadians(lat0))
    private val firstLat = grid.lats.first()
    private val firstLon = grid.lons.first()
    private val latStep = (grid.lats.last() - firstLat) / (grid.rows - 1)
    private val lonStep = (grid.lons.last() - firstLon) / (grid.cols - 1)

    /** Pixels per degree of latitude. */
    val scale: Double = max(
        size.width / ((grid.lons.last() - firstLon) * shrink),
        size.height / (grid.lats.last() - firstLat),
    )

    fun x(lon: Double): Float = (size.width / 2 + (lon - lon0) * shrink * scale).toFloat()

    fun y(lat: Double): Float = (size.height / 2 - (lat - lat0) * scale).toFloat()

    /** Screen position of the place at [lat], [lon], with [lon] taken to the turn nearest the centre. */
    fun point(lat: Double, lon: Double): Offset {
        var l = lon
        while (l - lon0 > 180) l -= 360
        while (lon0 - l > 180) l += 360
        return Offset(x(l), y(lat))
    }

    /** Screen position of fractional grid position ([row], [col]). */
    fun gridPoint(row: Float, col: Float): Offset = Offset(x(firstLon + col * lonStep), y(firstLat + row * latStep))

    /** Fractional grid row of screen [y]. */
    fun rowAt(y: Float): Float = ((lat0 + (size.height / 2 - y) / scale - firstLat) / latStep).toFloat()

    /** Fractional grid column of screen [x]. */
    fun colAt(x: Float): Float = ((lon0 + (x - size.width / 2) / (shrink * scale) - firstLon) / lonStep).toFloat()

    val south: Double get() = lat0 - size.height / 2 / scale
    val north: Double get() = lat0 + size.height / 2 / scale
    val west: Double get() = lon0 - size.width / 2 / (shrink * scale)
    val east: Double get() = lon0 + size.width / 2 / (shrink * scale)
}

/** One hour of the map worked out in grid space, independent of the canvas size. */
class MapLayers(
    /** Contour levels in hPa, each with its lines in upsampled grid coordinates. */
    val isobars: List<Pair<Float, List<Polyline>>>,
    val upsample: Int,
    val extrema: List<Extremum>,
    val windEast: ScalarField,
    val windNorth: ScalarField,
    val rain: ScalarField,
) {
    companion object {
        /** Upsampling between grid samples; 4 rounds the contours without much work. */
        private const val UPSAMPLE = 4

        fun build(grid: WeatherGrid, hour: Int): MapLayers {
            val pressure = grid.pressureField(hour)
            val fine = pressure.upsample(UPSAMPLE)
            val (east, north) = grid.windFields(hour)
            return MapLayers(
                isobars = Isobars.levels(fine).map { it to Isobars.contour(fine, it) },
                upsample = UPSAMPLE,
                extrema = Isobars.extrema(pressure),
                windEast = east,
                windNorth = north,
                rain = ScalarField(grid.rows, grid.cols, grid.rain[hour]),
            )
        }
    }
}
