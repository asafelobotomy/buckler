package dev.solon.bucklerhome

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

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
