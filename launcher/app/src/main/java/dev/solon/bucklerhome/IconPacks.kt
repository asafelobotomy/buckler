package dev.solon.bucklerhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.DisplayMetrics
import android.util.Xml
import androidx.compose.ui.graphics.toArgb
import org.xmlpull.v1.XmlPullParser

/** An installed icon pack: an app that ships icons for other apps, mapped by package and activity. */
data class IconPack(val packageName: String, val label: String)

/**
 * Icon packs in the common launcher format (ADW / Nova): the pack's `appfilter.xml` maps
 * `ComponentInfo{package/activity}` to one of its drawables. Any such pack works, e.g. Lawnicons
 * or Arcticons (`buckler launcher iconpack install arcticons`). A TV app the pack does not list
 * (often a different package from the phone app) can still match a pack icon named after it, such
 * as `netflix`. The rest get an icon drawn in the pack's style ([PackStyleIcons]) or keep their own.
 * [IconPackStore] holds which pack is chosen and whether it takes the theme colour.
 */
object IconPacks {
    /** The intents icon packs announce themselves with; one is enough. */
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
    )

    private class Mapping(val version: Long, val byComponent: Map<String, String>, val byPackage: Map<String, String>)

    /** Room around a flat pack icon (Arcticons), matching the margin layered icons have. */
    private const val MARGIN = 0.16f

    @Volatile private var cache: Pair<String, Mapping>? = null

    fun installed(context: Context): List<IconPack> {
        val pm = context.packageManager
        return ACTIONS.flatMap { action -> pm.queryIntentActivities(Intent(action), 0) }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { IconPack(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** A pack is chosen and still installed and readable. */
    fun isActive(context: Context): Boolean = IconPackStore.pack(context)?.let { mapping(context, it) != null } == true

    fun installedPackages(context: Context): Set<String> = installed(context).mapTo(HashSet()) { it.packageName }

    /**
     * The chosen pack's icon for an app, sized for a TV, or null to keep the app's own: no pack
     * chosen, the pack is gone, or it has nothing for this app.
     */
    fun iconFor(context: Context, component: ComponentName, label: String): Drawable? {
        val pack = IconPackStore.pack(context) ?: return null
        val mapping = mapping(context, pack) ?: return null
        val res = resources(context, pack) ?: return null
        val id = drawableId(mapping, res, pack, component.packageName, component.flattenToString(), label) ?: return null
        val icon = runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXHIGH, null) }.getOrNull()
            ?: return null
        val tint = IconPackStore.tint(context)
        val adaptive = icon as? AdaptiveIconDrawable
        if (tint == null) return PackIconDrawable(icon, if (adaptive != null) 0f else MARGIN, themed = false)
        // A layered icon (Lawnicons) would tint to a solid square: paint only its symbol, which
        // already sits inside the layer's margin, on the tile.
        val symbol = (adaptive?.foreground ?: icon).mutate().apply { setTint(tint) }
        return PackIconDrawable(symbol, if (adaptive != null) 0f else MARGIN, themed = true)
    }

    /** How many of [apps] (package to label) the chosen [pack] has an icon for, by package or by name. */
    fun coverage(context: Context, pack: String, apps: Map<String, String>): Int {
        val mapping = mapping(context, pack) ?: return 0
        val res = resources(context, pack) ?: return 0
        return apps.count { (pkg, label) -> drawableId(mapping, res, pack, pkg, null, label) != null }
    }

    @Suppress("DiscouragedApi") // Icon packs are addressed by drawable name; there is no R class to use.
    private fun drawableId(mapping: Mapping, res: Resources, pack: String, pkg: String, component: String?, label: String): Int? {
        val listed = component?.let { mapping.byComponent[it] } ?: mapping.byPackage[pkg]
        val names = listOfNotNull(listed) + nameCandidates(label)
        return names.firstNotNullOfOrNull { name -> res.getIdentifier(name, "drawable", pack).takeIf { it != 0 } }
    }

    /**
     * Pack drawable names an app could go by: its name in lower case, words joined by `_` or run
     * together ("Live Channels": `live_channels`, `livechannels`). Only whole names, so "Sonic"
     * does not borrow some other app's icon that merely contains the word.
     */
    internal fun nameCandidates(label: String): List<String> {
        val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.joinToString("").length < 3) return emptyList()
        return listOf(words.joinToString("_"), words.joinToString("")).distinct()
    }

    private fun resources(context: Context, pack: String): Resources? =
        runCatching { context.packageManager.getResourcesForApplication(pack) }.getOrNull()

    /** Read once per pack version; packs hold tens of thousands of entries. */
    private fun mapping(context: Context, pack: String): Mapping? {
        val version = runCatching { context.packageManager.getPackageInfo(pack, 0).lastUpdateTime }.getOrNull()
            ?: return null
        cache?.let { (cached, m) -> if (cached == pack && m.version == version) return m }
        val parsed = parse(context, pack, version) ?: return null
        cache = pack to parsed
        return parsed
    }

    private fun parse(context: Context, pack: String, version: Long): Mapping? {
        val res = resources(context, pack) ?: return null
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        fun read(parser: XmlPullParser) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                val raw = parser.getAttributeValue(null, "component") ?: continue
                val drawable = parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() } ?: continue
                val component = raw.removePrefix("ComponentInfo{").removeSuffix("}")
                val name = ComponentName.unflattenFromString(component) ?: continue
                byComponent.putIfAbsent(name.flattenToString(), drawable)
                byPackage.putIfAbsent(name.packageName, drawable)
            }
        }
        @Suppress("DiscouragedApi")
        val xmlId = res.getIdentifier("appfilter", "xml", pack)
        val ok = runCatching {
            if (xmlId != 0) {
                res.getXml(xmlId).use { read(it) }
            } else {
                res.assets.open("appfilter.xml").use { input ->
                    read(Xml.newPullParser().apply { setInput(input, null) })
                }
            }
        }.isSuccess
        return if (ok && byComponent.isNotEmpty()) Mapping(version, byComponent, byPackage) else null
    }
}

