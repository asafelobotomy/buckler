package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Colour schemes offered in Settings → Appearance. */
enum class AppTheme(val label: String) {
    BUCKLER("Buckler"),
    LIGHT("Light"),
    DARK("Dark"),
    GRUVBOX("Gruvbox"),
    SNES_UK("SNES (UK)"),
    SNES_US("SNES (US)"),
    SHIELD("SHIELD"),
    SLATE("Slate"),
    VELVET("Velvet"),
    EMBER("Ember"),
    TIDEPOOL("Tidepool"),
    FJORD("Fjord"),
    MARSHMALLOW("Marshmallow"),
    NEON("Neon"),
    GRAPHITE("Graphite"),
    ROSEWOOD("Rosewood"),
    SAFFRON("Saffron"),
    ;

    companion object {
        fun fromName(raw: String?): AppTheme = entries.firstOrNull { it.name == raw } ?: BUCKLER
    }
}

enum class FontChoice(val label: String) {
    SYSTEM("System"),
    HYPERLEGIBLE("Hyperlegible"),
    CONDENSED("Condensed"),
    SERIF("Serif"),
    PLAYFUL("Playful"),
    MONO("Monospace"),
    ;

    companion object {
        fun fromName(raw: String?): FontChoice = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** Global text scaling for low-vision users, applied on top of the system font scale. */
enum class TextSize(val label: String, val scale: Float) {
    NORMAL("Normal", 1f),
    LARGE("Large", 1.15f),
    EXTRA_LARGE("Extra large", 1.3f),
    ;

    companion object {
        fun fromName(raw: String?): TextSize = entries.firstOrNull { it.name == raw } ?: NORMAL
    }
}

/** App tile size, applied to every cover layout. Stored with the home layout. */
enum class TileSize(
    val label: String,
    val scale: Float,
    /** Height factor for banners. Thin is shorter than its width, so banners become a slim strip. */
    val heightScale: Float = scale,
) {
    THIN("Thin", 0.68f, 0.48f),
    SMALL("Small", 0.8f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.25f),
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): TileSize = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEDIUM
    }
}

data class AppearanceConfig(
    val theme: AppTheme = AppTheme.BUCKLER,
    val font: FontChoice = FontChoice.SYSTEM,
    val textSize: TextSize = TextSize.NORMAL,
    val highContrast: Boolean = false,
    /** Thick, bright outline around whatever has D-pad focus. */
    val strongFocus: Boolean = false,
    /** No focus zoom, no scrolling news ticker, no photo pan/zoom. */
    val reduceMotion: Boolean = false,
    /** Speak the label of the focused control with text-to-speech. */
    val spokenFocus: Boolean = false,
)

object AppearancePrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_THEME = "appearance_theme"
    private const val KEY_FONT = "appearance_font"
    private const val KEY_TEXT_SIZE = "appearance_text_size"
    private const val KEY_HIGH_CONTRAST = "a11y_high_contrast"
    private const val KEY_STRONG_FOCUS = "a11y_strong_focus"
    private const val KEY_REDUCE_MOTION = "a11y_reduce_motion"
    private const val KEY_SPOKEN_FOCUS = "a11y_spoken_focus"

    fun load(context: Context): AppearanceConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppearanceConfig(
            theme = AppTheme.fromName(p.getString(KEY_THEME, null)),
            font = FontChoice.fromName(p.getString(KEY_FONT, null)),
            textSize = TextSize.fromName(p.getString(KEY_TEXT_SIZE, null)),
            highContrast = p.getBoolean(KEY_HIGH_CONTRAST, false),
            strongFocus = p.getBoolean(KEY_STRONG_FOCUS, false),
            reduceMotion = p.getBoolean(KEY_REDUCE_MOTION, false),
            spokenFocus = p.getBoolean(KEY_SPOKEN_FOCUS, false),
        )
    }

    fun save(context: Context, config: AppearanceConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, config.theme.name)
            .putString(KEY_FONT, config.font.name)
            .putString(KEY_TEXT_SIZE, config.textSize.name)
            .putBoolean(KEY_HIGH_CONTRAST, config.highContrast)
            .putBoolean(KEY_STRONG_FOCUS, config.strongFocus)
            .putBoolean(KEY_REDUCE_MOTION, config.reduceMotion)
            .putBoolean(KEY_SPOKEN_FOCUS, config.spokenFocus)
            .apply()
    }
}

/**
 * Process-wide appearance state. Home and Settings are separate activities in one process, so a
 * change made in Settings repaints Home immediately instead of waiting for the next resume.
 */
object AppearanceStore {
    var config by mutableStateOf(AppearanceConfig())
        private set

    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        config = AppearancePrefs.load(context)
        LauncherIcon.apply(context, config.theme)
    }

    fun update(context: Context, transform: (AppearanceConfig) -> AppearanceConfig) {
        ensureLoaded(context)
        val next = transform(config)
        val themeChanged = next.theme != config.theme
        config = next
        AppearancePrefs.save(context, next)
        if (themeChanged) LauncherIcon.apply(context, next.theme)
    }
}
