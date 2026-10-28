package dev.solon.bucklerhome

/**
 * Pulls the article text out of a story page. Scripts, iframes and page chrome are discarded, so
 * the reader cannot show ads or open another popup.
 */
object ArticleReader {
    fun fetch(url: String): List<String> {
        val html = load(url) ?: return emptyList()
        val text = extract(html)
        if (text.isNotEmpty()) return text
        val amp = ampLink(html) ?: return emptyList()
        if (amp == url) return emptyList()
        return extract(load(amp).orEmpty())
    }

    /** AMP href when the page declares one. Script-rendered stories often still publish a static AMP copy. */
    internal fun ampLink(html: String): String? {
        val tag = ampTag.find(html)?.value ?: return null
        return ampHref.find(tag)?.groupValues?.get(1)
    }

    /** Structured article text when the page publishes it, otherwise the longest article's paragraphs. */
    fun extract(html: String): List<String> {
        val page = html.take(MAX_HTML)
        val structured = embeddedArticles(page)
        val visible = paragraphs(page)
        val structuredLen = structured.sumOf { it.length }
        val visibleLen = visible.sumOf { it.length }
        val chosen = when {
            structuredLen >= 280 && structuredLen >= visibleLen / 2 -> structured
            visible.isNotEmpty() -> visible
            else -> structured
        }
        // Page text and JSON-LD both carry entities (&quot;, &#x27;); some are double-escaped.
        return chosen.map { decodeHtmlEntities(decodeHtmlEntities(it)) }
    }

    private fun load(url: String): String? {
        if (!url.startsWith("https://")) return null
        val res = runCatching {
            HttpJson.get(url, headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        }.getOrNull() ?: return null
        return res.body.takeIf { res.code in 200..299 }
    }

    private const val MAX_HTML = 1_500_000
    private val ampTag = Regex("""<link\b[^>]*rel=["']amphtml["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val ampHref = Regex("""href=["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val articleBody = Regex(""""articleBody"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val articleBlock = Regex(
        """<article\b[^>]*>(.*?)</article>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val mainBlock = Regex(
        """<main\b[^>]*>(.*?)</main>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val paragraph = Regex(
        """<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tag = Regex("<[^>]+>")
    private val noise = listOf(
        "script", "style", "noscript", "iframe", "svg", "form", "nav", "footer", "header", "aside", "button",
    ).map { name -> Regex("(?is)<$name\\b[^>]*>.*?</$name>") }

    /** `articleBody` from JSON-LD or from a page blob such as a script payload, not only typed script tags. */
    private fun embeddedArticles(html: String): List<String> {
        val out = ArrayList<String>()
        for (match in articleBody.findAll(html)) {
            out += prose(unescapeJson(match.groupValues[1]))
        }
        return out.distinct().take(24)
    }

    private fun prose(raw: String): List<String> {
        if ('<' in raw) {
            val fromTags = paragraphs("<article>$raw</article>")
            if (fromTags.isNotEmpty()) return fromTags
        }
        val block = oneBlock(raw)
        if (block.length < 80) return emptyList()
        val parts = block.split('\n').map { it.trim() }.filter { it.length >= 40 && !promo(it) }
        return if (parts.size > 1) parts else listOf(block)
    }

    private fun paragraphs(html: String): List<String> {
        val stripped = stripNoise(html)
        val region = articleBlock.findAll(stripped).maxByOrNull { it.groupValues[1].length }?.groupValues?.get(1)
            ?: mainBlock.find(stripped)?.groupValues?.get(1)
            ?: stripped
        return paragraph.findAll(region)
            .map { oneLine(it.groupValues[1]) }
            .filter { it.length >= 80 && !promo(it) }
            .distinct()
            .take(24)
            .toList()
    }

    private fun stripNoise(html: String): String {
        var out = html
        for (pattern in noise) out = pattern.replace(out, " ")
        return out
    }

    private fun promo(text: String): Boolean {
        if (text.length > 240) return false
        val lower = text.lowercase()
        return listOf(
            "advertisement", "subscribe", "sign up", "newsletter", "cookie", "privacy policy", "related stories",
        ).any { it in lower }
    }

    private fun oneLine(raw: String): String =
        tag.replace(unescapeJson(raw), " ").replace(Regex("\\s+"), " ").trim()

    private fun oneBlock(raw: String): String {
        val text = tag.replace(raw, "\n")
        return text.replace(Regex("[ \\t]+"), " ").replace(Regex("\n{2,}"), "\n").trim()
    }

    private fun unescapeJson(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'n' -> append('\n')
                't' -> append(' ')
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                'u' -> {
                    val hex = raw.substring(i + 2, (i + 6).coerceAtMost(raw.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) append(code.toChar()) else append(next)
                    i += 4
                }
                else -> append(next)
            }
            i += 2
        }
    }
}
