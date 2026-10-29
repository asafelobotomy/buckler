package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persisted per-package cover choice.
 * value is one of:
 * - local:icon
 * - local:banner
 * - curated:<url>
 * - url:<https...>
 * - sgdb:<https...>
 * - igdb:<https...>
 */
object CoverStore {
    private const val PREFS = "bucklerhome"
    private const val KEY = "cover_overrides_json"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun set(context: Context, packageName: String, value: String?) {
        val raw = prefs(context).getString(KEY, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (value.isNullOrBlank()) {
            json.remove(packageName)
        } else {
            json.put(packageName, value)
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun all(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            out[key] = json.optString(key)
        }
        return out
    }

    fun parseUrl(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return when {
            stored.startsWith("local:") -> null
            stored.startsWith("curated:") -> stored.removePrefix("curated:")
            stored.startsWith("url:") -> stored.removePrefix("url:")
            stored.startsWith("sgdb:") -> stored.removePrefix("sgdb:")
            stored.startsWith("igdb:") -> stored.removePrefix("igdb:")
            stored.startsWith("https://") -> stored
            else -> null
        }
    }

    fun isLocalIcon(stored: String?): Boolean = stored == "local:icon"
    fun isLocalBanner(stored: String?): Boolean = stored == "local:banner"
}
