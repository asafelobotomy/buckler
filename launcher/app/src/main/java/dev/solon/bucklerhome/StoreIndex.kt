package dev.solon.bucklerhome

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** A file the store downloads, pinned by its SHA-256. */
data class StoreFile(val url: String, val sha256: String, val size: Long)

sealed interface StoreItem {
    val id: String
    val label: String
    val about: String

    /** The store's own count of this item's versions, from 1; it goes up whenever the item changes. */
    val revision: Int

    /** What the latest [revision] brought, in a line for people; blank at revision 1. */
    val changes: String
}

/**
 * An item whose code or colours ship in Buckler (a widget, a screensaver, a built-in scheme): the
 * store only adds or removes it. [requires] is the Buckler version that carries its current
 * [StoreItem.revision]; [since] the first that had it at all.
 */
sealed interface StoreBuiltIn : StoreItem {
    val default: Boolean
    val since: String
    val requires: String
}

/** One of Buckler's own Main Stage widgets ([stage]); its code ships in the app, the store adds or removes it. */
data class StoreWidget(
    override val id: String,
    override val label: String,
    override val about: String,
    override val default: Boolean,
    override val since: String,
    override val requires: String = since,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreBuiltIn {
    val stage: MainStage? get() = MainStage.entries.firstOrNull { it.wire() == id }
}

/** One of Buckler's own screensavers ([mode]); its code ships in the app, the store adds or removes it. */
data class StoreScreensaver(
    override val id: String,
    override val label: String,
    override val about: String,
    override val default: Boolean,
    override val since: String,
    override val requires: String = since,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreBuiltIn {
    val mode: ScreensaverMode? get() = ScreensaverMode.entries.firstOrNull { it.wire() == id }
}

/** One of Buckler's own colour schemes ([theme]); its colours ship in the app, the store adds or removes it. */
data class StoreScheme(
    override val id: String,
    override val label: String,
    override val about: String,
    override val default: Boolean,
    override val since: String,
    override val requires: String = since,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreBuiltIn {
    val theme: AppTheme? get() = AppTheme.entries.firstOrNull { it.wire() == id }
}

data class StoreTheme(
    override val id: String,
    override val label: String,
    override val about: String,
    val palette: Palette,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreItem

data class StoreFont(
    override val id: String,
    override val label: String,
    override val about: String,
    val regular: StoreFile,
    val bold: StoreFile,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreItem

data class StoreIconPack(
    override val id: String,
    override val label: String,
    override val about: String,
    val packageName: String,
    val version: String,
    val apk: StoreFile,
    val license: String,
    override val revision: Int = 1,
    override val changes: String = "",
) : StoreItem

data class StoreCatalog(
    val widgets: List<StoreWidget> = emptyList(),
    val screensavers: List<StoreScreensaver> = emptyList(),
    val schemes: List<StoreScheme> = emptyList(),
    val themes: List<StoreTheme> = emptyList(),
    val fonts: List<StoreFont> = emptyList(),
    val iconPacks: List<StoreIconPack> = emptyList(),
) {
    fun theme(id: String) = themes.firstOrNull { it.id == id }

    /** Every widget, screensaver and built-in scheme, for the update checks. */
    val builtIns: List<StoreBuiltIn> get() = widgets + screensavers + schemes
    fun font(id: String) = fonts.firstOrNull { it.id == id }
}

/**
 * bucklerStore's index (github.com/asafelobotomy/bucklerStore, index.json): the widgets, colour
 * schemes, fonts and icon packs it offers. Fetched when the store page opens, kept for next time,
 * and bundled in the APK as `store_index.json` so the store works offline from the first start.
 */
object StoreIndex {
    private const val PREFS = "buckler_store"
    private const val KEY_CACHE = "index_json"
    private const val KEY_FETCHED_AT = "index_fetched_at"
    private const val STALE_MS = 24 * 3_600_000L
    private const val ASSET = "store_index.json"
    private const val SCHEMA = 1
    private const val TAG = "StoreIndex"

    @Volatile private var parsed: Pair<String, StoreCatalog>? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last index fetched, or the bundled one. */
    fun cached(context: Context): StoreCatalog {
        val raw = prefs(context).getString(KEY_CACHE, null)
            ?: runCatching { context.assets.open(ASSET).bufferedReader().readText() }.getOrNull()
            ?: return StoreCatalog()
        parsed?.let { (text, catalog) -> if (text == raw) return catalog }
        return parse(raw).also { parsed = raw to it }
    }

    /** Fetch the current index; on failure keep the one already held. Call off the main thread. */
    fun refresh(context: Context): Boolean {
        val response = runCatching { HttpJson.get(BuildConfig.STORE_INDEX_URL) }
            .onFailure { Log.w(TAG, "Store index refresh failed", it) }.getOrNull()
        val body = response?.takeIf { it.code in 200..299 }?.body ?: return false
        if (parse(body).let { it.widgets.isEmpty() && it.themes.isEmpty() && it.fonts.isEmpty() }) return false
        prefs(context).edit().putString(KEY_CACHE, body).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
        return true
    }

    /**
     * [refresh] when the index held is over a day old (or was never fetched), so news of an update
     * reaches Home without anyone opening the store. One small, cached file, at most daily.
     */
    fun refreshIfStale(context: Context) {
        val at = prefs(context).getLong(KEY_FETCHED_AT, 0L)
        if (System.currentTimeMillis() - at < STALE_MS) return
        if (!refresh(context)) prefs(context).edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis() - STALE_MS + 3_600_000L).apply()
    }

    /** Anything malformed is left out rather than failing the whole index. */
    fun parse(raw: String): StoreCatalog {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return StoreCatalog()
        // A newer index format may mean things this Buckler cannot read: offer nothing until updated.
        if (root.optInt("schema", 0) != SCHEMA) return StoreCatalog()
        return StoreCatalog(
            widgets = root.optJSONArray("widgets").objects().mapNotNull(::widget),
            screensavers = root.optJSONArray("screensavers").objects().mapNotNull(::screensaver),
            schemes = root.optJSONArray("schemes").objects().mapNotNull(::scheme),
            themes = root.optJSONArray("themes").objects().mapNotNull(::theme),
            fonts = root.optJSONArray("fonts").objects().mapNotNull(::font),
            iconPacks = root.optJSONArray("iconpacks").objects().mapNotNull(::iconPack),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private val ID = Regex("^[a-z0-9][a-z0-9_-]{1,39}$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    private fun JSONObject.id(): String? = optString("id").takeIf { ID.matches(it) }

    /** An index from before revisions, or a file saved before them, counts as revision 1. */
    fun JSONObject.revision(): Int = optInt("revision", 1).coerceAtLeast(1)

    private fun widget(o: JSONObject): StoreWidget? = StoreWidget(
        id = o.id() ?: return null,
        label = o.optString("label").ifBlank { return null },
        about = o.optString("about"),
        default = o.optBoolean("default", false),
        since = o.optString("since", "0"),
        requires = o.optString("requires").ifBlank { o.optString("since", "0") },
        revision = o.revision(),
        changes = o.optString("changes"),
    )

    private fun screensaver(o: JSONObject): StoreScreensaver? = StoreScreensaver(
        id = o.id() ?: return null,
        label = o.optString("label").ifBlank { return null },
        about = o.optString("about"),
        default = o.optBoolean("default", false),
        since = o.optString("since", "0"),
        requires = o.optString("requires").ifBlank { o.optString("since", "0") },
        revision = o.revision(),
        changes = o.optString("changes"),
    )

    private fun scheme(o: JSONObject): StoreScheme? = StoreScheme(
        id = o.id() ?: return null,
        label = o.optString("label").ifBlank { return null },
        about = o.optString("about"),
        default = o.optBoolean("default", false),
        since = o.optString("since", "0"),
        requires = o.optString("requires").ifBlank { o.optString("since", "0") },
        revision = o.revision(),
        changes = o.optString("changes"),
    )

    fun theme(o: JSONObject): StoreTheme? {
        val c = o.optJSONObject("colors") ?: return null
        fun color(key: String): Color? = parseColor(c.optString(key))
        val palette = Palette(
            dark = o.optBoolean("dark", true),
            bgTop = color("bgTop") ?: return null,
            bgMid = color("bgMid") ?: return null,
            bgBottom = color("bgBottom") ?: return null,
            brand = color("brand") ?: return null,
            onBrand = color("onBrand") ?: return null,
            text = color("text") ?: return null,
            muted = color("muted") ?: return null,
            hint = color("hint") ?: return null,
            section = color("section") ?: return null,
            tile = color("tile") ?: return null,
            footer = color("footer") ?: return null,
            border = color("border") ?: return null,
            ring = color("ring") ?: return null,
        )
        return StoreTheme(
            o.id() ?: return null, o.optString("label").ifBlank { return null }, o.optString("about"), palette,
            revision = o.revision(), changes = o.optString("changes"),
        )
    }

    private fun font(o: JSONObject): StoreFont? {
        val files = o.optJSONObject("files") ?: return null
        return StoreFont(
            id = o.id() ?: return null,
            label = o.optString("label").ifBlank { return null },
            about = o.optString("about"),
            regular = file(files.optJSONObject("regular")) ?: return null,
            bold = file(files.optJSONObject("bold")) ?: return null,
            revision = o.revision(),
            changes = o.optString("changes"),
        )
    }

    private fun iconPack(o: JSONObject): StoreIconPack? = StoreIconPack(
        id = o.id() ?: return null,
        label = o.optString("label").ifBlank { return null },
        about = o.optString("about"),
        packageName = o.optString("package").ifBlank { return null },
        version = o.optString("version"),
        apk = file(o) ?: return null,
        license = o.optString("license"),
        revision = o.revision(),
        changes = o.optString("changes"),
    )

    private fun file(o: JSONObject?): StoreFile? {
        if (o == null) return null
        val url = o.optString("url").takeIf { it.startsWith("https://") } ?: return null
        val sha = o.optString("sha256").lowercase().takeIf { SHA.matches(it) } ?: return null
        return StoreFile(url, sha, o.optLong("size", 0))
    }

    /** `#RRGGBB`, opaque. */
    fun parseColor(raw: String): Color? {
        if (raw.length != 7 || raw[0] != '#') return null
        val rgb = raw.substring(1).toLongOrNull(16) ?: return null
        return Color(0xFF000000 or rgb)
    }
}
