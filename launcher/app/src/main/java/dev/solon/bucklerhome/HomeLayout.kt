package dev.solon.bucklerhome

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class CoverLayout {
    ICON,
    POSTER,
    BANNER,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): CoverLayout = when (raw?.lowercase()) {
            "poster" -> POSTER
            "banner" -> BANNER
            else -> ICON
        }
    }
}

enum class ShelfType {
    APPS,
    LIBRARY,
    RECENTS,
    WATCH_NEXT,
    SUGGESTED,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): ShelfType = when (raw?.lowercase()) {
            "library" -> LIBRARY
            "recents" -> RECENTS
            "watch_next" -> WATCH_NEXT
            "suggested" -> SUGGESTED
            else -> APPS
        }
    }
}

data class ShelfConfig(
    val id: String,
    val type: ShelfType,
    val title: String,
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
    /** Up Next: when true, [packages] is the allow-list. Each app still contributes at most 3 cards. */
    val restrictSources: Boolean = false,
)

data class HomeLayout(
    val layout: CoverLayout = CoverLayout.BANNER,
    val shelves: List<ShelfConfig> = defaultShelves(),
    val suggestedFeedUrl: String? = null,
    val tileSize: TileSize = TileSize.MEDIUM,
    /** App name under the artwork. Off: the tile is the picture, edge to edge. */
    val showLabels: Boolean = false,
    /** What fills the band under the header. */
    val mainStage: MainStage = MainStage.NEWS,
    /** How tall that band may grow. Medium leaves a full shelf row on a 16:9 screen. */
    val stageSize: StageSize = StageSize.MEDIUM,
) {
    fun toJson(): JSONObject {
        val shelvesArr = JSONArray()
        for (s in shelves) {
            shelvesArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("type", s.type.wire())
                    .put("title", s.title)
                    .put("enabled", s.enabled)
                    .put("packages", JSONArray(s.packages))
                    .put("restrict_sources", s.restrictSources),
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("layout", layout.wire())
            .put("tile_size", tileSize.wire())
            .put("show_labels", showLabels)
            .put("main_stage", mainStage.wire())
            .put("stage_size", stageSize.wire())
            .put("suggested_feed_url", suggestedFeedUrl)
            .put("shelves", shelvesArr)
    }

    /** Effective pinned packages (an empty Apps shelf shows the defaults, so report them). */
    fun appsPackages(): List<String> =
        shelves.firstOrNull { it.type == ShelfType.APPS }?.packages?.ifEmpty { DEFAULT_DOCK_PACKAGES }
            ?: DEFAULT_DOCK_PACKAGES

    fun withPinned(packageName: String): HomeLayout {
        val pins = appsPackages()
        return if (packageName in pins) this else withAppsPackages(pins + packageName)
    }

    fun withoutPinned(packageName: String): HomeLayout =
        withAppsPackages(appsPackages().filterNot { it == packageName })

    /** Swap [packageName] with its neighbour; [delta] is -1 (left) or +1 (right). */
    fun withMovedPinned(packageName: String, delta: Int): HomeLayout {
        val pins = appsPackages().toMutableList()
        val from = pins.indexOf(packageName)
        val to = from + delta
        if (from < 0 || to !in pins.indices) return this
        pins[from] = pins[to].also { pins[to] = pins[from] }
        return withAppsPackages(pins)
    }

    /**
     * Move shelf [id] up (-1) or down (+1) past the next shelf that is actually on screen.
     * Disabled (and, via [visibleIds], empty) shelves are skipped so a press never looks like a no-op.
     */
    fun withMovedShelf(id: String, delta: Int, visibleIds: Set<String>? = null): HomeLayout {
        val list = shelves.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || delta == 0) return this
        val step = if (delta < 0) -1 else 1
        var to = from + step
        while (to in list.indices && (!list[to].enabled || (visibleIds != null && list[to].id !in visibleIds))) {
            to += step
        }
        if (to !in list.indices) return this
        list.add(to, list.removeAt(from))
        return copy(shelves = list)
    }

    /** Up Next allow-list. [restrict] false shows every publisher, three cards each. */
    fun withWatchNextSources(shelfId: String, restrict: Boolean, allowed: List<String>): HomeLayout =
        copy(
            shelves = shelves.map { shelf ->
                if (shelf.id != shelfId || shelf.type != ShelfType.WATCH_NEXT) {
                    shelf
                } else {
                    shelf.copy(restrictSources = restrict, packages = if (restrict) allowed else emptyList())
                }
            },
        )

    fun withAppsPackages(packages: List<String>): HomeLayout =
        copy(
            shelves = shelves.map { s ->
                if (s.type == ShelfType.APPS) s.copy(packages = packages) else s
            },
        )

    companion object {
        const val IMPORT_FILE = "home-layout.json"
        private const val PREFS = "bucklerhome"
        private const val KEY_LAYOUT = "home_layout_json"
        private const val KEY_LAYOUT_CORRUPT = "home_layout_json_corrupt"
        private const val TAG = "HomeLayout"

        fun defaultShelves(appsPackages: List<String> = DEFAULT_DOCK_PACKAGES): List<ShelfConfig> =
            listOf(
                ShelfConfig("watch_next", ShelfType.WATCH_NEXT, "Up Next", enabled = true),
                ShelfConfig("apps", ShelfType.APPS, "Apps", enabled = true, packages = appsPackages),
                ShelfConfig("suggested", ShelfType.SUGGESTED, "Suggested", enabled = true),
                ShelfConfig("recents", ShelfType.RECENTS, "Recent", enabled = false),
                ShelfConfig("library", ShelfType.LIBRARY, "Library", enabled = true),
            )

        fun default(appsPackages: List<String> = DEFAULT_DOCK_PACKAGES): HomeLayout =
            HomeLayout(
                layout = CoverLayout.BANNER,
                shelves = defaultShelves(appsPackages),
            )

        fun fromJson(raw: String): HomeLayout {
            val obj = JSONObject(raw)
            val shelvesArr = obj.optJSONArray("shelves") ?: JSONArray()
            val shelves = buildList {
                for (i in 0 until shelvesArr.length()) {
                    val s = shelvesArr.optJSONObject(i) ?: continue
                    val pkgsArr = s.optJSONArray("packages") ?: JSONArray()
                    val pkgs = buildList {
                        for (j in 0 until pkgsArr.length()) {
                            pkgsArr.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }.distinct()
                    add(
                        ShelfConfig(
                            id = s.optString("id", "shelf_$i"),
                            type = ShelfType.fromWire(s.optString("type")),
                            title = s.optString("title", "Shelf"),
                            enabled = s.optBoolean("enabled", true),
                            packages = pkgs,
                            restrictSources = s.optBoolean("restrict_sources", false),
                        ),
                    )
                }
            }
            val feed = obj.optString("suggested_feed_url", "").ifBlank { null }
            return HomeLayout(
                layout = CoverLayout.fromWire(obj.optString("layout")),
                // Duplicate shelf ids would crash the LazyColumn; keep the first of each.
                shelves = shelves.distinctBy { it.id }.ifEmpty { defaultShelves() },
                suggestedFeedUrl = feed,
                tileSize = TileSize.fromWire(obj.optString("tile_size")),
                showLabels = obj.optBoolean("show_labels", false),
                mainStage = MainStage.fromWire(obj.optString("main_stage").takeIf { it.isNotBlank() }),
                stageSize = StageSize.fromWire(obj.optString("stage_size").takeIf { it.isNotBlank() }),
            )
        }

        fun load(context: Context): HomeLayout {
            importIfPresent(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_LAYOUT, null)
            if (!raw.isNullOrBlank()) {
                return runCatching { fromJson(raw) }.getOrElse { error ->
                    Log.w(TAG, "Stored home layout is corrupt; keeping a copy and resetting", error)
                    prefs.edit().putString(KEY_LAYOUT_CORRUPT, raw).apply()
                    migrateFromDockPrefs(context)
                }
            }
            return migrateFromDockPrefs(context)
        }

        fun save(context: Context, layout: HomeLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAYOUT, layout.toJson().toString())
                .apply()
            // Keep legacy dock pins in sync for older code paths
            DockPrefs.savePins(context, layout.appsPackages())
        }

        fun importFile(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, IMPORT_FILE)

        fun importIfPresent(context: Context): Boolean {
            val file = importFile(context)
            if (!file.isFile) return false
            val parsed = runCatching { fromJson(file.readText()) }
                .onFailure { Log.w(TAG, "Ignoring invalid ${file.name}", it) }
                .getOrNull() ?: return false
            save(context, parsed)
            runCatching { file.delete() }
            return true
        }

        private fun migrateFromDockPrefs(context: Context): HomeLayout {
            val pins = DockPrefs.loadPins(context)
            val layout = default(pins)
            save(context, layout)
            return layout
        }
    }
}
