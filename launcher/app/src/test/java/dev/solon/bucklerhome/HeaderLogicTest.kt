package dev.solon.bucklerhome

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderLogicTest {
    @Test
    fun clockSupports12And24Hour() {
        val t = LocalTime.of(21, 5)
        assertEquals("21:05", DisplayFormat.time(t, true, Locale.US))
        assertEquals("9:05 PM", DisplayFormat.time(t, false, Locale.US))
    }

    @Test
    fun dateStylesRender() {
        val d = LocalDate.of(2026, 10, 26)
        assertEquals("Mon 26 Oct", DisplayFormat.date(d, DateStyle.SHORT, Locale.US))
        assertEquals("Monday, 26 October", DisplayFormat.date(d, DateStyle.LONG, Locale.US))
        assertEquals("26/10/2026", DisplayFormat.date(d, DateStyle.DAY_MONTH_YEAR, Locale.US))
        assertEquals("10/26/2026", DisplayFormat.date(d, DateStyle.MONTH_DAY_YEAR, Locale.US))
        assertEquals("2026-10-26", DisplayFormat.date(d, DateStyle.ISO, Locale.US))
        assertEquals("Oct 26, 2026", DisplayFormat.date(d, DateStyle.US_LONG, Locale.US))
    }

    @Test
    fun dateStyleCyclesAndSurvivesUnknownNames() {
        assertEquals(DateStyle.SHORT, DateStyle.entries.last().next())
        assertEquals(DateStyle.SHORT, DateStyle.fromName("nope"))
    }

    @Test
    fun newsSourceCyclesThroughOff() {
        assertEquals(NewsSource.NPR, NewsSource.BBC_WORLD.next())
        assertNull(NewsSource.entries.last().next())
    }

    @Test
    fun rssTitlesAreCleaned() {
        val xml = """
            <rss><channel><title>Feed title</title><image><title>Logo</title></image>
            <item><title><![CDATA[Fish &amp; chips <b>win</b>]]></title></item>
            <item><title>Tom &amp; Jerry&#39;s &quot;big&quot; day</title></item>
            <item><title>Tom &amp; Jerry&#39;s &quot;big&quot; day</title></item>
            <item><link>x</link></item>
            </channel></rss>
        """.trimIndent()
        val titles = NewsFeed.parseHeadlines(xml, 10)
        assertEquals(listOf("Fish & chips win", "Tom & Jerry's \"big\" day"), titles)
    }

    @Test
    fun atomEntriesAndLimitWork() {
        val xml = (1..5).joinToString("") { "<entry><title type=\"html\">T$it</title></entry>" }
        assertEquals(listOf("T1", "T2"), NewsFeed.parseHeadlines(xml, 2))
    }

    @Test
    fun weatherParsingAndFormatting() {
        val w = WeatherClient.parseWeather(
            """{"current":{"temperature_2m":18.4,"weather_code":61,"is_day":0}}""",
        )!!
        assertEquals(61, w.code)
        assertEquals(false, w.isDay)
        assertEquals("18°C", WeatherClient.formatTemperature(w.temperatureC, false))
        assertEquals("65°F", WeatherClient.formatTemperature(w.temperatureC, true))
        assertNull(WeatherClient.parseWeather("""{"current":{}}"""))
        assertNull(WeatherClient.parseWeather("not json"))
    }

    @Test
    fun geocodingResultsBecomePlaces() {
        val places = WeatherClient.parsePlaces(
            """{"results":[
              {"name":"Berlin","admin1":"State of Berlin","country_code":"DE","latitude":52.5,"longitude":13.4},
              {"name":"Broken"}]}""",
        )
        assertEquals(1, places.size)
        assertEquals("Berlin, State of Berlin, DE", places[0].name)
        assertTrue(WeatherClient.parsePlaces("{}").isEmpty())
    }

    @Test
    fun netStatePrefersEthernetAndDetectsVpn() {
        assertEquals(NetState(LinkType.NONE, false), netStateFrom(emptyList()))
        val wifi = NetInfo(wifi = true)
        val eth = NetInfo(ethernet = true)
        val vpn = NetInfo(vpn = true, wifi = true)
        assertEquals(NetState(LinkType.WIFI, false), netStateFrom(listOf(wifi)))
        assertEquals(NetState(LinkType.ETHERNET, false), netStateFrom(listOf(wifi, eth)))
        // A VPN rides on a physical link; it must not hide it or count as the link itself.
        assertEquals(NetState(LinkType.WIFI, true), netStateFrom(listOf(vpn, wifi)))
        assertEquals(NetState(LinkType.NONE, true), netStateFrom(listOf(vpn)))
    }
}
