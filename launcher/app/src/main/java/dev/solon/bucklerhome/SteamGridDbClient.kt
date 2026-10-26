package dev.solon.bucklerhome

import android.content.Context
import android.net.Uri
import org.json.JSONArray

data class SgdbImage(
    val id: Int,
    val url: String,
    val thumb: String?,
    val kind: String,
)

object SteamGridDbClient {
    private const val BASE = "https://www.steamgriddb.com/api/v2"
    const val PREFERENCES_URL = "https://www.steamgriddb.com/profile/preferences"

    fun searchCovers(context: Context, query: String, layout: CoverLayout): List<CoverCandidate> {
        val token = CoverAccounts.sgdbToken(context) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val encoded = Uri.encode(query)
        val search = HttpJson.get(
            "$BASE/search/autocomplete/$encoded",
            headers = auth(token),
        )
        if (search.code !in 200..299) return emptyList()
        val games = HttpJson.parseObject(search.body)?.optJSONArray("data") ?: return emptyList()
        if (games.length() == 0) return emptyList()
        val gameId = games.optJSONObject(0)?.optInt("id") ?: return emptyList()
        val kind = when (layout) {
            CoverLayout.BANNER -> "heroes"
            CoverLayout.POSTER -> "grids"
            CoverLayout.ICON -> "icons"
        }
        val images = fetchImages(token, kind, gameId)
        return images.take(12).mapIndexed { index, img ->
            CoverCandidate(
                id = "sgdb-${img.id}",
                label = "SteamGridDB ${img.kind} ${index + 1}",
                source = "SteamGridDB",
                storeValue = "sgdb:${img.url}",
                previewUrl = img.thumb ?: img.url,
            )
        }
    }

    private fun fetchImages(token: String, kind: String, gameId: Int): List<SgdbImage> {
        val response = HttpJson.get(
            "$BASE/$kind/game/$gameId",
            headers = auth(token),
        )
        if (response.code !in 200..299) return emptyList()
        val data = HttpJson.parseObject(response.body)?.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<SgdbImage>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (url.isBlank()) continue
            out += SgdbImage(
                id = obj.optInt("id"),
                url = url,
                thumb = obj.optString("thumb").takeIf { it.isNotBlank() },
                kind = kind.trimEnd('s'),
            )
        }
        return out
    }

    private fun auth(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")
}
