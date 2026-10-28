package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date styles offered in Settings → Header. The sample text is rendered live from the pattern. */
enum class DateStyle(val pattern: String) {
    SHORT("EEE d MMM"),
    LONG("EEEE, d MMMM"),
    DAY_MONTH_YEAR("dd/MM/yyyy"),
    MONTH_DAY_YEAR("MM/dd/yyyy"),
    ISO("yyyy-MM-dd"),
    US_LONG("MMM d, yyyy"),
    ;

    fun next(): DateStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(raw: String?): DateStyle = entries.firstOrNull { it.name == raw } ?: SHORT
    }
}

object DisplayFormat {
    fun time(time: LocalTime, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
        val pattern = if (use24h) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    fun date(date: LocalDate, style: DateStyle, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern(style.pattern, locale))
}

/** How big the header row is. Widgets (logo, clock, icons, Now Playing, ticker) scale together. */
enum class HeaderSize(val label: String, val scale: Float) {
    THIN("Thin", 0.8f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.3f),
    ;

    companion object {
        fun fromName(raw: String?): HeaderSize = entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

/** One row on the Weather stage: the coming hours, or the next ten days. */
enum class WeatherSpan(val label: String) {
    HOURS("Next hours"),
    DAYS("Next 10 days"),
    ;

    companion object {
        fun fromName(raw: String?): WeatherSpan = entries.firstOrNull { it.name == raw } ?: HOURS
    }
}

/** How often the Main Stage advances to the next news story. */
enum class NewsPace(val label: String, val millis: Long) {
    S30("30s", 30_000),
    S60("60s", 60_000),
    S90("90s", 90_000),
    ;

    companion object {
        fun fromName(raw: String?): NewsPace = entries.firstOrNull { it.name == raw } ?: S60
    }
}

/** Scroll speed of the news ticker, and how long each story stays when motion is reduced. */
enum class TickerSpeed(val label: String, val velocityDp: Float, val rotateMs: Long) {
    SLOW("Slow", 20f, 14_000),
    NORMAL("Normal", 42f, 9_000),
    FAST("Fast", 80f, 5_000),
    ;

    companion object {
        fun fromName(raw: String?): TickerSpeed = entries.firstOrNull { it.name == raw } ?: NORMAL
    }
}

/** Header pieces that can be switched off one by one in Settings → Header. */
enum class HeaderElement(val label: String) {
    LOGO("Logo"),
    CLOCK("Clock"),
    DATE("Date"),
    WEATHER("Weather"),
    NETWORK("Network"),
    VPN("VPN"),
    EDIT("Edit Home"),
    STORE("Play Store"),
    SETTINGS("Settings"),
}

/** Header content the user can switch on/off or tune; read on every resume. */
data class DisplayConfig(
    val use24h: Boolean = true,
    val dateStyle: DateStyle = DateStyle.SHORT,
    val place: Place? = null,
    val fahrenheit: Boolean = false,
    /** Chosen feeds, in catalog order. Several are mixed; none turns the ticker off. */
    val newsSources: List<NewsSource> = listOf(NewsSource.BBC_WORLD),
    val newsRegion: NewsRegion = NewsRegion.WORLDWIDE,
    val newsPace: NewsPace = NewsPace.S60,
    /** Hours or ten days, for the Weather stage only. */
    val weatherSpan: WeatherSpan = WeatherSpan.HOURS,
    val tickerSpeed: TickerSpeed = TickerSpeed.NORMAL,
    val headerSize: HeaderSize = HeaderSize.MEDIUM,
    val nowPlaying: Boolean = true,
    /** Off: no header at all, only shelves. */
    val headerVisible: Boolean = true,
    val hidden: Set<HeaderElement> = emptySet(),
    /**
     * News and Weather appear in the header and on the Main Stage. While synced (the default) both
     * use the fields above; unsynced, the Main Stage uses its own copies below.
     */
    val newsSynced: Boolean = true,
    val stageNewsSources: List<NewsSource> = newsSources,
    val stageNewsRegion: NewsRegion = newsRegion,
    val weatherSynced: Boolean = true,
    val stagePlace: Place? = place,
    val stageFahrenheit: Boolean = fahrenheit,
) {
    /** What the News stage shows and the Weather stage uses: the header's when synced. */
    val stageNews: List<NewsSource> get() = if (newsSynced) newsSources else stageNewsSources
    val stageWeatherPlace: Place? get() = if (weatherSynced) place else stagePlace
    val stageWeatherFahrenheit: Boolean get() = if (weatherSynced) fahrenheit else stageFahrenheit

    fun shows(element: HeaderElement): Boolean = headerVisible && element !in hidden

    /** Weather is fetched only when it will be shown. */
    val weatherPlace: Place? get() = place?.takeIf { shows(HeaderElement.WEATHER) }
    val showsNews: Boolean get() = headerVisible && newsSources.isNotEmpty()

    /** Null when the ticker is off, so Home does not fetch feeds it will not show. */
    fun newsQuery(): NewsQuery? = if (showsNews) NewsQuery(newsSources) else null
    val showsNowPlaying: Boolean get() = headerVisible && nowPlaying

    /** Edit Home or Settings is in the header. Without either, Home adds a Home options card. */
    val reachesSettings: Boolean get() = shows(HeaderElement.EDIT) || shows(HeaderElement.SETTINGS)
}

/** Edit Home and Settings can each be hidden, but not both: one of them is the way back here. */
fun canHideHeaderElement(element: HeaderElement, hidden: Set<HeaderElement>): Boolean {
    val routes = setOf(HeaderElement.EDIT, HeaderElement.SETTINGS)
    return element !in routes || (routes - element).any { it !in hidden }
}

object DisplayPrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_24H = "display_24h"
    private const val KEY_DATE_STYLE = "display_date_style"
    private const val KEY_PLACE_NAME = "weather_place_name"
    private const val KEY_PLACE_LAT = "weather_place_lat"
    private const val KEY_PLACE_LON = "weather_place_lon"
    private const val KEY_FAHRENHEIT = "weather_fahrenheit"
    private const val KEY_NEWS = "news_source"
    private const val KEY_NEWS_SHUFFLE = "news_shuffle"
    private const val KEY_NEWS_SOURCES = "news_sources"
    private const val KEY_NEWS_REGION = "news_region"
    private const val KEY_WEATHER_SPAN = "weather_span"
    private const val KEY_NEWS_PACE = "news_stage_pace"
    private const val KEY_TICKER_SPEED = "news_ticker_speed"
    private const val KEY_HEADER_SIZE = "header_size"
    private const val KEY_NOW_PLAYING = "now_playing_enabled"
    private const val KEY_HEADER_VISIBLE = "header_visible"
    private const val KEY_HEADER_HIDDEN = "header_hidden"
    private const val NEWS_OFF = "off"
    private const val KEY_NEWS_SYNCED = "news_synced"
    private const val KEY_STAGE_NEWS_SOURCES = "news_stage_sources"
    private const val KEY_STAGE_NEWS_REGION = "news_stage_region"
    private const val KEY_WEATHER_SYNCED = "weather_synced"
    private const val KEY_STAGE_PLACE = "weather_stage_place"
    private const val KEY_STAGE_FAHRENHEIT = "weather_stage_fahrenheit"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): DisplayConfig {
        val p = prefs(context)
        val place = readPlace(p, KEY_PLACE_NAME, KEY_PLACE_LAT, KEY_PLACE_LON)
        val sources = readNewsSources(p)
        val region = NewsRegion.fromName(p.getString(KEY_NEWS_REGION, null))
        val fahrenheit = p.getBoolean(KEY_FAHRENHEIT, false)
        return DisplayConfig(
            use24h = p.getBoolean(KEY_24H, true),
            dateStyle = DateStyle.fromName(p.getString(KEY_DATE_STYLE, null)),
            place = place,
            fahrenheit = fahrenheit,
            newsSources = sources,
            newsRegion = region,
            newsPace = NewsPace.fromName(p.getString(KEY_NEWS_PACE, null)),
            weatherSpan = WeatherSpan.fromName(p.getString(KEY_WEATHER_SPAN, null)),
            tickerSpeed = TickerSpeed.fromName(p.getString(KEY_TICKER_SPEED, null)),
            headerSize = HeaderSize.fromName(p.getString(KEY_HEADER_SIZE, null)),
            nowPlaying = p.getBoolean(KEY_NOW_PLAYING, true),
            headerVisible = p.getBoolean(KEY_HEADER_VISIBLE, true),
            hidden = p.getStringSet(KEY_HEADER_HIDDEN, emptySet()).orEmpty()
                .mapNotNull { raw -> HeaderElement.entries.firstOrNull { it.name == raw } }
                .toSet(),
            newsSynced = p.getBoolean(KEY_NEWS_SYNCED, true),
            stageNewsSources = p.getStringSet(KEY_STAGE_NEWS_SOURCES, null)
                ?.let { names -> NewsSource.entries.filter { it.name in names } } ?: sources,
            stageNewsRegion = p.getString(KEY_STAGE_NEWS_REGION, null)?.let(NewsRegion::fromName) ?: region,
            weatherSynced = p.getBoolean(KEY_WEATHER_SYNCED, true),
            stagePlace = readPlace(p, "${KEY_STAGE_PLACE}_name", "${KEY_STAGE_PLACE}_lat", "${KEY_STAGE_PLACE}_lon")
                ?: place.takeIf { !p.contains("${KEY_STAGE_PLACE}_cleared") },
            stageFahrenheit = if (p.contains(KEY_STAGE_FAHRENHEIT)) p.getBoolean(KEY_STAGE_FAHRENHEIT, false) else fahrenheit,
        )
    }

