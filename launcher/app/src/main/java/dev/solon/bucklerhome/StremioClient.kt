package dev.solon.bucklerhome

import android.content.Context
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** A Stremio sign-in in progress: [code] is entered at [link] (or the QR code is scanned). */
data class StremioLink(val code: String, val link: String)

/** A video from Stremio's Continue watching: [type] and [videoId] are what add-ons are asked about. */
data class StremioItem(val title: String, val type: String, val videoId: String, val startMs: Long)

/** A stream Buckler can play itself, or why there is none. */
data class StremioStream(val url: String?, val problem: String?)

/**
 * The little of Stremio Multiview needs. Stremio streams nothing itself: the add-ons a user has
 * installed answer with streams. Buckler plays those that are a direct link (as debrid services
 * and some add-ons give); torrents need Stremio's own streaming engine, inside the Stremio app.
 */
object StremioClient {
    private const val API = "https://api.strem.io/api"
    private const val LINK = "https://link.stremio.com/api/v2"

    /** A code to approve on a phone or computer, so nothing is typed on the TV. */
    fun startLink(): StremioLink? = runCatching {
        val res = HttpJson.get("$LINK/create?type=Create")
        val result = JSONObject(res.body).getJSONObject("result")
        StremioLink(result.getString("code"), result.getString("link"))
    }.getOrNull()

    /** The account's key once the code is approved; null while it is waiting. */
    fun finishLink(link: StremioLink): String? = runCatching {
        val res = HttpJson.get("$LINK/read?type=Read&code=${enc(link.code)}")
        JSONObject(res.body).optJSONObject("result")?.optString("authKey")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun api(method: String, body: JSONObject): JSONObject? = runCatching {
        val res = HttpJson.postJson("$API/$method", body.toString())
        JSONObject(res.body).takeIf { !it.has("error") || it.isNull("error") }
    }.getOrNull()

    fun email(authKey: String): String? =
        api("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))?.optJSONObject("result")?.optString("email")

    /** Continue watching: started and not removed, the most recently watched first. */
    fun continueWatching(authKey: String): List<StremioItem> {
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true)
        val items = api("datastoreGet", body)?.optJSONArray("result") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { !it.optBoolean("removed") }
            .mapNotNull { item ->
                val state = item.optJSONObject("state") ?: return@mapNotNull null
                val offset = state.optLong("timeOffset", 0)
                val video = state.optString("video_id").takeIf { it.isNotBlank() && it != "null" }
                if (offset <= 0 && video == null) return@mapNotNull null
                val id = video ?: item.getString("_id")
                Triple(state.optString("lastWatched"), item, StremioItem(title(item.optString("name"), id), item.optString("type"), id, offset))
            }
            .sortedByDescending { it.first }
            .take(20)
            .map { it.third }
    }

    /** "Show" for a film; "Show · S1E2" for an episode ("tt123:1:2"). */
    fun title(name: String, videoId: String): String {
        val parts = videoId.split(':')
        val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val episode = parts.lastOrNull()?.toIntOrNull()
        return if (parts.size >= 3 && season != null && episode != null) "$name · S${season}E$episode" else name
    }

    /** The first direct link any installed add-on gives for the video, or why there is none. */
    fun stream(authKey: String, type: String, videoId: String): StremioStream {
        val body = JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true)
        val addons = api("addonCollectionGet", body)?.optJSONObject("result")?.optJSONArray("addons")
            ?: return StremioStream(null, "Stremio did not answer.")
        var torrentsOnly = false
        for (i in 0 until addons.length()) {
            val addon = addons.getJSONObject(i)
            if (!servesStreams(addon.optJSONObject("manifest") ?: continue, type, videoId)) continue
            val base = addon.optString("transportUrl").removeSuffix("/manifest.json").takeIf { it.startsWith("http") } ?: continue
            val streams = runCatching {
                // Ids keep their colons ("tt123:1:2"), as add-ons expect.
                JSONObject(HttpJson.get("$base/stream/$type/${enc(videoId).replace("%3A", ":")}.json").body).optJSONArray("streams")
            }.getOrNull() ?: continue
            pickDirect(streams)?.let { return StremioStream(it, null) }
            if (streams.length() > 0) torrentsOnly = true
        }
        return StremioStream(
            null,
            if (torrentsOnly) "Only torrent streams: those play in the Stremio app." else "No add-on has a stream for this.",
        )
    }

    /** Whether an add-on's manifest offers streams for [type] and ids like [videoId]. */
    fun servesStreams(manifest: JSONObject, type: String, videoId: String): Boolean {
        val resources = manifest.optJSONArray("resources") ?: return false
        val types = manifest.optJSONArray("types")
        val prefixes = manifest.optJSONArray("idPrefixes")
        for (i in 0 until resources.length()) {
            val r = resources.get(i)
            val (name, rTypes, rPrefixes) = if (r is JSONObject) {
                Triple(r.optString("name"), r.optJSONArray("types") ?: types, r.optJSONArray("idPrefixes") ?: prefixes)
            } else {
                Triple(r.toString(), types, prefixes)
            }
            if (name != "stream") continue
            val typeOk = rTypes == null || (0 until rTypes.length()).any { rTypes.optString(it) == type }
            val idOk = rPrefixes == null || (0 until rPrefixes.length()).any { videoId.startsWith(rPrefixes.optString(it)) }
            if (typeOk && idOk) return true
        }
        return false
    }

    /** A plain http(s) stream with no headers needed; torrents, YouTube ids and archives are left. */
    fun pickDirect(streams: JSONArray): String? = (0 until streams.length()).asSequence()
        .map { streams.getJSONObject(it) }
        .filter { it.optJSONObject("behaviorHints")?.has("proxyHeaders") != true }
        .mapNotNull { it.optString("url").takeIf { u -> u.startsWith("http://") || u.startsWith("https://") } }
        .firstOrNull()

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** Stremio's sign-in, kept with the other Multiview secrets. */
object StremioAccount {
    private const val KEY_AUTH = "stremio_auth_key"
    private const val KEY_EMAIL = "stremio_email"

    fun authKey(context: Context): String? = SecurePrefs.get(context).getString(KEY_AUTH, null)

    fun email(context: Context): String = SecurePrefs.get(context).getString(KEY_EMAIL, null).orEmpty()

    fun set(context: Context, authKey: String?, email: String = "") {
        SecurePrefs.get(context).edit().apply {
            if (authKey == null) remove(KEY_AUTH).remove(KEY_EMAIL) else putString(KEY_AUTH, authKey).putString(KEY_EMAIL, email)
        }.apply()
    }
}