/**
 * An icon from a pack, with room around it. [themed] icons are drawn in the theme's text colour,
 * which the tile swaps for the focus colour's text so a focused tile keeps its symbol visible.
 */
class PackIconDrawable(icon: Drawable, margin: Float, val themed: Boolean) : InsetDrawable(icon, margin)

/** Which icon pack Home uses (none: each app's own icon) and whether its icons take the theme's text colour. */
object IconPackStore {
    private const val PREFS = "icon_pack"
    private const val KEY_PACK = "pack"
    private const val KEY_TINT = "tint"
    private const val KEY_IN_LIBRARY = "in_library"
    private const val KEY_FILL_GAPS = "fill_gaps"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pack(context: Context): String? = prefs(context).getString(KEY_PACK, null)?.takeIf { it.isNotBlank() }

    fun setPack(context: Context, pack: String?) = prefs(context).edit().putString(KEY_PACK, pack.orEmpty()).apply()

    /** Whether installed icon packs show in the Library (off: they are not apps to open). */
    fun packsInLibrary(context: Context): Boolean = prefs(context).getBoolean(KEY_IN_LIBRARY, false)

    fun setPacksInLibrary(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_IN_LIBRARY, on).apply()

    /** Draw apps the pack has no icon for in its style ([PackStyleIcons]); on unless switched off. */
    fun fillGaps(context: Context): Boolean = prefs(context).getBoolean(KEY_FILL_GAPS, true)

    fun setFillGaps(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_FILL_GAPS, on).apply()

    fun tinted(context: Context): Boolean = prefs(context).getBoolean(KEY_TINT, false)

    fun setTinted(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_TINT, on).apply()

    /** The colour to paint pack icons, or null to keep the pack's own colours. */
    fun tint(context: Context): Int? {
        if (!tinted(context)) return null
        AppearanceStore.ensureLoaded(context)
        return Palettes.resolve(AppearanceStore.config).text.toArgb()
    }
}