    private fun readPlace(p: SharedPreferences, name: String, lat: String, lon: String): Place? {
        val label = p.getString(name, null) ?: return null
        if (!p.contains(lat) || !p.contains(lon)) return null
        return Place(label, p.getFloat(lat, 0f).toDouble(), p.getFloat(lon, 0f).toDouble())
    }

    fun setUse24h(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_24H, value).apply()

    fun setDateStyle(context: Context, style: DateStyle) =
        prefs(context).edit().putString(KEY_DATE_STYLE, style.name).apply()

    fun setFahrenheit(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FAHRENHEIT, value).apply()

    fun setNowPlaying(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOW_PLAYING, value).apply()

    fun setHeaderVisible(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HEADER_VISIBLE, value).apply()

    fun setHidden(context: Context, hidden: Set<HeaderElement>) =
        prefs(context).edit().putStringSet(KEY_HEADER_HIDDEN, hidden.map { it.name }.toSet()).apply()

    fun setNewsSources(context: Context, sources: List<NewsSource>) =
        prefs(context).edit().putStringSet(KEY_NEWS_SOURCES, sources.map { it.name }.toSet()).apply()

    fun setNewsRegion(context: Context, region: NewsRegion) {
        val sources = sourcesForRegion(region, load(context).newsSources)
        prefs(context).edit()
            .putString(KEY_NEWS_REGION, region.name)
            .putStringSet(KEY_NEWS_SOURCES, sources.map { it.name }.toSet())
            .apply()
    }

