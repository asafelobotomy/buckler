package dev.solon.bucklerhome

/** One headline plus the feed it came from, so the ticker can show that source's mark. */
data class Headline(
    val source: NewsSource,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    /** Article URL. The News stage opens this in the reading overlay. */
    val link: String? = null,
)

/** Title, short text, picture and article link parsed from one RSS item. */
data class NewsStory(
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
)

/** Feeds to fetch, in catalog order. More than one is mixed so neighbouring stories differ. */
data class NewsQuery(val sources: List<NewsSource>) {
    val cacheKey: String = sources.joinToString("+") { it.name }.ifEmpty { "off" }
}

enum class NewsCategory(val label: String) {
    WORLD("World"),
    GAMING("Gaming"),
    BUSINESS("Business"),
    TECH("Tech"),
}

/**
 * Free public RSS feeds: no key, no account. [mark] is the short label drawn in front of a story,
 * in [markColor] with [onMark] text (brand colours, drawn by us rather than bundled artwork).
 */
enum class NewsSource(
    val label: String,
    val url: String,
    val mark: String,
    val markColor: Long,
    val onMark: Long,
    val category: NewsCategory,
) {
    BBC_WORLD("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "BBC", 0xFFBB1919, 0xFFFFFFFF, NewsCategory.WORLD),
    NPR("NPR News", "https://feeds.npr.org/1001/rss.xml", "NPR", 0xFFF4F4F4, 0xFF111111, NewsCategory.WORLD),
    GUARDIAN("The Guardian", "https://www.theguardian.com/world/rss", "Gdn", 0xFF052962, 0xFFFFFFFF, NewsCategory.WORLD),
    AL_JAZEERA("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "AJ", 0xFFF5A623, 0xFF1A1208, NewsCategory.WORLD),
    DW("DW", "https://rss.dw.com/rdf/rss-en-world", "DW", 0xFF003399, 0xFFFFFFFF, NewsCategory.WORLD),
    POLYGON("Polygon", "https://www.polygon.com/rss/index.xml", "PLY", 0xFFFF0066, 0xFFFFFFFF, NewsCategory.GAMING),
    EUROGAMER("Eurogamer", "https://www.eurogamer.net/feed", "EG", 0xFFE4002B, 0xFFFFFFFF, NewsCategory.GAMING),
    PC_GAMER("PC Gamer", "https://www.pcgamer.com/rss/", "PCG", 0xFFB00000, 0xFFFFFFFF, NewsCategory.GAMING),
    ROCK_PAPER_SHOTGUN("Rock Paper Shotgun", "https://www.rockpapershotgun.com/feed", "RPS", 0xFFF5C518, 0xFF1A1208, NewsCategory.GAMING),
    BBC_BUSINESS("BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml", "Biz", 0xFFBB1919, 0xFFFFFFFF, NewsCategory.BUSINESS),
    NPR_BUSINESS("NPR Business", "https://feeds.npr.org/1006/rss.xml", "NPR", 0xFFF4F4F4, 0xFF111111, NewsCategory.BUSINESS),
    GUARDIAN_BUSINESS("Guardian Business", "https://www.theguardian.com/uk/business/rss", "Gdn", 0xFF052962, 0xFFFFFFFF, NewsCategory.BUSINESS),
    ARS_TECHNICA("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Ars", 0xFFFF4E00, 0xFF1A1208, NewsCategory.TECH),
    THE_VERGE("The Verge", "https://www.theverge.com/rss/index.xml", "VRG", 0xFF5200FF, 0xFFFFFFFF, NewsCategory.TECH),
    ;

    companion object {
        fun fromName(raw: String?): NewsSource? = entries.firstOrNull { it.name == raw }
    }
}

object NewsFeed {
    private const val MAX_HEADLINES = 14
    private const val MAX_MIXED = 28
    private val RSS_ACCEPT = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml")

    fun fetch(source: NewsSource): List<String> = fetchStories(source).map { it.title }

    private fun fetchStories(source: NewsSource): List<NewsStory> {
        val res = runCatching { HttpJson.get(source.url, headers = RSS_ACCEPT) }.getOrNull()
            ?: return emptyList()
        return if (res.code in 200..299) parseStories(res.body, MAX_HEADLINES) else emptyList()
    }

    /** One source's headlines, or the chosen sources mixed so each story is followed by another source. */
    fun load(query: NewsQuery): List<Headline> {
        if (query.sources.isEmpty()) return emptyList()
        val groups = query.sources.map { source ->
            fetchStories(source).map { story ->
                Headline(source, story.title, story.summary, story.imageUrl, story.link)
            }
        }.filter { it.isNotEmpty() }
        val mixed = if (groups.size > 1) interleaveHeadlines(groups) else groups.flatten()
        return mixed.take(MAX_MIXED)
    }

    /** Lead story for the Main Stage: the first one with a picture, otherwise the first story. */
    fun featured(headlines: List<Headline>): Headline? = stagePool(headlines).firstOrNull()

    /** Stories the Main Stage cycles. Ones with a picture come first; the rest are used only if none do. */
    fun stagePool(headlines: List<Headline>): List<Headline> {
        val pictured = headlines.filter { !it.imageUrl.isNullOrBlank() }
        return pictured.ifEmpty { headlines }
    }

    /**
     * Round-robin: the first story of each source, then the second of each, and so on. A source
     * that runs out is skipped, so neighbouring stories stay from different sources.
     */
    fun interleave(groups: List<Pair<NewsSource, List<String>>>): List<Headline> =
        interleaveHeadlines(groups.map { (source, titles) -> titles.map { Headline(source, it) } })

    fun interleaveHeadlines(groups: List<List<Headline>>): List<Headline> {
        val columns = groups
        val out = ArrayList<Headline>()
        var row = 0
        while (true) {
            var added = false
            for (column in columns) {
                if (row < column.size) {
                    out += column[row]
                    added = true
                }
            }
            if (!added) return out
            row++
        }
    }

    private val itemRe = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val titleRe = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val descRe = Regex(
        "<(description|summary|content)\\b[^>]*>(.*?)</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val imgSrc = Regex("""<img\b[^>]*\bsrc=["']([^"']+)["']""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val cdataRe = Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val tagRe = Regex("<[^>]+>")
    private val entityRe = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")

    /** Tolerant RSS 2.0 / Atom title extraction (no XML parser, so it is unit-testable on the JVM). */
    fun parseHeadlines(xml: String, limit: Int): List<String> =
        parseStories(xml, limit).map { it.title }

    /** Title, a short summary and an image URL when the item carries one. */
    fun parseStories(xml: String, limit: Int): List<NewsStory> =
        itemRe.findAll(xml)
            .mapNotNull { item -> storyFrom(item.groupValues[2]) }
            .distinctBy { it.title }
            .take(limit)
            .toList()

    private fun storyFrom(item: String): NewsStory? {
        val title = titleRe.find(item)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
        val summary = descRe.find(item)?.groupValues?.get(2)?.let(::clean)?.let(::clip)
            ?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
        return NewsStory(title, summary, imageFrom(item), linkFrom(item))
    }

    private val rssLink = Regex("""<link\b[^>]*>([^<]+)</link>""", RegexOption.IGNORE_CASE)
    private val atomLink = Regex("""<link\b([^>]*)/?>""", RegexOption.IGNORE_CASE)

    /** RSS `<link>` body, otherwise an Atom alternate href. */
    private fun linkFrom(item: String): String? {
        val body = rssLink.find(item)?.groupValues?.get(1)?.trim()
        if (body != null && body.startsWith("https://")) return body
        var fallback: String? = null
        for (match in atomLink.findAll(item)) {
            val href = attr(match.value, "href") ?: continue
            if (!href.startsWith("https://")) continue
            when (attr(match.value, "rel")) {
                null, "alternate" -> return href
                "self", "enclosure" -> Unit
                else -> if (fallback == null) fallback = href
            }
        }
        return fallback
    }

    private fun imageFrom(item: String): String? {
        val found = ArrayList<Pair<String, Int>>()
        for (match in tagRe.findAll(item)) {
            val raw = match.value
            val url = attr(raw, "url") ?: continue
            if (!url.startsWith("https://")) continue
            val kind = (attr(raw, "type") ?: attr(raw, "medium")).orEmpty()
            val thumbnail = raw.startsWith("<media:thumbnail")
            val image = thumbnail || kind.startsWith("image") || looksLikeImage(url)
            val enclosure = raw.startsWith("<enclosure")
            if (enclosure && !image) continue
            if (raw.startsWith("<media:content") && kind.isNotEmpty() && !image) continue
            if (!(thumbnail || image)) continue
            found += enlargeNewsImage(url) to imageScore(raw, thumbnail)
        }
        imgSrc.find(item)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }?.let { url ->
            found += enlargeNewsImage(url) to 2
        }
        return found.maxByOrNull { it.second }?.first
    }

    /** Prefer a large declared size. Thumbnails and tiny icons lose to a full frame. */
    private fun imageScore(tag: String, thumbnail: Boolean): Int {
        val width = attr(tag, "width")?.toIntOrNull() ?: 0
        val height = attr(tag, "height")?.toIntOrNull() ?: 0
        if ((width in 1..160) || (height in 1..160)) return 1
        var score = when {
            width > 0 && height > 0 -> width * height
            width > 0 -> width * width
            else -> 10_000
        }
        if (thumbnail) score /= 4
        if (tag.startsWith("<media:content")) score *= 2
        return score
    }

    /**
     * Longest edge to request and to decode. BBC ichef only publishes a 1024 step in this path,
     * so that host stays on 1024 while every other rewrite uses [NEWS_IMAGE_PX].
     */
    const val NEWS_IMAGE_PX = 1400

    private val bbcSize = Regex("""(ichef\.bbci\.co\.uk/(?:ace/standard|news)/)\d{2,4}/""")
    private val queryPx = Regex("""([?&](?:width|w|h|s)=)\d+""")
    private val brightspotResize = Regex("""resize/(\d+)x(\d+)!""")
    private val futureSize = Regex("""-(\d{2,4})-(\d{2,3})(\.(?:jpe?g|png|webp))""")

    /** Ask known image CDNs for a frame large enough for a TV stage, and cap ones that are already huge. */
    fun enlargeNewsImage(url: String): String {
        var out = url.replace("&amp;", "&").replace("&#038;", "&").replace("&#38;", "&")
        out = bbcSize.replace(out) { "${it.groupValues[1]}1024/" }
        out = queryPx.replace(out) { match ->
            val key = match.groupValues[1]
            if (key.endsWith("s=") && "media.npr.org" !in out) match.value else "$key$NEWS_IMAGE_PX"
        }
        if ("brightspotcdn.com" in out) {
            out = brightspotResize.replace(out) { match ->
                val w = match.groupValues[1].toInt()
                val h = match.groupValues[2].toInt()
                val long = maxOf(w, h)
                if (long <= NEWS_IMAGE_PX) match.value
                else {
                    val nw = if (w >= h) NEWS_IMAGE_PX else (w.toLong() * NEWS_IMAGE_PX / h).toInt().coerceAtLeast(1)
                    val nh = if (h > w) NEWS_IMAGE_PX else (h.toLong() * NEWS_IMAGE_PX / w).toInt().coerceAtLeast(1)
                    "resize/${nw}x${nh}!"
                }
            }
        }
        if ("futurecdn.net" in out) {
            out = futureSize.replace(out) { "-$NEWS_IMAGE_PX-${it.groupValues[2]}${it.groupValues[3]}" }
        }
        return out
    }

    private fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

    private fun clip(text: String): String {
        if (text.length <= 180) return text
        val cut = text.take(180)
        val space = cut.lastIndexOf(' ')
        return (if (space > 80) cut.take(space) else cut).trimEnd() + "…"
    }

    private fun clean(raw: String): String {
        val unwrapped = cdataRe.replace(raw) { it.groupValues[1] }
        return decodeEntities(tagRe.replace(unwrapped, "")).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntities(text: String): String = entityRe.replace(text) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x") || e.startsWith("#X")) {
                    e.drop(2).toIntOrNull(16)
                } else {
                    e.drop(1).toIntOrNull()
                }
                code?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
