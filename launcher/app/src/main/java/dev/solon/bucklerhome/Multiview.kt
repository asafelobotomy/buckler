package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Where a Multiview tile's video comes from. */
enum class MultiviewKind(val label: String) {
    JELLYFIN("Jellyfin"),
    IPTV("IPTV"),
    CAMERA("Camera"),
}

/**
 * One tile on the Multiview stage. [ref] is the Jellyfin item id, or the stream's address for IPTV
 * and cameras; [startMs] is where a Jellyfin video was left off.
 */
data class MultiviewTile(val kind: MultiviewKind, val title: String, val ref: String, val startMs: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("title", title).put("ref", ref).put("start", startMs)

    companion object {
        fun fromJson(json: JSONObject): MultiviewTile? {
            val kind = MultiviewKind.entries.firstOrNull { it.name == json.optString("kind") } ?: return null
            val ref = json.optString("ref").ifBlank { return null }
            return MultiviewTile(kind, json.optString("title").ifBlank { kind.label }, ref, json.optLong("start", 0))
        }
    }
}

/** A camera, or any stream address, added by hand: name and address (rtsp://, http(s)://). */
data class MultiviewCamera(val name: String, val url: String)

/** Jellyfin sign-in: the server, its token and the user it belongs to. */
data class JellyfinAccount(val server: String, val token: String, val userId: String, val userName: String)

/**
 * Multiview's tiles and sources, kept encrypted (playlist and camera addresses often carry a
 * password). Held in Compose state too, so Home redraws as Settings changes them: both run in
 * the same process.
 */
object MultiviewStore {
    const val TILES = 3
    private const val KEY_TILES = "multiview_tiles"
    private const val KEY_PLAYLIST = "multiview_m3u"
    private const val KEY_CAMERAS = "multiview_cameras"
    private const val KEY_JF_SERVER = "jellyfin_server"
    private const val KEY_JF_TOKEN = "jellyfin_token"
    private const val KEY_JF_USER = "jellyfin_user_id"
    private const val KEY_JF_NAME = "jellyfin_user_name"
    private const val KEY_DEVICE = "jellyfin_device_id"

    var tiles by mutableStateOf<List<MultiviewTile?>>(List(TILES) { null })
        private set
    private var loaded = false

    fun load(context: Context): List<MultiviewTile?> {
        if (loaded) return tiles
        val raw = SecurePrefs.get(context).getString(KEY_TILES, null)
        tiles = List(TILES) { i ->
            runCatching { JSONArray(raw).optJSONObject(i)?.let(MultiviewTile::fromJson) }.getOrNull()
        }
        loaded = true
        return tiles
    }

    fun setTile(context: Context, index: Int, tile: MultiviewTile?) {
        val next = load(context).toMutableList().also { it[index] = tile }
        val json = JSONArray(next.map { it?.toJson() ?: JSONObject.NULL })
        SecurePrefs.get(context).edit().putString(KEY_TILES, json.toString()).apply()
        tiles = next
    }

    fun playlist(context: Context): String = SecurePrefs.get(context).getString(KEY_PLAYLIST, null).orEmpty()

    fun setPlaylist(context: Context, url: String) = SecurePrefs.get(context).edit().putString(KEY_PLAYLIST, url.trim()).apply()

    fun cameras(context: Context): List<MultiviewCamera> = runCatching {
        val arr = JSONArray(SecurePrefs.get(context).getString(KEY_CAMERAS, "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { MultiviewCamera(it.getString("name"), it.getString("url")) } }
    }.getOrDefault(emptyList())

    fun setCameras(context: Context, cameras: List<MultiviewCamera>) {
        val json = JSONArray(cameras.map { JSONObject().put("name", it.name).put("url", it.url) })
        SecurePrefs.get(context).edit().putString(KEY_CAMERAS, json.toString()).apply()
    }

    fun jellyfin(context: Context): JellyfinAccount? {
        val p = SecurePrefs.get(context)
        val server = p.getString(KEY_JF_SERVER, null) ?: return null
        val token = p.getString(KEY_JF_TOKEN, null) ?: return null
        return JellyfinAccount(server, token, p.getString(KEY_JF_USER, null).orEmpty(), p.getString(KEY_JF_NAME, null).orEmpty())
    }

    fun setJellyfin(context: Context, account: JellyfinAccount?) {
        SecurePrefs.get(context).edit().apply {
            if (account == null) {
                listOf(KEY_JF_SERVER, KEY_JF_TOKEN, KEY_JF_USER, KEY_JF_NAME).forEach(::remove)
            } else {
                putString(KEY_JF_SERVER, account.server)
                putString(KEY_JF_TOKEN, account.token)
                putString(KEY_JF_USER, account.userId)
                putString(KEY_JF_NAME, account.userName)
            }
        }.apply()
    }

    /** Jellyfin wants a stable id per device. */
    fun deviceId(context: Context): String {
        val p = SecurePrefs.get(context)
        return p.getString(KEY_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString(KEY_DEVICE, it).apply() }
    }

    /** The address a tile plays: Jellyfin's direct stream (no transcoding, so three are light on the server). */
    fun streamUrl(context: Context, tile: MultiviewTile): String? = when (tile.kind) {
        MultiviewKind.JELLYFIN -> jellyfin(context)?.let { JellyfinClient.streamUrl(it, tile.ref) }
        MultiviewKind.IPTV, MultiviewKind.CAMERA -> tile.ref
    }
}
