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
    private fun file(context: Context, key: String) = File(context.cacheDir, "headlines-${key.hashCode().toUInt()}.json")

    /** Headlines saved for [key], and when they were fetched (0 if unknown), so a restart does not refetch fresh news. */
    class Saved(val headlines: List<Headline>, val fetchedAt: Long)

    fun load(context: Context, key: String): Saved = runCatching {
        val json = JSONObject(file(context, key).readText())
        if (json.optString("key") != key) return Saved(emptyList(), 0)
        val items = json.getJSONArray("items")
        Saved((0 until items.length()).mapNotNull { i -> items.getJSONObject(i).toHeadline() }, json.optLong("at", 0))
    }.getOrDefault(Saved(emptyList(), 0))

    fun save(context: Context, key: String, headlines: List<Headline>, fetchedAt: Long) {
        val items = JSONArray(headlines.map { it.toJson() })
        runCatching { file(context, key).writeText(JSONObject().put("key", key).put("at", fetchedAt).put("items", items).toString()) }
    }

    private fun Headline.toJson() = JSONObject()
        .put("source", source.name)
        .put("title", title)
        .put("summary", summary ?: JSONObject.NULL)
        .put("image", imageUrl ?: JSONObject.NULL)
        .put("link", link ?: JSONObject.NULL)
        .put("published", published ?: JSONObject.NULL)

    private fun JSONObject.toHeadline(): Headline? {
        val source = NewsSource.entries.firstOrNull { it.name == optString("source") } ?: return null
        fun text(name: String) = if (isNull(name)) null else optString(name).ifBlank { null }
        val published = if (isNull("published")) null else optLong("published").takeIf { it > 0 }
        return Headline(source, optString("title").ifBlank { return null }, text("summary"), text("image"), text("link"), published)
    }
}
