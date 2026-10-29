package dev.solon.bucklerhome

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One hour ahead: when, the WMO code, and the chance of rain or snow in percent. */
data class GlanceHour(val at: LocalDateTime, val temperatureC: Double, val code: Int, val rainChance: Int)

/** What At a Glance needs about the weather: now, the next hours, today's range and the sun. */
data class GlanceWeather(
    val now: Weather,
    val hours: List<GlanceHour>,
    val highC: Double?,
    val lowC: Double?,
    val sunrise: LocalTime?,
    val sunset: LocalTime?,
)

/** Open-Meteo again (no key): one request with the extra fields the header's weather lacks. */
object GlanceWeatherClient {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    fun fetch(place: Place): GlanceWeather? {
        val url = "$FORECAST?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&forecast_days=2&timezone=auto"
        val res = runCatching { HttpJson.get(url) }.getOrNull() ?: return null
        return if (res.code in 200..299) parse(res.body) else null
    }

    fun parse(body: String, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): GlanceWeather? {
        val root = HttpJson.parseObject(body) ?: return null
        val current = WeatherClient.parseWeather(body) ?: return null
        // Times come in the city's own zone (timezone=auto); shift them to the TV's, which the
        // rules compare them with, in case the two differ.
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
        fun local(text: String): LocalDateTime? = runCatching {
            LocalDateTime.parse(text).atOffset(offset).atZoneSameInstant(zone).toLocalDateTime()
        }.getOrNull()
        val hourly = root.optJSONObject("hourly")
        val start = now.withMinute(0).withSecond(0).withNano(0)
        val hours = buildList {
            val times = hourly?.optJSONArray("time") ?: return@buildList
            val temps = hourly.optJSONArray("temperature_2m")
            val codes = hourly.optJSONArray("weather_code")
            val rain = hourly.optJSONArray("precipitation_probability")
            for (i in 0 until times.length()) {
                val at = local(times.optString(i)) ?: continue
                if (at.isBefore(start) || size >= 24) continue
                add(
                    GlanceHour(
                        at = at,
                        temperatureC = temps?.optDouble(i) ?: Double.NaN,
                        code = codes?.optInt(i) ?: 0,
                        rainChance = rain?.optInt(i, 0) ?: 0,
                    ),
                )
            }
        }
        val daily = root.optJSONObject("daily")
        fun first(name: String): Double? = daily?.optJSONArray(name)?.optDouble(0)?.takeUnless { it.isNaN() }
        fun time(name: String): LocalTime? = daily?.optJSONArray(name)?.optString(0)?.let(::local)?.toLocalTime()
        return GlanceWeather(current, hours, first("temperature_2m_max"), first("temperature_2m_min"), time("sunrise"), time("sunset"))
    }
}
