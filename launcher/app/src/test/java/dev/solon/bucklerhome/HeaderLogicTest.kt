package dev.solon.bucklerhome

import java.time.LocalDate
import java.time.LocalDateTime
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
    fun shuffleTakesTheNextStoryFromEachSource() {
        fun group(source: NewsSource, vararg titles: String) = titles.map { Headline(source, it) }
        val mixed = NewsFeed.interleaveHeadlines(
            listOf(
                group(NewsSource.BBC_WORLD, "a1", "a2"),
                group(NewsSource.NPR, "b1"),
                group(NewsSource.GUARDIAN, "c1", "c2", "c3"),
            ),
        )
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "c3"), mixed.map { it.title })
        assertEquals(NewsSource.NPR, mixed[1].source)
        assertEquals(NewsSource.GUARDIAN, mixed[2].source)
        assertTrue(mixed.zipWithNext().take(4).all { (a, b) -> a.source != b.source })
        assertEquals(emptyList<Headline>(), NewsFeed.interleaveHeadlines(emptyList()))
    }

    @Test
    fun headerSizeTickerSpeedAndShuffleQuery() {
        assertEquals(HeaderSize.MEDIUM, HeaderSize.fromName(null))
        assertEquals(HeaderSize.LARGE, HeaderSize.fromName("LARGE"))
        assertEquals(0.8f, HeaderSize.THIN.scale)
        assertEquals(TickerSpeed.NORMAL, TickerSpeed.fromName("nope"))
        assertTrue(TickerSpeed.SLOW.velocityDp < TickerSpeed.FAST.velocityDp)
        assertTrue(TickerSpeed.SLOW.rotateMs > TickerSpeed.FAST.rotateMs)
        assertNull(DisplayConfig(newsSources = emptyList()).newsQuery())
        val mixed = DisplayConfig(newsSources = listOf(NewsSource.BBC_WORLD, NewsSource.NPR))
        assertEquals("BBC_WORLD+NPR", mixed.newsQuery()?.cacheKey)
        assertEquals(listOf(NewsSource.BBC_WORLD, NewsSource.NPR), mixed.newsQuery()?.sources)
        assertNull(DisplayConfig(newsSources = listOf(NewsSource.BBC_WORLD), headerVisible = false).newsQuery())
        val hidden = DisplayConfig(newsSources = listOf(NewsSource.NPR), headerVisible = false)
        assertEquals(NewsSource.NPR, stageNewsQuery(hidden, MainStage.NEWS)?.sources?.single())
        assertNull(stageNewsQuery(hidden, MainStage.WEATHER))
        assertNull(stageNewsQuery(DisplayConfig(newsSources = emptyList()), MainStage.NEWS))
        // Synced (default): the stage follows the ticker. Unsynced: each keeps its own.
        val synced = DisplayConfig(newsSources = listOf(NewsSource.NPR), stageNewsSources = listOf(NewsSource.POLYGON))
        assertEquals(listOf(NewsSource.NPR), synced.stageNews)
        val apart = synced.copy(newsSynced = false)
        assertEquals("POLYGON", stageNewsQuery(apart, MainStage.NEWS)?.cacheKey)
        assertEquals("NPR", apart.newsQuery()?.cacheKey)
        val dublin = Place("Dublin", 53.3, -6.2)
        val cork = Place("Cork", 51.9, -8.5)
        val weather = DisplayConfig(place = dublin, stagePlace = cork, fahrenheit = false, stageFahrenheit = true)
        assertEquals(dublin, weather.stageWeatherPlace)
        assertEquals(cork, weather.copy(weatherSynced = false).stageWeatherPlace)
        assertTrue(weather.copy(weatherSynced = false).stageWeatherFahrenheit)
        assertEquals("BBC", NewsSource.BBC_WORLD.mark)
        assertTrue(NewsSource.entries.none { it.name == "HACKER_NEWS" })
        assertTrue(NewsSource.entries.any { it.category == NewsCategory.GAMING })
        assertTrue(NewsSource.entries.any { it.category == NewsCategory.BUSINESS })
        assertTrue(NewsSource.entries.any { it.category == NewsCategory.TECH })
        val uk = sourcesForRegion(NewsRegion.UK, listOf(NewsSource.POLYGON))
        assertTrue(uk.contains(NewsSource.BBC_UK))
        assertTrue(uk.contains(NewsSource.POLYGON))
        assertTrue(uk.none { it == NewsSource.NPR })
        assertTrue(sourcesForRegion(NewsRegion.US, emptyList()).all { NewsRegion.US in it.regions })
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
        val titles = NewsFeed.parseStories(xml, 10).map { it.title }
        assertEquals(listOf("Fish & chips win", "Tom & Jerry's \"big\" day"), titles)
    }

    @Test
    fun atomEntriesAndLimitWork() {
        val xml = (1..5).joinToString("") { "<entry><title type=\"html\">T$it</title></entry>" }
        assertEquals(listOf("T1", "T2"), NewsFeed.parseStories(xml, 2).map { it.title })
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

    @Test
    fun vpnTraceCountryCodes() {
        val body = "ip=1.2.3.4\nts=1\nloc=IE\ncolo=DUB\n"
        assertEquals("IE", VpnCountry.parseTrace(body))
        assertEquals("UK", VpnCountry.parseTrace("loc=gb\n"))
        assertEquals("US", VpnCountry.parseTrace("loc=US\n"))
        assertNull(VpnCountry.parseTrace("ip=1.2.3.4\n"))
        assertNull(VpnCountry.parseTrace("loc=Ireland\n"))
        assertNull(VpnCountry.parseTrace("loc=\n"))
    }

    @Test
    fun newsStoryKeepsSummaryAndPicture() {
        val xml = """
            <item>
              <title>Big story</title>
              <link>https://example.com/story</link>
              <description><![CDATA[A <b>short</b> summary.]]></description>
              <enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>
              <media:thumbnail url="https://example.com/a.jpg"/>
            </item>
        """.trimIndent()
        val story = NewsFeed.parseStories(xml, 5).single()
        assertEquals("Big story", story.title)
        assertEquals("A short summary.", story.summary)
        assertEquals("https://example.com/a.jpg", story.imageUrl)
        assertEquals(
            "https://ichef.bbci.co.uk/news/1024/branded_news/a.jpg",
            NewsFeed.parseStories(
                """
                <item><title>Wide</title>
                <media:thumbnail url="https://ichef.bbci.co.uk/news/240/branded_news/a.jpg" width="240" height="135"/>
                <media:thumbnail url="https://ichef.bbci.co.uk/news/976/branded_news/a.jpg" width="976" height="549"/>
                </item>
                """.trimIndent(),
                1,
            ).single().imageUrl,
        )
        assertEquals(
            "https://i.guim.co.uk/img/x.jpg?width=1400&quality=85",
            NewsFeed.enlargeNewsImage("https://i.guim.co.uk/img/x.jpg?width=140&quality=85"),
        )
        assertEquals(
            "https://npr.brightspotcdn.com/x/resize/1400x933!/q",
            NewsFeed.enlargeNewsImage("https://npr.brightspotcdn.com/x/resize/8287x5525!/q"),
        )
        assertEquals(
            "https://cdn.mos.cms.futurecdn.net/abc-1400-80.jpg",
            NewsFeed.enlargeNewsImage("https://cdn.mos.cms.futurecdn.net/abc-1920-80.jpg"),
        )
        assertEquals(
            "https://cdn.mos.cms.futurecdn.net/abc-1400-70.png",
            NewsFeed.enlargeNewsImage("https://cdn.mos.cms.futurecdn.net/abc-320-70.png"),
        )
        assertEquals(
            "https://platform.theverge.com/a.jpg?w=1400&h=1400",
            NewsFeed.enlargeNewsImage("https://platform.theverge.com/a.jpg?w=150&amp;h=150"),
        )
        assertEquals(
            "https://media.npr.org/a.jpg?s=1400",
            NewsFeed.enlargeNewsImage("https://media.npr.org/a.jpg?s=200"),
        )
        assertEquals(
            "https://static0.polygonimages.com/a.jpg",
            NewsFeed.enlargeNewsImage("https://static0.polygonimages.com/a.jpg"),
        )
        assertEquals("https://example.com/story", story.link)
        assertEquals(story.title, NewsFeed.featured(listOf(Headline(NewsSource.BBC_WORLD, story.title, story.summary, story.imageUrl, story.link)))?.title)
    }

    @Test
    fun forecastSkipsPastHoursAndKeepsHighsAndLows() {
        val body = """
            {"hourly":{"time":["2026-10-26T08:00","2026-10-26T09:00","2026-10-26T10:00"],
            "temperature_2m":[1.2,2.2,3.2],"weather_code":[0,3,61],"is_day":[1,1,0]},
            "daily":{"time":["2026-10-26","2026-10-27"],"weather_code":[3,61],
            "temperature_2m_max":[10.4,11.2],"temperature_2m_min":[4.1,5.0]}}
        """.trimIndent()
        val forecast = WeatherClient.parseForecast(body, 8, 5, LocalDateTime.of(2026, 10, 26, 9, 15))
        assertEquals(listOf("09:00", "10:00"), forecast.hours.map { it.label })
        assertEquals(2.2, forecast.hours[0].temperatureC, 0.01)
        assertEquals(false, forecast.hours[1].isDay)
        assertEquals(2, forecast.days.size)
        assertEquals(10.4, forecast.days[0].temperatureC, 0.01)
        assertEquals(4.1, forecast.days[0].lowC ?: Double.NaN, 0.01)
        assertTrue(forecast.days[0].allDay)
    }

    @Test
    fun stagePoolKeepsEverySourceAndPutsPicturesFirst() {
        val stories = listOf(
            Headline(NewsSource.AL_JAZEERA, "aj text"),
            Headline(NewsSource.BBC_WORLD, "bbc text"),
            Headline(NewsSource.BBC_WORLD, "bbc art", imageUrl = "https://example.com/a.jpg"),
            Headline(NewsSource.AL_JAZEERA, "aj text 2"),
        )
        assertEquals(
            listOf("aj text", "bbc art", "aj text 2", "bbc text"),
            NewsFeed.stagePool(stories).map { it.title },
        )
        assertEquals(NewsPace.S60, NewsPace.fromName(null))
        assertEquals(30_000L, NewsPace.S30.millis)
        assertEquals(90_000L, NewsPace.S90.millis)
    }

    @Test
    fun readerKeepsTheArticleAndDropsChrome() {
        val html = """
            <html><body>
            <script>alert("ad")</script>
            <iframe src="https://ads.example/popup"></iframe>
            <article>
              <p>Subscribe to our newsletter</p>
              <p>This is the real opening paragraph of the story and it is long enough to be kept in the reader.</p>
              <p>A second paragraph follows with enough words to be the body of the article itself and nothing else.</p>
            </article>
            </body></html>
        """.trimIndent()
        val text = ArticleReader.extract(html)
        assertEquals(2, text.size)
        assertTrue(text[0].startsWith("This is the real"))
        assertTrue(text.none { "Subscribe" in it || "alert" in it || "ads.example" in it })
        val embedded = ArticleReader.extract(
            """
            <html><script>{"articleBody":"This is the real opening paragraph of the story and it is long enough to be kept in the reader.\nA second paragraph follows with enough words to be the body of the article itself and nothing else."}</script></html>
            """.trimIndent(),
        )
        assertEquals(2, embedded.size)
        assertTrue(embedded[0].startsWith("This is the real"))
        assertEquals(
            "https://example.com/amp/story",
            ArticleReader.ampLink("""<link rel="amphtml" href="https://example.com/amp/story">"""),
        )
    }

    @Test
    fun atomAlternateLinkBeatsTheFeedLink() {
        val xml = """
            <entry>
              <title>Atom story</title>
              <link rel="self" href="https://example.com/feed"/>
              <link rel="alternate" href="https://example.com/atom"/>
            </entry>
        """.trimIndent()
        assertEquals("https://example.com/atom", NewsFeed.parseStories(xml, 2).single().link)
    }

    @Test
    fun upNextKeepsThreeCardsFromEachSource() {
        val pkgs = listOf("a", "a", "b", "a", "a", "b", "b", "b", "c")
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 8), capUpNextIndexes(pkgs, null))
        assertEquals(listOf(2, 5, 6), capUpNextIndexes(pkgs, setOf("b")))
        assertEquals(emptyList<Int>(), capUpNextIndexes(pkgs, emptySet()))
        assertEquals(UP_NEXT_PER_SOURCE, 3)
    }

    @Test
    fun mediaCardsPinAheadOfNewerOnesAndKeepArt() {
        val older = MediaCard("a", "One", "ep", "Jellyfin", "https://old", pinned = false, seenAt = 1)
        val newer = MediaCard("b", "Two", "", "YouTube", null, pinned = false, seenAt = 2)
        val merged = MediaCards.merge(listOf(older), newer, now = 3)
        assertEquals(listOf("Two", "One"), merged.map { it.title })
        val pinned = MediaCards.order(merged.map { if (it.title == "One") it.copy(pinned = true) else it })
        assertEquals("One", pinned[0].title)
        assertTrue(pinned[0].pinned)
        val refreshed = MediaCards.merge(pinned, older.copy(artUri = null, pinned = false), now = 9)
        assertEquals("https://old", refreshed.first { it.title == "One" }.artUri)
        assertTrue(refreshed.first { it.title == "One" }.pinned)
    }

    @Test
    fun editHomeAndSettingsCannotBothBeHidden() {
        assertTrue(canHideHeaderElement(HeaderElement.EDIT, emptySet()))
        assertTrue(!canHideHeaderElement(HeaderElement.EDIT, setOf(HeaderElement.SETTINGS)))
        assertTrue(!canHideHeaderElement(HeaderElement.SETTINGS, setOf(HeaderElement.EDIT)))
        assertTrue(canHideHeaderElement(HeaderElement.CLOCK, setOf(HeaderElement.EDIT, HeaderElement.SETTINGS)))
    }

    @Test
    fun downFromTheBottomWrapsToTheRightmostIcon() {
        assertEquals(HeaderElement.SETTINGS, headerEndElement(DisplayConfig(), hasStore = true))
        val noSettings = DisplayConfig(hidden = setOf(HeaderElement.SETTINGS))
        assertEquals(HeaderElement.STORE, headerEndElement(noSettings, hasStore = true))
        assertEquals(HeaderElement.EDIT, headerEndElement(noSettings, hasStore = false))
        assertNull(headerEndElement(DisplayConfig(headerVisible = false), hasStore = true))
    }

    @Test
    fun feedDatesParseInRssAndAtomForms() {
        val t = 1_793_279_964_000L // 2026-10-29T13:19:24Z
        assertEquals(t, NewsFeed.parseDate("Thu, 29 Oct 2026 13:19:24 GMT"))
        assertEquals(t, NewsFeed.parseDate("Thu, 29 Oct 2026 09:19:24 -0400"))
        assertEquals(t, NewsFeed.parseDate("2026-10-29T13:19:24Z"))
        assertEquals(t, NewsFeed.parseDate("2026-10-29T15:19:24+02:00"))
        assertNull(NewsFeed.parseDate("yesterday"))
    }

    @Test
    fun storiesComeNewestFirstAndOldOnesDropWhileEnoughRemain() {
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        fun story(title: String, hoursAgo: Long?) = NewsStory(title, published = hoursAgo?.let { now - it * hour })
        val feed = listOf(story("old", 72), story("b", 5), story("undated", null), story("a", 1), story("c", 10), story("d", 20))
        // Importance order in, newest first out; undated stories stay, after the dated ones.
        assertEquals(listOf("a", "b", "c", "d", "undated"), NewsFeed.freshest(feed, now).map { it.title })
        // Too few recent stories: the newest few are kept whatever their age.
        val stale = listOf(story("x", 100), story("y", 80), story("z", 90))
        assertEquals(listOf("y", "z", "x"), NewsFeed.freshest(stale, now).map { it.title })
    }

    @Test
    fun parsedStoriesCarryTheirDate() {
        val xml = "<rss><channel><item><title>Hi</title><pubDate>Thu, 29 Oct 2026 13:19:24 GMT</pubDate></item></channel></rss>"
        assertEquals(1_793_279_964_000L, NewsFeed.parseStories(xml, 5).single().published)
    }
}
