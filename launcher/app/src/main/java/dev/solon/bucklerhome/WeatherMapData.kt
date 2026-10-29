package dev.solon.bucklerhome

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hourly forecast fields on a regular latitude/longitude grid around a place.
 *
 * Row r is latitude [lats]`[r]` (south to north), column c is longitude [lons]`[c]` (west to east;
 * may run past ±180 so the grid stays continuous across the antimeridian). Each field is indexed
 * `field[hour][r * cols + c]`; [hours] holds the local "yyyy-MM-ddTHH:mm" stamp of each hour.
 * Pressure is mean sea-level in hPa, wind speed in km/h, wind direction in degrees the wind
 * blows *from*, rain in mm over the hour. A missing reading is NaN.
 */
class WeatherGrid(
    val lats: List<Double>,
    val lons: List<Double>,
    val hours: List<String>,
    val pressure: Array<FloatArray>,
    val windSpeed: Array<FloatArray>,
    val windDir: Array<FloatArray>,
    val rain: Array<FloatArray>,
) {
    val rows: Int get() = lats.size
    val cols: Int get() = lons.size

    /** The pressure of [hour] as a contourable field. */
    fun pressureField(hour: Int): ScalarField = ScalarField(rows, cols, pressure[hour])

    /**
     * Wind of [hour] as (east, north) component fields in km/h. Speed and direction are turned
     * into components before anyone interpolates, so a point between a north and a south wind
     * comes out calm instead of pointing east.
     */
    fun windFields(hour: Int): Pair<ScalarField, ScalarField> {
        val speed = windSpeed[hour]
        val dir = windDir[hour]
        // Direction is where the wind comes from, so the motion vector points the other way.
        val east = FloatArray(speed.size) { -speed[it] * sin(Math.toRadians(dir[it].toDouble())).toFloat() }
        val north = FloatArray(speed.size) { -speed[it] * cos(Math.toRadians(dir[it].toDouble())).toFloat() }
        return ScalarField(rows, cols, east) to ScalarField(rows, cols, north)
    }
}

/**
 * Fetches the [WeatherGrid] for the weather map from Open-Meteo in one multi-point request: no API
 * key and no account. Results are kept for [CACHE_MS] per place, since the hourly model output
 * changes far less often than the map is shown.
 */
object WeatherMapClient {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val CACHE_MS = 30 * 60 * 1000L
    private const val HOURS = 24

    /**
     * Grid shape: 13 x 7 = 91 points, one request. More columns than rows because the box is wider
     * than tall, which keeps the spacing on the ground roughly even (about 300 km either way).
     */
    const val COLS = 13
    const val ROWS = 7

    /** Half the latitude span, in degrees; longitude is wider so the box suits a wide card. */
    const val HALF_LAT = 8.0
    private const val LON_PER_LAT = 2.5

    private var cachedKey: String? = null
    private var cachedAt = 0L
    private var cachedGrid: WeatherGrid? = null

    /** Blocking: call off the main thread. Returns null when the network or the reply fails. */
    fun fetch(place: Place): WeatherGrid? {
        val key = "${place.lat},${place.lon}"
        synchronized(this) {
            val grid = cachedGrid
            if (grid != null && cachedKey == key && System.currentTimeMillis() - cachedAt < CACHE_MS) return grid
        }
        val (lats, lons) = gridFor(place)
        val pointLats = ArrayList<Double>()
        val pointLons = ArrayList<Double>()
        for (lat in lats) for (lon in lons) {
            pointLats += lat
            pointLons += wrapLon(lon)
        }
        val url = "$FORECAST?latitude=${pointLats.joinToString(",") { "%.2f".format(Locale.ROOT, it) }}" +
            "&longitude=${pointLons.joinToString(",") { "%.2f".format(Locale.ROOT, it) }}" +
            "&hourly=pressure_msl,wind_speed_10m,wind_direction_10m,precipitation" +
            "&forecast_hours=$HOURS&timezone=auto"
        val res = runCatching { HttpJson.get(url) }.getOrNull() ?: return null
        if (res.code !in 200..299) return null
        val grid = parseGrid(res.body, lats, lons) ?: return null
        synchronized(this) {
            cachedKey = key
            cachedAt = System.currentTimeMillis()
            cachedGrid = grid
        }
        return grid
    }

    /**
     * Grid latitudes (south to north) and longitudes (west to east) centred on [place]. The
     * longitude span widens by 1/cos(latitude) so the box keeps its shape on the ground; latitude is
     * kept off the poles, where that factor runs away.
     */
    fun gridFor(place: Place): Pair<List<Double>, List<Double>> {
        val centreLat = place.lat.coerceIn(-80.0, 80.0)
        val halfLon = (HALF_LAT * LON_PER_LAT / cos(Math.toRadians(centreLat))).coerceAtMost(170.0)
        val lats = (0 until ROWS).map { r ->
            (centreLat - HALF_LAT + 2 * HALF_LAT * r / (ROWS - 1)).coerceIn(-89.9, 89.9)
        }
        val lons = (0 until COLS).map { c -> place.lon - halfLon + 2 * halfLon * c / (COLS - 1) }
        return lats to lons
    }

    private fun wrapLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0

    /**
     * Reads Open-Meteo's multi-point reply: a JSON array with one object per point, in request
     * order (row by row, south to north, each row west to east). Returns null unless it is an
     * array of exactly [lats] x [lons] points with hourly data; an error reply is a JSON object.
     */
    fun parseGrid(body: String, lats: List<Double>, lons: List<Double>): WeatherGrid? {
        if (!body.trimStart().startsWith("[")) return null
        val points = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val count = lats.size * lons.size
        if (count == 0 || points.length() != count) return null
        val hourly = (0 until count).map { points.optJSONObject(it)?.optJSONObject("hourly") ?: return null }
        // forecast_hours counts from the current hour at every point, so index i is the same
        // instant everywhere; take the labels from the middle point, which is the user's place.
        val times = hourly[count / 2].optJSONArray("time") ?: return null
        val hourCount = times.length()
        if (hourCount == 0) return null
        val hours = (0 until hourCount).map { times.optString(it) }
        fun field(name: String, missing: Float) = Array(hourCount) { h ->
            FloatArray(count) { p -> reading(hourly[p], name, h, missing) }
        }
        return WeatherGrid(
            lats = lats,
            lons = lons,
            hours = hours,
            pressure = field("pressure_msl", Float.NaN),
            windSpeed = field("wind_speed_10m", 0f),
            windDir = field("wind_direction_10m", 0f),
            // No reading means nothing to shade, so rain defaults to dry rather than a gap.
            rain = field("precipitation", 0f),
        )
    }

    private fun reading(hourly: JSONObject, name: String, hour: Int, missing: Float): Float {
        val values = hourly.optJSONArray(name) ?: return missing
        if (hour >= values.length() || values.isNull(hour)) return missing
        val v = values.optDouble(hour)
        return if (v.isNaN()) missing else v.toFloat()
    }
}
