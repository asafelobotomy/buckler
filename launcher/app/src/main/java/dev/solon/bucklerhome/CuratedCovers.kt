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
    private const val ASSET_NAME = "covers_index.json"

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

    /**
     * Try network pack, then fall back to the APK-bundled asset.
     * Private GitHub raw URLs 404 without auth — asset fallback keeps covers working.
     */
    fun refresh(context: Context): Boolean {
        val headers = CoverAccounts.githubAuthHeaders(context)
        // Prefer Contents API when a token is present (works for private repos).
        val apiUrl =
            "https://api.github.com/repos/asafelobotomy/buckler/contents/covers/index.json?ref=main"
        val response = runCatching {
            if (headers.isNotEmpty()) {
                HttpJson.get(apiUrl, headers = headers)
            } else {
                HttpJson.get(BuildConfig.CURATED_COVERS_URL)
            }
        }.getOrNull()
        if (response != null && response.code in 200..299 && response.body.isNotBlank()) {
            val body = if (headers.isNotEmpty()) {
                decodeGithubContents(response.body) ?: response.body
            } else {
                response.body
            }
            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                prefs(context).edit()
                    .putString(KEY_CACHE, body)
                    .putLong(KEY_FETCHED, System.currentTimeMillis())
                    .apply()
                return true
            }
        }
        return reloadFromAssets(context)
    }

    private fun decodeGithubContents(jsonBody: String): String? {
        val obj = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return null
        val b64 = obj.optString("content").replace("\n", "")
        if (b64.isBlank()) return null
        return runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        }.getOrNull()
    }

    fun reloadFromAssets(context: Context): Boolean {
        val asset = readAsset(context) ?: return false
        prefs(context).edit()
            .putString(KEY_CACHE, asset)
            .putLong(KEY_FETCHED, System.currentTimeMillis())
            .apply()
        return true
    }

    fun ensureCached(context: Context) {
        val cached = prefs(context).getString(KEY_CACHE, null)
        if (!cached.isNullOrBlank()) return
        if (reloadFromAssets(context)) return
        // Last resort: network (may 404 on private repos).
        val response = runCatching { HttpJson.get(BuildConfig.CURATED_COVERS_URL) }.getOrNull()
        if (response != null && response.code in 200..299 && response.body.isNotBlank()) {
            prefs(context).edit()
                .putString(KEY_CACHE, response.body)
                .putLong(KEY_FETCHED, System.currentTimeMillis())
                .apply()
        }
    }

    fun lastFetched(context: Context): Long =
        prefs(context).getLong(KEY_FETCHED, 0L)

    private fun readAsset(context: Context): String? =
        runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().readText()
        }.getOrNull()?.takeIf { it.isNotBlank() }

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
