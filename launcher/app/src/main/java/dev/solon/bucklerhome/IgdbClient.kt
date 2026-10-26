package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONArray

object IgdbClient {
    private const val BASE = "https://api.igdb.com/v4"

    fun searchCovers(context: Context, query: String): List<CoverCandidate> {
        val token = TwitchDeviceAuth.ensureAccessToken(context) ?: return emptyList()
        val clientId = BuildConfig.TWITCH_CLIENT_ID
        if (clientId.isBlank() || query.isBlank()) return emptyList()
        val body = """
            search "${query.replace("\"", "")}";
            fields name,cover.image_id;
            limit 8;
        """.trimIndent()
        val response = HttpJson.postJson(
            "$BASE/games",
            body,
            headers = mapOf(
                "Client-ID" to clientId,
                "Authorization" to "Bearer $token",
            ),
        )
        if (response.code !in 200..299) return emptyList()
        val arr = runCatching { JSONArray(response.body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<CoverCandidate>()
        for (i in 0 until arr.length()) {
            val game = arr.optJSONObject(i) ?: continue
            val cover = game.optJSONObject("cover") ?: continue
            val imageId = cover.optString("image_id")
            if (imageId.isBlank()) continue
            val url = "https://images.igdb.com/igdb/image/upload/t_cover_big/$imageId.jpg"
            val name = game.optString("name").ifBlank { "IGDB cover" }
            out += CoverCandidate(
                id = "igdb-$imageId",
                label = name,
                source = "IGDB",
                storeValue = "igdb:$url",
                previewUrl = url,
            )
        }
        return out
    }
}
