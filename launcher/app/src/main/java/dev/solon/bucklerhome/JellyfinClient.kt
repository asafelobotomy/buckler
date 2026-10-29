package dev.solon.bucklerhome

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import org.json.JSONObject

/** A Jellyfin video that can go on a Multiview tile. */
data class JellyfinItem(val id: String, val title: String, val startMs: Long)

/** A Jellyfin server found on the home network. */
data class JellyfinServer(val name: String, val address: String)

/** Quick Connect in progress: [code] is shown on the TV and approved in another Jellyfin app. */
data class QuickConnect(val server: String, val code: String, val secret: String)

/**
 * The little of Jellyfin's API Multiview needs: finding servers, signing in (Quick Connect, so no
 * password is typed on the TV, or name and password), listing videos, and their direct streams.
 */
object JellyfinClient {
    private const val DISCOVERY_PORT = 7359

    private fun auth(context: Context, token: String? = null): Map<String, String> {
        val value = "MediaBrowser Client=\"Buckler\", Device=\"SHIELD\", " +
            "DeviceId=\"${MultiviewStore.deviceId(context)}\", Version=\"${BuildConfig.VERSION_NAME}\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")
        // Newer servers read Authorization, older ones X-Emby-Authorization.
        return mapOf("Authorization" to value, "X-Emby-Authorization" to value)
    }

    /** "192.168.1.5:8096" → "http://192.168.1.5:8096", without a trailing slash. */
    fun normalise(address: String): String {
        val trimmed = address.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    /** Servers answering Jellyfin's discovery broadcast within [timeoutMs]. */
    fun discover(timeoutMs: Int = 2_000): List<JellyfinServer> = runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val ask = "who is JellyfinServer?".toByteArray()
            socket.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val found = ArrayList<JellyfinServer>()
            val buffer = ByteArray(2048)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val json = JSONObject(String(packet.data, 0, packet.length))
                found += JellyfinServer(json.optString("Name", "Jellyfin"), json.optString("Address").trimEnd('/'))
            }
            found.distinctBy { it.address }
        }
    }.getOrDefault(emptyList())

    /** The server's name, or null when nothing Jellyfin answers there. */
    fun serverName(server: String): String? = runCatching {
        val res = HttpJson.get("$server/System/Info/Public")
        if (res.code in 200..299) JSONObject(res.body).optString("ServerName", "Jellyfin") else null
    }.getOrNull()

    fun startQuickConnect(context: Context, server: String): QuickConnect? = runCatching {
        // POST on current servers; some older ones only take GET.
        val res = HttpJson.postJson("$server/QuickConnect/Initiate", "{}", auth(context)).takeIf { it.code in 200..299 }
            ?: HttpJson.get("$server/QuickConnect/Initiate", auth(context))
        if (res.code !in 200..299) return null
        val json = JSONObject(res.body)
        QuickConnect(server, json.getString("Code"), json.getString("Secret"))
    }.getOrNull()

    /** Signed in once the code is approved; null while it is still waiting. */
    fun finishQuickConnect(context: Context, pending: QuickConnect): JellyfinAccount? = runCatching {
        val state = HttpJson.get("${pending.server}/QuickConnect/Connect?secret=${enc(pending.secret)}", auth(context))
        if (state.code !in 200..299 || !JSONObject(state.body).optBoolean("Authenticated")) return null
        val res = HttpJson.postJson(
            "${pending.server}/Users/AuthenticateWithQuickConnect",
            JSONObject().put("Secret", pending.secret).toString(),
            auth(context),
        )
        account(pending.server, res)
    }.getOrNull()

    fun signIn(context: Context, server: String, user: String, password: String): JellyfinAccount? = runCatching {
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        account(server, HttpJson.postJson("$server/Users/AuthenticateByName", body, auth(context)))
    }.getOrNull()

    private fun account(server: String, res: HttpJson.Response): JellyfinAccount? {
        if (res.code !in 200..299) return null
        val json = JSONObject(res.body)
        val user = json.getJSONObject("User")
        return JellyfinAccount(server, json.getString("AccessToken"), user.getString("Id"), user.optString("Name"))
    }

    /** Videos to choose from: what is under way, what is next in a series, and the newest. */
    fun videos(context: Context, account: JellyfinAccount): List<Pair<String, List<JellyfinItem>>> {
        val u = account.userId
        val common = "Limit=20&Fields=UserData&MediaTypes=Video"
        return listOf(
            "Continue watching" to "/Users/$u/Items/Resume?$common",
            "Next up" to "/Shows/NextUp?userId=$u&$common",
            "Latest" to "/Users/$u/Items/Latest?$common&IncludeItemTypes=Movie,Episode",
        ).map { (label, path) -> label to items(context, account, path) }
    }

    private fun items(context: Context, account: JellyfinAccount, path: String): List<JellyfinItem> = runCatching {
        val res = HttpJson.get(account.server + path, auth(context, account.token))
        if (res.code !in 200..299) return emptyList()
        val body = res.body.trim()
        // Latest answers with a bare array, the others with { "Items": [...] }.
        val arr = if (body.startsWith("[")) org.json.JSONArray(body) else JSONObject(body).optJSONArray("Items") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            val series = item.optString("SeriesName").takeIf { it.isNotBlank() }
            val name = item.optString("Name")
            val ticks = item.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", 0) ?: 0
            JellyfinItem(item.getString("Id"), series?.let { "$it · $name" } ?: name, ticks / 10_000)
        }
    }.getOrDefault(emptyList())

    /** The file as it is, untouched: no transcoding, so three at once stay light on the server. */
    fun streamUrl(account: JellyfinAccount, itemId: String): String =
        "${account.server}/Videos/$itemId/stream?static=true&api_key=${enc(account.token)}"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
