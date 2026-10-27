package dev.solon.bucklerhome

/**
 * Pulls the article text out of a story page. Scripts, iframes and page chrome are discarded, so
 * the reader cannot show ads or open another popup.
 */
object ArticleReader {
    fun fetch(url: String): List<String> {
        if (!url.startsWith("https://")) return emptyList()
        val res = runCatching {
            HttpJson.get(url, headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        }.getOrNull() ?: return emptyList()
        if (res.code !in 200..299) return emptyList()
        return extract(res.body)
    }

    /** Structured article text when the page publishes it, otherwise the longest article's paragraphs. */
    fun extract(html: String): List<String> {
        val structured = jsonLd(html)
        val visible = paragraphs(html)
        val structuredLen = structured.sumOf { it.length }
        val visibleLen = visible.sumOf { it.length }
        return when {
            structuredLen >= 280 && structuredLen >= visibleLen / 2 -> structured
            visible.isNotEmpty() -> visible
            else -> structured
        }
    }

    private val ldScript = Regex(
        """<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
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
    private val noiseTags = listOf(
        "script", "style", "noscript", "iframe", "svg", "form", "nav", "footer", "header", "aside", "button",
    )

    private fun jsonLd(html: String): List<String> {
        val out = ArrayList<String>()
        for (match in ldScript.findAll(html)) {
            val raw = articleBody.find(match.groupValues[1])?.groupValues?.get(1) ?: continue
            val text = oneBlock(unescapeJson(raw))
            if (text.length < 80) continue
            val parts = text.split('\n').map { it.trim() }.filter { it.length >= 40 && !promo(it) }
            out += if (parts.size > 1) parts else listOf(text)
        }
        return out.distinct().take(24)
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
        for (name in noiseTags) {
            out = Regex("(?is)<$name\\b[^>]*>.*?</$name>").replace(out, " ")
        }
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
