package dev.solon.bucklerhome

/** One headline plus the feed it came from, so the ticker can show that source's mark. */
data class Headline(
    val source: NewsSource,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
)

/** Title, short text and picture parsed from one RSS item. */
data class NewsStory(val title: String, val summary: String? = null, val imageUrl: String? = null)

/**
 * What to fetch. Shuffle pulls every source and round-robins them so each story is followed by a
 * story from a different source; otherwise only [source] is used.
 */
data class NewsQuery(val source: NewsSource?, val shuffle: Boolean) {
    val sources: List<NewsSource> = if (shuffle) NewsSource.entries else listOfNotNull(source)
    val cacheKey: String = if (shuffle) "shuffle" else source?.name ?: "off"
}

/**
 * Free public RSS feeds: no key, no account. [mark] is the short label drawn in front of a story,
 * in [markColor] with [onMark] text (brand colours, drawn by us rather than bundled artwork).
 */
enum class NewsSource(val label: String, val url: String, val mark: String, val markColor: Long, val onMark: Long) {
    BBC_WORLD("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "BBC", 0xFFBB1919, 0xFFFFFFFF),
    NPR("NPR News", "https://feeds.npr.org/1001/rss.xml", "NPR", 0xFFF4F4F4, 0xFF111111),
    GUARDIAN("The Guardian", "https://www.theguardian.com/world/rss", "Gdn", 0xFF052962, 0xFFFFFFFF),
    AL_JAZEERA("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "AJ", 0xFFF5A623, 0xFF1A1208),
    HACKER_NEWS("Hacker News", "https://hnrss.org/frontpage", "Y", 0xFFFF6600, 0xFF1A1208),
    ;

    /** Next source in the cycle; after the last one comes null (news off). */
    fun next(): NewsSource? = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromName(raw: String?): NewsSource = entries.firstOrNull { it.name == raw } ?: BBC_WORLD
    }
}

object NewsFeed {
    private const val MAX_HEADLINES = 14
    private val RSS_ACCEPT = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml")

    fun fetch(source: NewsSource): List<String> = fetchStories(source).map { it.title }

    private fun fetchStories(source: NewsSource): List<NewsStory> {
        val res = runCatching { HttpJson.get(source.url, headers = RSS_ACCEPT) }.getOrNull()
            ?: return emptyList()
        return if (res.code in 200..299) parseStories(res.body, MAX_HEADLINES) else emptyList()
    }

    /** One source's headlines, or every source interleaved when [NewsQuery.shuffle] is set. */
    fun load(query: NewsQuery): List<Headline> {
        val groups = query.sources.map { source ->
            source to fetchStories(source).map { story ->
                Headline(source, story.title, story.summary, story.imageUrl)
            }
        }.filter { it.second.isNotEmpty() }
        return if (query.shuffle) interleaveHeadlines(groups.map { it.second }) else groups.flatMap { it.second }
    }

    /** Lead story for the Main Stage: the first one with a picture, otherwise the first story. */
    fun featured(headlines: List<Headline>): Headline? =
        headlines.firstOrNull { !it.imageUrl.isNullOrBlank() } ?: headlines.firstOrNull()

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
        return NewsStory(title, summary, imageFrom(item))
    }

    private fun imageFrom(item: String): String? {
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
            if (thumbnail || image) return url
        }
        return imgSrc.find(item)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }
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
