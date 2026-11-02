package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A font installed from the store: its Regular and Bold files. */
class InstalledFont(val id: String, val label: String, val regular: File, val bold: File)

/**
 * What is installed from bucklerStore, kept in Buckler's files folder on shared storage
 * (`Android/data/dev.solon.bucklerhome/files/store/`) so `buckler store install` can put things
 * there from a computer too:
 * - `themes/<id>.json`: a colour scheme, as the index lists it;
 * - `fonts/<id>/regular.ttf`, `bold.ttf` and `font.json` (its name);
 * - `widgets.json`: `{"added": [...], "removed": [...]}`, widgets changed from the index's default.
 * Icon packs are apps; Android keeps those. What is installed is snapshot state, so Home and
 * Settings repaint when it changes; [version] goes up with every change, for `remember` keys.
 */
object StoreLibrary {
    var version by mutableIntStateOf(0)
        private set

    // Snapshot state: Compose code that reads any of these repaints when they change.
    private var themes by mutableStateOf<Map<String, StoreTheme>>(emptyMap())
    private var fonts by mutableStateOf<Map<String, InstalledFont>>(emptyMap())
    private var added by mutableStateOf<Set<String>>(emptySet())
    private var removed by mutableStateOf<Set<String>>(emptySet())
    @Volatile private var loaded = false
    private const val TAG = "StoreLibrary"

    fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "store")

    private fun themesDir(context: Context) = File(root(context), "themes")
    private fun fontsDir(context: Context) = File(root(context), "fonts")
    private fun widgetsFile(context: Context) = File(root(context), "widgets.json")

    fun ensureLoaded(context: Context) {
        if (!loaded) reload(context)
    }

    /** Read the folder again, for things a computer put there since. */
    @Synchronized
    fun reload(context: Context) {
        loaded = true
        themes = themesDir(context).listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching { StoreIndex.theme(JSONObject(file.readText())) }.getOrNull()?.takeIf { it.id == file.nameWithoutExtension }
        }.associateBy { it.id }
        fonts = fontsDir(context).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val regular = File(dir, "regular.ttf")
            val bold = File(dir, "bold.ttf")
            if (!regular.isFile || !bold.isFile) return@mapNotNull null
            val label = runCatching { JSONObject(File(dir, "font.json").readText()).optString("label") }.getOrNull()
            InstalledFont(dir.name, label?.ifBlank { null } ?: dir.name, regular, bold)
        }.associateBy { it.id }
        val widgets = runCatching { JSONObject(widgetsFile(context).readText()) }.getOrNull()
        added = widgets?.optJSONArray("added").strings()
        removed = widgets?.optJSONArray("removed").strings()
        version++
    }

    private fun JSONArray?.strings(): Set<String> =
        if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }.toSet()

    // Colour schemes and fonts, as Appearance uses them.

    fun installedThemes(): List<StoreTheme> = themes.values.sortedBy { it.label.lowercase() }
    fun installedFonts(): List<InstalledFont> = fonts.values.sortedBy { it.label.lowercase() }
    fun palette(id: String): Palette? = themes[id]?.palette
    fun hasTheme(id: String) = id in themes
    fun hasFont(id: String) = id in fonts

    @OptIn(ExperimentalTextApi::class)
    fun fontFamily(id: String): FontFamily? = fonts[id]?.let { font ->
        runCatching { FontFamily(Font(font.regular, FontWeight.Normal), Font(font.bold, FontWeight.Bold)) }.getOrNull()
    }

    fun typeface(id: String): Typeface? = fonts[id]?.let { runCatching { Typeface.createFromFile(it.regular) }.getOrNull() }

    fun installTheme(context: Context, theme: StoreTheme) {
        val dir = themesDir(context).also { it.mkdirs() }
        File(dir, "${theme.id}.json").writeText(themeJson(theme).toString(2))
        reload(context)
    }

    /** Download both files, refusing any whose SHA-256 is not the index's. Call off the main thread. */
    fun installFont(context: Context, font: StoreFont): Boolean {
        val dir = File(fontsDir(context), font.id).also { it.mkdirs() }
        val ok = download(font.regular, File(dir, "regular.ttf")) && download(font.bold, File(dir, "bold.ttf"))
        if (ok) {
            File(dir, "font.json").writeText(JSONObject().put("id", font.id).put("label", font.label).toString())
        } else {
            dir.deleteRecursively()
        }
        reload(context)
        return ok
    }

    fun removeTheme(context: Context, id: String) {
        File(themesDir(context), "$id.json").delete()
        if (AppearanceStore.config.storeTheme == id) AppearanceStore.update(context) { it.copy(storeTheme = null) }
        reload(context)
    }

    fun removeFont(context: Context, id: String) {
        if (AppearanceStore.config.storeFont == id) AppearanceStore.update(context) { it.copy(storeFont = null) }
        File(fontsDir(context), id).deleteRecursively()
        reload(context)
    }

    // Widgets: Buckler's own, offered on Home once added.

    /** Whether [stage] is on offer: added or removed by hand, else the index's default (added when unlisted). */
    fun widgetAdded(stage: MainStage, catalog: StoreCatalog? = null): Boolean {
        val id = stage.wire()
        return when {
            id in removed -> false
            id in added -> true
            else -> catalog?.widgets?.firstOrNull { it.id == id }?.default ?: true
        }
    }

    /** The Main Stage widgets to offer: those added, and [current] always, so the one on screen stays choosable. */
    fun stages(context: Context, current: MainStage? = null): List<MainStage> {
        ensureLoaded(context)
        val catalog = StoreIndex.cached(context)
        return MainStage.entries.filter { it == current || widgetAdded(it, catalog) }
    }

    fun setWidgetAdded(context: Context, stage: MainStage, on: Boolean) {
        ensureLoaded(context)
        val id = stage.wire()
        val nextAdded = if (on) added + id else added - id
        val nextRemoved = if (on) removed - id else removed + id
        root(context).mkdirs()
        widgetsFile(context).writeText(
            JSONObject()
                .put("added", JSONArray(nextAdded.sorted()))
                .put("removed", JSONArray(nextRemoved.sorted()))
                .toString(2),
        )
        reload(context)
    }

    // Downloads.

    /** Stream [file] to [dest] through a temporary file, kept only when its SHA-256 matches. */
    fun download(file: StoreFile, dest: File): Boolean {
        val part = File(dest.parentFile, dest.name + ".part")
        val code = runCatching { HttpJson.downloadTo(file.url, part) }
            .onFailure { Log.w(TAG, "Download failed: ${file.url}", it) }.getOrDefault(-1)
        if (code !in 200..299 || sha256(part) != file.sha256) {
            Log.w(TAG, "Refusing ${file.url}: HTTP $code or checksum mismatch")
            part.delete()
            return false
        }
        return part.renameTo(dest) || run { part.delete(); false }
    }

    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun themeJson(theme: StoreTheme): JSONObject {
        val p = theme.palette
        fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
        val colors = JSONObject()
            .put("bgTop", hex(p.bgTop)).put("bgMid", hex(p.bgMid)).put("bgBottom", hex(p.bgBottom))
            .put("brand", hex(p.brand)).put("onBrand", hex(p.onBrand)).put("text", hex(p.text))
            .put("muted", hex(p.muted)).put("hint", hex(p.hint)).put("section", hex(p.section))
            .put("tile", hex(p.tile)).put("footer", hex(p.footer)).put("border", hex(p.border)).put("ring", hex(p.ring))
        return JSONObject().put("id", theme.id).put("label", theme.label).put("about", theme.about)
            .put("dark", p.dark).put("colors", colors)
    }
}
