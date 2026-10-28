package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last headlines on disk, so the ticker has something to show the moment Home starts. On a
 * 3 GB TV Android often closes Home while an app plays, and fetching every source again takes a
 * while; until then the ticker would be blank.
 */
object HeadlineStore {
    private const val FILE = "headlines.json"

    fun load(context: Context, key: String): List<Headline> = runCatching {
        val json = JSONObject(File(context.cacheDir, FILE).readText())
        if (json.optString("key") != key) return emptyList()
        val items = json.getJSONArray("items")
        (0 until items.length()).mapNotNull { i -> items.getJSONObject(i).toHeadline() }
    }.getOrDefault(emptyList())

    fun save(context: Context, key: String, headlines: List<Headline>) {
        val items = JSONArray(headlines.map { it.toJson() })
        runCatching { File(context.cacheDir, FILE).writeText(JSONObject().put("key", key).put("items", items).toString()) }
    }

    private fun Headline.toJson() = JSONObject()
        .put("source", source.name)
        .put("title", title)
        .put("summary", summary ?: JSONObject.NULL)
        .put("image", imageUrl ?: JSONObject.NULL)
        .put("link", link ?: JSONObject.NULL)

    private fun JSONObject.toHeadline(): Headline? {
        val source = NewsSource.entries.firstOrNull { it.name == optString("source") } ?: return null
        fun text(name: String) = if (isNull(name)) null else optString(name).ifBlank { null }
        return Headline(source, optString("title").ifBlank { return null }, text("summary"), text("image"), text("link"))
    }
}
