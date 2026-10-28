package dev.solon.bucklerhome

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bad feed, weather, and article input must degrade, never throw into Home. */
class FeedSafetyTest {
    @Test
    fun outOfRangeEntityStaysAsWritten() {
        assertEquals("a &#99999999; b", decodeHtmlEntities("a &#99999999; b"))
        assertEquals("x &#x7FFFFFFF; y", decodeHtmlEntities("x &#x7FFFFFFF; y"))
        assertEquals("It's \"ok\" & <fine>", decodeHtmlEntities("It&#x27;s &quot;ok&quot; &amp; &lt;fine&gt;"))
    }

    @Test
    fun readerDecodesEntitiesInArticleText() {
        val body = "Finland&#x27;s president said the aim was to &quot;convey messages&quot; directly. "
        val html = "<article><p>${body.repeat(3)}</p></article>"
        val text = ArticleReader.extract(html).joinToString(" ")
        assertTrue(text, "Finland's president" in text)
        assertTrue(text, "\"convey messages\"" in text)
        assertTrue(text, "&quot;" !in text && "&#x27;" !in text)
    }

    @Test
    fun nullWeatherReadingsAreSkipped() {
        assertNull(WeatherClient.parseWeather("""{"current":{"temperature_2m":null,"weather_code":3}}"""))
        val body = """
            {"hourly":{"time":["2026-10-26T10:00","2026-10-26T11:00"],
              "temperature_2m":[null,12.5],"weather_code":[3,3],"is_day":[1,1]},
             "daily":{"time":["2026-10-26","2026-10-27"],"weather_code":[3,3],
              "temperature_2m_max":[null,14.0],"temperature_2m_min":[8.0,null]}}
        """.trimIndent()
        val forecast = WeatherClient.parseForecast(body, 8, 5, LocalDateTime.of(2026, 10, 26, 9, 15))
        assertEquals(listOf("11:00"), forecast.hours.map { it.label })
        assertEquals(1, forecast.days.size)
        assertNull(forecast.days.single().lowC)
        assertEquals("–°", WeatherClient.formatTemperature(Double.NaN, false))
    }
}
