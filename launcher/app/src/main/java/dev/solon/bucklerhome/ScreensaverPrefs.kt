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
    /** Every screensaver's own option values (see [ScreensaverMode.options]); missing ones use defaults. */
    val options: Map<ScreensaverMode, Map<SaverOption, Int>> = emptyMap(),
    val use24h: Boolean = true,
    val dateStyle: DateStyle = DateStyle.SHORT,
    val font: FontChoice = FontChoice.SYSTEM,
    val theme: AppTheme = AppTheme.BUCKLER,
    val reduceMotion: Boolean = false,
) {
    /** [option]'s value for the screensaver being shown. */
    fun value(option: SaverOption): Int = options[mode]?.get(option) ?: option.default

    fun on(option: SaverOption): Boolean = value(option) != 0

    /** [SaverOption.SPEED] as a multiplier (1 = normal). */
    val speed: Float get() = value(SaverOption.SPEED) / 100f
    val intervalSeconds: Int get() = value(SaverOption.INTERVAL)
    val showClock: Boolean get() = on(SaverOption.CLOCK)
}

object ScreensaverPrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_MODE = "saver_mode"
    private const val KEY_CATEGORY = "saver_category"
    private const val KEY_CATEGORIES = "saver_categories"
    private const val KEY_INTERVAL = "saver_interval"
    private const val KEY_CLOCK = "saver_clock"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): ScreensaverConfig {
        val p = prefs(context)
        val appearance = AppearancePrefs.load(context)
        val display = DisplayPrefs.load(context)
        return ScreensaverConfig(
            mode = ScreensaverMode.fromName(p.getString(KEY_MODE, null)),
            categories = readCategories(p),
            options = ScreensaverMode.entries.associateWith { mode ->
                mode.options.associateWith { option -> readOption(p, mode, option) }
            },
            use24h = display.use24h,
            dateStyle = display.dateStyle,
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

    private fun optionKey(mode: ScreensaverMode, option: SaverOption) = "saver_${mode.name.lowercase()}_${option.name.lowercase()}"

    /**
     * A screensaver's own value. Before each had its own, one clock-overlay switch and one photo
     * interval were shared; those carry over as every screensaver's starting value.
     */
    private fun readOption(p: android.content.SharedPreferences, mode: ScreensaverMode, option: SaverOption): Int {
        val key = optionKey(mode, option)
        if (p.contains(key)) return p.getInt(key, option.default)
        return when (option) {
            SaverOption.CLOCK -> if (p.getBoolean(KEY_CLOCK, true)) 1 else 0
            SaverOption.INTERVAL -> p.getInt(KEY_INTERVAL, option.default).takeIf { v -> option.choices.any { it.first == v } } ?: option.default
            else -> option.default
        }
    }

    fun setOption(context: Context, mode: ScreensaverMode, option: SaverOption, value: Int) =
        prefs(context).edit().putInt(optionKey(mode, option), value).apply()
}
