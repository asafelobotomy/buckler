package dev.solon.bucklerhome

import android.content.Context

enum class ScreensaverMode(val label: String) {
    CLOCK("Drifting clock"),
    DVD("DVD bounce"),
    PHOTOS("Photos (online)"),
    UPLOADED("My photos"),
    NOW_PLAYING("Now Playing"),
    MAZE("3D Maze"),
    PIPES("3D Pipes"),
    MYSTIFY("Mystify"),
    STARFIELD("Starfield"),
    ;

    val isPhotos: Boolean get() = this == PHOTOS || this == UPLOADED

    /** Full-screen animations that can carry the small clock overlay. */
    val isRetro: Boolean get() = this == MAZE || this == PIPES || this == MYSTIFY || this == STARFIELD

    companion object {
        fun fromName(raw: String?): ScreensaverMode = entries.firstOrNull { it.name == raw } ?: CLOCK
    }
}

/**
 * Online photo categories. Images come from Wikimedia Commons "Featured pictures", which are
 * freely licensed; [query] is a CirrusSearch expression combined with that category.
 */
enum class PhotoCategory(val label: String, val query: String) {
    NATURE("Nature", "forest OR meadow OR mountain OR waterfall OR lake"),
    CITYSCAPE(
        "Cityscape",
        "skyline OR cityscape OR downtown OR skyscraper OR \"old town\" OR harbour OR \"city at night\" OR \"night view\"",
    ),
    SEASCAPE("Seascape", "seascape OR coast OR beach OR lighthouse OR ocean"),
    LANDMARKS("Landmarks", "castle OR cathedral OR palace OR temple OR bridge"),
    SKY("Sky & space", "aurora OR \"milky way\" OR nebula OR sunset OR clouds"),
    WILDLIFE(
        "Wildlife",
        "bird OR butterfly OR wildlife OR deer OR fox OR owl OR eagle OR mammal OR elephant OR zebra OR heron",
    ),
    ;

    companion object {
        fun fromName(raw: String?): PhotoCategory = entries.firstOrNull { it.name == raw } ?: NATURE
    }
}

data class ScreensaverConfig(
    val mode: ScreensaverMode = ScreensaverMode.CLOCK,
    /** Online photo categories; slides take turns between them. Never empty. */
    val categories: Set<PhotoCategory> = setOf(PhotoCategory.NATURE),
    val intervalSeconds: Int = 20,
    val showClock: Boolean = true,
    val use24h: Boolean = true,
    val font: FontChoice = FontChoice.SYSTEM,
    val theme: AppTheme = AppTheme.BUCKLER,
    val reduceMotion: Boolean = false,
)

object ScreensaverPrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_MODE = "saver_mode"
    private const val KEY_CATEGORY = "saver_category"
    private const val KEY_CATEGORIES = "saver_categories"
    private const val KEY_INTERVAL = "saver_interval"
    private const val KEY_CLOCK = "saver_clock"
    val INTERVALS = listOf(10, 20, 30, 60, 120)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): ScreensaverConfig {
        val p = prefs(context)
        val appearance = AppearancePrefs.load(context)
        return ScreensaverConfig(
            mode = ScreensaverMode.fromName(p.getString(KEY_MODE, null)),
            categories = readCategories(p),
            intervalSeconds = p.getInt(KEY_INTERVAL, 20),
            showClock = p.getBoolean(KEY_CLOCK, true),
            use24h = DisplayPrefs.load(context).use24h,
            font = appearance.font,
            theme = appearance.theme,
            reduceMotion = appearance.reduceMotion,
        )
    }

    fun setMode(context: Context, mode: ScreensaverMode) =
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()

    /** Several categories now; the single pick from before carries over as the first. */
    private fun readCategories(p: android.content.SharedPreferences): Set<PhotoCategory> {
        val names = p.getStringSet(KEY_CATEGORIES, null)
            ?: return setOf(PhotoCategory.fromName(p.getString(KEY_CATEGORY, null)))
        return PhotoCategory.entries.filter { it.name in names }.toSet().ifEmpty { setOf(PhotoCategory.NATURE) }
    }

    /** Toggles [category]; returns false (and changes nothing) when it is the last one on. */
    fun toggleCategory(context: Context, current: Set<PhotoCategory>, category: PhotoCategory): Boolean {
        val next = if (category in current) current - category else current + category
        if (next.isEmpty()) return false
        prefs(context).edit().putStringSet(KEY_CATEGORIES, next.map { it.name }.toSet()).apply()
        return true
    }

    fun setShowClock(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLOCK, value).apply()

    fun cycleInterval(context: Context, current: Int) {
        val next = INTERVALS.getOrElse(INTERVALS.indexOf(current) + 1) { INTERVALS.first() }
        prefs(context).edit().putInt(KEY_INTERVAL, next).apply()
    }
}
