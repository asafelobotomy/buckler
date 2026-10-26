package dev.solon.bucklerhome

import android.content.Context
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
    val news: NewsSource? = NewsSource.BBC_WORLD,
    val nowPlaying: Boolean = true,
    /** Off: no header at all, only shelves. */
    val headerVisible: Boolean = true,
    val hidden: Set<HeaderElement> = emptySet(),
) {
    fun shows(element: HeaderElement): Boolean = headerVisible && element !in hidden

    /** Weather is fetched only when it will be shown. */
    val weatherPlace: Place? get() = place?.takeIf { shows(HeaderElement.WEATHER) }
    val showsNews: Boolean get() = headerVisible && news != null
    val showsNowPlaying: Boolean get() = headerVisible && nowPlaying
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
    private const val KEY_NOW_PLAYING = "now_playing_enabled"
    private const val KEY_HEADER_VISIBLE = "header_visible"
    private const val KEY_HEADER_HIDDEN = "header_hidden"
    private const val NEWS_OFF = "off"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): DisplayConfig {
        val p = prefs(context)
        val name = p.getString(KEY_PLACE_NAME, null)
        val place = if (name != null && p.contains(KEY_PLACE_LAT) && p.contains(KEY_PLACE_LON)) {
            Place(name, p.getFloat(KEY_PLACE_LAT, 0f).toDouble(), p.getFloat(KEY_PLACE_LON, 0f).toDouble())
        } else {
            null
        }
        val newsRaw = p.getString(KEY_NEWS, NewsSource.BBC_WORLD.name)
        return DisplayConfig(
            use24h = p.getBoolean(KEY_24H, true),
            dateStyle = DateStyle.fromName(p.getString(KEY_DATE_STYLE, null)),
            place = place,
            fahrenheit = p.getBoolean(KEY_FAHRENHEIT, false),
            news = if (newsRaw == NEWS_OFF) null else NewsSource.fromName(newsRaw),
            nowPlaying = p.getBoolean(KEY_NOW_PLAYING, true),
            headerVisible = p.getBoolean(KEY_HEADER_VISIBLE, true),
            hidden = p.getStringSet(KEY_HEADER_HIDDEN, emptySet()).orEmpty()
                .mapNotNull { raw -> HeaderElement.entries.firstOrNull { it.name == raw } }
                .toSet(),
        )
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

    fun setNews(context: Context, source: NewsSource?) =
        prefs(context).edit().putString(KEY_NEWS, source?.name ?: NEWS_OFF).apply()

    fun setPlace(context: Context, place: Place?) {
        val edit = prefs(context).edit()
        if (place == null) {
            edit.remove(KEY_PLACE_NAME).remove(KEY_PLACE_LAT).remove(KEY_PLACE_LON)
        } else {
            edit.putString(KEY_PLACE_NAME, place.name)
                .putFloat(KEY_PLACE_LAT, place.lat.toFloat())
                .putFloat(KEY_PLACE_LON, place.lon.toFloat())
        }
        edit.apply()
    }
}
