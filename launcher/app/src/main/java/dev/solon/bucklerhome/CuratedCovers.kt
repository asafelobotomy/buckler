package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONObject

data class CuratedCoverEntry(
    val packageName: String,
    val icon: String? = null,
    val poster: String? = null,
    val banner: String? = null,
) {
    /**
     * Art that actually matches [layout]. A copy of the icon is not a banner or a poster, so
     * the tile falls back to the app icon instead of letterboxing the same logo.
     */
    fun urlFor(layout: CoverLayout): String? = when (layout) {
        CoverLayout.BANNER -> banner?.takeIf { it.isNotBlank() && it != icon }
        CoverLayout.POSTER -> poster?.takeIf { it.isNotBlank() && it != icon }
        CoverLayout.ICON -> null
    }
}

object CuratedCovers {
    private const val PREFS = "bucklerhome"
    private const val KEY_CACHE = "curated_covers_json"
    private const val KEY_SOURCE = "curated_covers_source"
    private const val KEY_ASSET_HASH = "curated_covers_asset_hash"
    private const val SOURCE_ASSET = "asset"
    private const val SOURCE_NETWORK = "network"
    private const val ASSET_NAME = "covers_index.json"
    private const val TAG = "CuratedCovers"

    /** The bundled-pack freshness check runs once per process, not on every lookup. */
    @Volatile
    private var assetVersionChecked = false

    @Volatile
    private var parsedRaw: String? = null

    @Volatile
    private var parsed: Map<String, CuratedCoverEntry> = emptyMap()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, packageName: String): CuratedCoverEntry? =
        loadMap(context)[packageName]

    fun urlFor(context: Context, packageName: String, layout: CoverLayout): String? =
        get(context, packageName)?.urlFor(layout)

    /** Parsed curated index (cached until the stored JSON changes). */
    fun entries(context: Context): Map<String, CuratedCoverEntry> = loadMap(context)

    /**
     * Try network pack, then fall back to the APK-bundled asset.
     * Private GitHub raw URLs 404 without auth — asset fallback keeps covers working.
     */
    fun refresh(context: Context): Boolean {
        val headers = CoverAccounts.githubAuthHeaders(context)
        // Prefer Contents API when a token is present (works for private repos).
        val apiUrl =
            "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/contents/covers/index.json?ref=main"
        val response = runCatching {
            if (headers.isNotEmpty()) {
                HttpJson.get(apiUrl, headers = headers)
            } else {
                HttpJson.get(BuildConfig.CURATED_COVERS_URL)
            }
        }.onFailure { Log.w(TAG, "Curated covers refresh failed", it) }.getOrNull()
        if (response != null && response.code in 200..299 && response.body.isNotBlank()) {
            val body = if (headers.isNotEmpty()) {
                decodeGithubContents(response.body) ?: response.body
            } else {
                response.body
            }
            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                prefs(context).edit()
                    .putString(KEY_CACHE, body)
                    .putString(KEY_SOURCE, SOURCE_NETWORK)
                    .apply()
                return true
            }
        }
        // Keep a previously fetched pack rather than clobbering it with the bundled one.
        if (!prefs(context).getString(KEY_CACHE, null).isNullOrBlank()) return false
        return reloadFromAssets(context)
    }

    private fun decodeGithubContents(jsonBody: String): String? {
        val obj = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return null
        val b64 = obj.optString("content").replace("\n", "")
        if (b64.isBlank()) return null
        return runCatching {
            String(Base64.decode(b64, Base64.DEFAULT))
        }.getOrNull()
    }

    fun reloadFromAssets(context: Context): Boolean {
        val asset = readAsset(context) ?: return false
        prefs(context).edit()
            .putString(KEY_CACHE, asset)
            .putString(KEY_SOURCE, SOURCE_ASSET)
            .putInt(KEY_ASSET_HASH, asset.hashCode())
            .apply()
        return true
    }

    fun ensureCached(context: Context) {
        val prefs = prefs(context)
        val cached = prefs.getString(KEY_CACHE, null)
        if (!cached.isNullOrBlank()) {
            // An app update may ship a newer bundled pack; only replace a bundled-origin cache.
            if (!assetVersionChecked) {
                assetVersionChecked = true
                val bundledOrigin = prefs.getString(KEY_SOURCE, SOURCE_ASSET) == SOURCE_ASSET
                val bundled = readAsset(context)
                if (bundledOrigin && bundled != null && bundled.hashCode() != prefs.getInt(KEY_ASSET_HASH, 0)) {
                    reloadFromAssets(context)
                }
            }
            return
        }
        if (reloadFromAssets(context)) return
        // Last resort: network (may 404 on private repos).
        val response = runCatching { HttpJson.get(BuildConfig.CURATED_COVERS_URL) }.getOrNull()
        if (response != null && response.code in 200..299 && response.body.isNotBlank()) {
            prefs(context).edit()
                .putString(KEY_CACHE, response.body)
                    .apply()
        }
    }

    private fun readAsset(context: Context): String? =
        runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().readText()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun loadMap(context: Context): Map<String, CuratedCoverEntry> {
        ensureCached(context)
        val raw = prefs(context).getString(KEY_CACHE, null) ?: return emptyMap()
        if (raw == parsedRaw) return parsed
        return parse(raw).also {
            parsed = it
            parsedRaw = raw
        }
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
