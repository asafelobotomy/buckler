package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import org.json.JSONObject

object SuggestedRepository {
    fun load(
        context: Context,
        apps: List<LaunchableApp>,
        pinned: List<String>,
        feedUrl: String?,
        limit: Int = 16,
    ): List<ShelfItem> {
        val counts = LaunchStats.loadCounts(context)
        val recents = LaunchStats.loadRecents(context)
        val byPkg = apps.associateBy { it.packageName }

        val score = mutableMapOf<String, Int>()
        for ((pkg, c) in counts) score[pkg] = c * 10
        recents.forEachIndexed { index, pkg ->
            score[pkg] = (score[pkg] ?: 0) + (recents.size - index)
        }
        for (pkg in pinned) {
            score[pkg] = (score[pkg] ?: 0) + 3
        }

        val local = score.entries
            .sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key] }
            .distinctBy { it.packageName }
            .take(limit)
            .map { app ->
                ShelfItem(
                    id = "sug-${app.packageName}",
                    title = app.label,
                    packageName = app.packageName,
                    launchIntent = app.launchIntent,
                    icon = app.icon,
                )
            }

        val remote = feedUrl?.let { fetchFeed(it) }.orEmpty()
        return (remote + local).distinctBy { it.id }.take(limit)
    }

    private fun fetchFeed(url: String): List<ShelfItem> {
        if (!url.startsWith("https://")) return emptyList()
        return runCatching {
            val response = HttpJson.get(url, connectTimeoutMs = 4_000, readTimeoutMs = 4_000)
            if (response.code !in 200..299) return@runCatching emptyList()
            val arr = JSONObject(response.body).optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val title = item.optString("title")
                    if (title.isBlank()) continue
                    val pkg = item.optString("package").ifBlank { null }
                    val image = item.optString("image_url").ifBlank { null }
                    val intentUri = item.optString("intent_uri").ifBlank { null }
                    val intent = intentUri?.let {
                        runCatching {
                            Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }.getOrNull()
                    }
                    add(
                        ShelfItem(
                            id = "feed-$i-$title",
                            title = title,
                            packageName = pkg,
                            launchIntent = intent,
                            imageUrl = image,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
