package dev.solon.bucklerhome

/** Free public RSS feeds: no key, no account. */
enum class NewsSource(val label: String, val url: String) {
    BBC_WORLD("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
    NPR("NPR News", "https://feeds.npr.org/1001/rss.xml"),
    GUARDIAN("The Guardian", "https://www.theguardian.com/world/rss"),
    AL_JAZEERA("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
    HACKER_NEWS("Hacker News", "https://hnrss.org/frontpage"),
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

    fun fetch(source: NewsSource): List<String> {
        val res = runCatching { HttpJson.get(source.url, headers = RSS_ACCEPT) }.getOrNull()
            ?: return emptyList()
        return if (res.code in 200..299) parseHeadlines(res.body, MAX_HEADLINES) else emptyList()
    }

    private val itemRe = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val titleRe = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val cdataRe = Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val tagRe = Regex("<[^>]+>")
    private val entityRe = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")

    /** Tolerant RSS 2.0 / Atom title extraction (no XML parser, so it is unit-testable on the JVM). */
    fun parseHeadlines(xml: String, limit: Int): List<String> =
        itemRe.findAll(xml)
            .mapNotNull { titleRe.find(it.groupValues[2])?.groupValues?.get(1) }
            .map { clean(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(limit)
            .toList()

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
