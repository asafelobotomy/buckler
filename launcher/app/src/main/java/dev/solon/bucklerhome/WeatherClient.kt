package dev.solon.bucklerhome

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import kotlin.math.roundToInt
import org.json.JSONObject

/** One hour or one day in a forecast strip. [label] is "23:00" or "2026-10-27". */
data class ForecastSlot(
    val label: String,
    val temperatureC: Double,
    val code: Int,
    val isDay: Boolean = true,
    val lowC: Double? = null,
    val allDay: Boolean = false,
)

data class Forecast(val hours: List<ForecastSlot> = emptyList(), val days: List<ForecastSlot> = emptyList())

data class Place(val name: String, val lat: Double, val lon: Double)

data class Weather(val temperatureC: Double, val code: Int, val isDay: Boolean)

/** Open-Meteo: free, open-source, no API key and no account. */
object WeatherClient {
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    fun search(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        if (q.isEmpty()) return emptyList()
        val res = runCatching { HttpJson.get("$GEOCODE?name=$q&count=6&language=en&format=json") }
            .getOrNull() ?: return emptyList()
        return if (res.code in 200..299) parsePlaces(res.body) else emptyList()
    }

    fun fetch(place: Place): Weather? {
        val url = "$FORECAST?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day&timezone=auto"
        val res = runCatching { HttpJson.get(url) }.getOrNull() ?: return null
        return if (res.code in 200..299) parseWeather(res.body) else null
    }

    /** Next [hourCount] hours and [dayCount] days, for the Main Stage weather strip. */
    fun fetchForecast(place: Place, hourCount: Int = 8, dayCount: Int = 10): Forecast? {
        val url = "$FORECAST?latitude=${place.lat}&longitude=${place.lon}" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=${dayCount.coerceAtLeast(1)}&timezone=auto"
        val res = runCatching { HttpJson.get(url) }.getOrNull() ?: return null
        return if (res.code in 200..299) parseForecast(res.body, hourCount, dayCount) else null
    }

    fun parsePlaces(body: String): List<Place> {
        val results = HttpJson.parseObject(body)?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("latitude") || !o.has("longitude")) return@mapNotNull null
            val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code"))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            Place(label, o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }

    fun parseWeather(body: String): Weather? {
        val current = HttpJson.parseObject(body)?.optJSONObject("current") ?: return null
        if (!current.has("temperature_2m") || !current.has("weather_code")) return null
        return Weather(
            temperatureC = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
        )
    }

    fun parseForecast(
        body: String,
        hourCount: Int = 8,
        dayCount: Int = 5,
        now: LocalDateTime = LocalDateTime.now(),
    ): Forecast {
        val root = HttpJson.parseObject(body) ?: return Forecast()
        return Forecast(
            hours = readHours(root.optJSONObject("hourly"), hourCount, now),
            days = readDays(root.optJSONObject("daily"), dayCount),
        )
    }

    private fun readHours(hourly: JSONObject?, count: Int, now: LocalDateTime): List<ForecastSlot> {
        if (hourly == null || count <= 0) return emptyList()
        val times = hourly.optJSONArray("time") ?: return emptyList()
        val temps = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
        val day = hourly.optJSONArray("is_day")
        val start = now.withMinute(0).withSecond(0).withNano(0)
        return buildList {
            for (i in 0 until times.length()) {
                if (size >= count) break
                val stamp = times.optString(i)
                val whenAt = runCatching { LocalDateTime.parse(stamp) }.getOrNull() ?: continue
                if (whenAt.isBefore(start)) continue
                if (i >= temps.length() || i >= codes.length()) continue
                add(
                    ForecastSlot(
                        label = stamp.substringAfter('T', stamp).take(5),
                        temperatureC = temps.optDouble(i),
                        code = codes.optInt(i),
                        isDay = day?.optInt(i, 1) != 0,
                    ),
                )
            }
        }
    }

    private fun readDays(daily: JSONObject?, count: Int): List<ForecastSlot> {
        if (daily == null || count <= 0) return emptyList()
        val times = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lows = daily.optJSONArray("temperature_2m_min")
        return buildList {
            val n = minOf(count, times.length(), codes.length(), highs.length())
            for (i in 0 until n) {
                add(
                    ForecastSlot(
                        label = times.optString(i),
                        temperatureC = highs.optDouble(i),
                        lowC = lows?.optDouble(i),
                        code = codes.optInt(i),
                        allDay = true,
                    ),
                )
            }
        }
    }

    fun formatTemperature(celsius: Double, fahrenheit: Boolean): String {
        val value = if (fahrenheit) celsius * 9 / 5 + 32 else celsius
        return "${value.roundToInt()}°${if (fahrenheit) "F" else "C"}"
    }

    /** WMO weather interpretation codes → (glyph, summary). */
    fun describe(code: Int, isDay: Boolean = true): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀" else "☾") to "Clear"
        1, 2 -> (if (isDay) "🌤" else "☾") to "Partly cloudy"
        3 -> "☁" to "Overcast"
        45, 48 -> "🌫" to "Fog"
        in 51..57 -> "🌦" to "Drizzle"
        in 61..67 -> "🌧" to "Rain"
        in 71..77 -> "❄" to "Snow"
        in 80..82 -> "🌧" to "Showers"
        85, 86 -> "❄" to "Snow showers"
        in 95..99 -> "⛈" to "Thunderstorm"
        else -> "☁" to "Weather"
    }
}
