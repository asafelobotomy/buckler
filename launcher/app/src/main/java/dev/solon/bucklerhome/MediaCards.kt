package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One piece of media seen playing, shown as a cover on the Main Stage. */
data class MediaCard(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val appLabel: String,
    val artUri: String?,
    val pinned: Boolean,
    val seenAt: Long,
) {
    fun sameAs(other: MediaCard): Boolean = packageName == other.packageName && title == other.title
}

object MediaCards {
    private const val PREFS = "bucklerhome"
    private const val KEY = "media_cards_json"
    private const val MAX_UNPINNED = 8

    fun load(context: Context): List<MediaCard> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("package")
                val title = o.optString("title")
                if (pkg.isBlank() || title.isBlank()) continue
                add(
                    MediaCard(
                        packageName = pkg,
                        title = title,
                        subtitle = o.optString("subtitle"),
                        appLabel = o.optString("app"),
                        artUri = o.optString("art").takeIf { it.isNotBlank() },
                        pinned = o.optBoolean("pinned"),
                        seenAt = o.optLong("seen"),
                    ),
                )
            }
        }
    }

    fun remember(context: Context, info: NowPlayingInfo) {
        if (info.packageName.isBlank() || info.title.isBlank()) return
        val incoming = MediaCard(
            packageName = info.packageName,
            title = info.title,
            subtitle = info.subtitle,
            appLabel = info.appLabel,
            artUri = info.artUri,
            pinned = false,
            seenAt = System.currentTimeMillis(),
        )
        save(context, merge(load(context), incoming, incoming.seenAt))
    }

    fun togglePin(context: Context, card: MediaCard): List<MediaCard> {
        val next = order(load(context).map { if (it.sameAs(card)) it.copy(pinned = !it.pinned) else it })
        save(context, next)
        return next
    }

    fun merge(existing: List<MediaCard>, incoming: MediaCard, now: Long, maxUnpinned: Int = MAX_UNPINNED): List<MediaCard> {
        val previous = existing.firstOrNull { it.sameAs(incoming) }
        val updated = incoming.copy(
            seenAt = now,
            pinned = previous?.pinned ?: incoming.pinned,
            artUri = incoming.artUri ?: previous?.artUri,
            subtitle = incoming.subtitle.ifBlank { previous?.subtitle.orEmpty() },
            appLabel = incoming.appLabel.ifBlank { previous?.appLabel.orEmpty() },
        )
        return order(listOf(updated) + existing.filterNot { it.sameAs(updated) }, maxUnpinned)
    }

    fun order(cards: List<MediaCard>, maxUnpinned: Int = MAX_UNPINNED): List<MediaCard> {
        val pinned = cards.filter { it.pinned }.sortedByDescending { it.seenAt }
        val recent = cards.filterNot { it.pinned }.sortedByDescending { it.seenAt }.take(maxUnpinned)
        return pinned + recent
    }

    private fun save(context: Context, cards: List<MediaCard>) {
        val arr = JSONArray()
        for (card in cards) {
            arr.put(
                JSONObject()
                    .put("package", card.packageName)
                    .put("title", card.title)
                    .put("subtitle", card.subtitle)
                    .put("app", card.appLabel)
                    .put("art", card.artUri)
                    .put("pinned", card.pinned)
                    .put("seen", card.seenAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
