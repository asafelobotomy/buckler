package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class CuratedCoverEntry(
    val packageName: String,
    val icon: String? = null,
    val poster: String? = null,
    val banner: String? = null,
)

object CuratedCovers {
    private const val PREFS = "bucklerhome"
    private const val KEY_CACHE = "curated_covers_json"
    private const val KEY_FETCHED = "curated_covers_fetched_at"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, packageName: String): CuratedCoverEntry? =
        loadMap(context)[packageName]

    fun urlFor(context: Context, packageName: String, layout: CoverLayout): String? {
        val entry = get(context, packageName) ?: return null
        return when (layout) {
            CoverLayout.BANNER -> entry.banner ?: entry.poster ?: entry.icon
            CoverLayout.POSTER -> entry.poster ?: entry.banner ?: entry.icon
            CoverLayout.ICON -> entry.icon ?: entry.poster ?: entry.banner
        }
    }

    fun refresh(context: Context): Boolean {
        val response = runCatching { HttpJson.get(BuildConfig.CURATED_COVERS_URL) }.getOrNull()
            ?: return false
        if (response.code !in 200..299) return false
        prefs(context).edit()
            .putString(KEY_CACHE, response.body)
            .putLong(KEY_FETCHED, System.currentTimeMillis())
            .apply()
        return true
    }

    fun ensureCached(context: Context) {
        val cached = prefs(context).getString(KEY_CACHE, null)
        if (cached.isNullOrBlank()) {
            // Seed from bundled asset if present; else try network once.
            val asset = runCatching {
                context.assets.open("covers_index.json").bufferedReader().readText()
            }.getOrNull()
            if (!asset.isNullOrBlank()) {
                prefs(context).edit().putString(KEY_CACHE, asset).apply()
            } else {
                refresh(context)
            }
        }
    }

    fun lastFetched(context: Context): Long =
        prefs(context).getLong(KEY_FETCHED, 0L)

    private fun loadMap(context: Context): Map<String, CuratedCoverEntry> {
        ensureCached(context)
        val raw = prefs(context).getString(KEY_CACHE, null) ?: return emptyMap()
        return parse(raw)
    }

    fun parse(raw: String): Map<String, CuratedCoverEntry> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val apps = root.optJSONObject("apps") ?: root
        val out = mutableMapOf<String, CuratedCoverEntry>()
        apps.keys().forEach { pkg ->
            val obj = apps.optJSONObject(pkg) ?: return@forEach
            out[pkg] = CuratedCoverEntry(
                packageName = pkg,
                icon = obj.optString("icon").takeIf { it.isNotBlank() },
                poster = obj.optString("poster").takeIf { it.isNotBlank() },
                banner = obj.optString("banner").takeIf { it.isNotBlank() },
            )
        }
        return out
    }
}