    fun setWeatherSpan(context: Context, span: WeatherSpan) =
        prefs(context).edit().putString(KEY_WEATHER_SPAN, span.name).apply()

    /** New multi-select, or the old single source / shuffle flag from before it existed. */
    private fun readNewsSources(p: SharedPreferences): List<NewsSource> {
        if (p.contains(KEY_NEWS_SOURCES)) {
            val names = p.getStringSet(KEY_NEWS_SOURCES, emptySet()).orEmpty()
            return NewsSource.entries.filter { it.name in names }
        }
        if (p.getBoolean(KEY_NEWS_SHUFFLE, false)) {
            return listOf(NewsSource.BBC_WORLD, NewsSource.NPR, NewsSource.GUARDIAN, NewsSource.AL_JAZEERA)
        }
        val raw = p.getString(KEY_NEWS, NewsSource.BBC_WORLD.name)
        if (raw == NEWS_OFF) return emptyList()
        return listOfNotNull(NewsSource.fromName(raw)).ifEmpty { listOf(NewsSource.BBC_WORLD) }
    }

    fun setNewsPace(context: Context, pace: NewsPace) =
        prefs(context).edit().putString(KEY_NEWS_PACE, pace.name).apply()

    fun setTickerSpeed(context: Context, speed: TickerSpeed) =
        prefs(context).edit().putString(KEY_TICKER_SPEED, speed.name).apply()

    fun setHeaderSize(context: Context, size: HeaderSize) =
        prefs(context).edit().putString(KEY_HEADER_SIZE, size.name).apply()

    fun setPlace(context: Context, place: Place?) =
        writePlace(context, place, KEY_PLACE_NAME, KEY_PLACE_LAT, KEY_PLACE_LON)

    private fun writePlace(context: Context, place: Place?, name: String, lat: String, lon: String) {
        val edit = prefs(context).edit()
        if (place == null) {
            edit.remove(name).remove(lat).remove(lon)
        } else {
            edit.putString(name, place.name)
                .putFloat(lat, place.lat.toFloat())
                .putFloat(lon, place.lon.toFloat())
        }
        edit.apply()
    }

    // --- Main Stage copies, used while a widget is not synced across sections -----------------

    fun setStageNewsSources(context: Context, sources: List<NewsSource>) =
        prefs(context).edit().putStringSet(KEY_STAGE_NEWS_SOURCES, sources.map { it.name }.toSet()).apply()

    fun setStageNewsRegion(context: Context, region: NewsRegion) {
        val sources = sourcesForRegion(region, load(context).stageNewsSources)
        prefs(context).edit()
            .putString(KEY_STAGE_NEWS_REGION, region.name)
            .putStringSet(KEY_STAGE_NEWS_SOURCES, sources.map { it.name }.toSet())
            .apply()
    }

    fun setStagePlace(context: Context, place: Place?) {
        writePlace(context, place, "${KEY_STAGE_PLACE}_name", "${KEY_STAGE_PLACE}_lat", "${KEY_STAGE_PLACE}_lon")
        val edit = prefs(context).edit()
        if (place == null) edit.putBoolean("${KEY_STAGE_PLACE}_cleared", true) else edit.remove("${KEY_STAGE_PLACE}_cleared")
        edit.apply()
    }

    fun setStageFahrenheit(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_STAGE_FAHRENHEIT, value).apply()

    /**
     * Sync News across sections, or stop. Stopping gives the Main Stage a copy of what both use now,
     * so nothing changes until one is edited. Syncing again keeps the settings of [from].
     */
    fun setNewsSynced(context: Context, synced: Boolean, from: HomeSection) {
        val cfg = load(context)
        if (!synced) {
            setStageNewsSources(context, cfg.newsSources)
            prefs(context).edit().putString(KEY_STAGE_NEWS_REGION, cfg.newsRegion.name).apply()
        } else if (from == HomeSection.MAIN_STAGE) {
            setNewsSources(context, cfg.stageNewsSources)
            prefs(context).edit().putString(KEY_NEWS_REGION, cfg.stageNewsRegion.name).apply()
        }
        prefs(context).edit().putBoolean(KEY_NEWS_SYNCED, synced).apply()
    }

    /** As [setNewsSynced], for the weather city and units. */
    fun setWeatherSynced(context: Context, synced: Boolean, from: HomeSection) {
        val cfg = load(context)
        if (!synced) {
            setStagePlace(context, cfg.place)
            setStageFahrenheit(context, cfg.fahrenheit)
        } else if (from == HomeSection.MAIN_STAGE) {
            setPlace(context, cfg.stagePlace)
            setFahrenheit(context, cfg.stageFahrenheit)
        }
        prefs(context).edit().putBoolean(KEY_WEATHER_SYNCED, synced).apply()
    }
}

/** Parts of Home a widget can appear in; see [DisplayPrefs.setNewsSynced]. */
enum class HomeSection { HEADER, MAIN_STAGE }
