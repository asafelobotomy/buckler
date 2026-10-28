package dev.solon.bucklerhome

import android.net.Uri
import org.json.JSONObject
import kotlin.math.abs

/**
 * Word-timed lyrics from NetEase Cloud Music's YRC format, the first source before LRCLIB. The API
 * is unofficial (no key, not documented), so every failure just falls through to LRCLIB.
 *
 * YRC lines read `[lineStart,lineDur](wordStart,wordDur,0)word(…)word`, all in ms; lines that
 * start with `{` are credits and are skipped.
 */
object NetEaseLyrics {
    private const val BASE = "https://music.163.com/api"
    private const val MAX_DURATION_GAP_MS = 4_000L
    private const val CANDIDATES = 3
    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android TV) buckler-home/${BuildConfig.VERSION_NAME}",
        "Referer" to "https://music.163.com/",
    )

    /** Best YRC lines for the track: real word timing first, else line-timed YRC; null if none. */
    fun lookup(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val search = HttpJson.get(
            "$BASE/search/get?s=${Uri.encode("$title $artist")}&type=1&limit=10",
            headers = HEADERS,
        )
        if (search.code !in 200..299) return null
        val songs = HttpJson.parseObject(search.body)?.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val ids = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
            .filter { matches(it, title, artist, durationMs) }
            .sortedBy { if (durationMs > 0) abs(it.optLong("duration") - durationMs) else 0L }
            .map { it.optLong("id") }
            .take(CANDIDATES)
        var lineTimed: List<LyricLine>? = null
        for (id in ids) {
            val lines = fetchYrc(id) ?: continue
            if (hasWordTiming(lines)) return lines
            if (lineTimed == null) lineTimed = lines
        }
        return lineTimed
    }

    private fun fetchYrc(id: Long): List<LyricLine>? {
        val res = HttpJson.get("$BASE/song/lyric/v1?id=$id&lv=1&yv=1&tv=0&rv=0&kv=0", headers = HEADERS)
        if (res.code !in 200..299) return null
        val yrc = HttpJson.parseObject(res.body)?.optJSONObject("yrc")?.optString("lyric").orEmpty()
        return parseYrc(yrc).takeIf { it.size >= MIN_LINES }
    }

    /** Same song: title and an artist agree once bracketed extras are dropped; length within 4 s. */
    internal fun matches(song: JSONObject, title: String, artist: String, durationMs: Long): Boolean {
        if (normalize(song.optString("name")) != normalize(title)) return false
        val wanted = normalize(artist)
        val artists = song.optJSONArray("artists")
        val artistOk = wanted.isEmpty() || (0 until (artists?.length() ?: 0)).any { i ->
            val name = normalize(artists?.optJSONObject(i)?.optString("name").orEmpty())
            name.isNotEmpty() && (name in wanted || wanted in name)
        }
        val length = song.optLong("duration")
        return artistOk && (durationMs <= 0 || length <= 0 || abs(length - durationMs) <= MAX_DURATION_GAP_MS)
    }

    /** Lowercase letters and digits only, without "(feat. …)", "[Remix]" or " - Remastered" tails. */
    internal fun normalize(raw: String): String = raw.lowercase()
        .replace(Regex("""\([^)]*\)|\[[^]]*]"""), "")
        .substringBefore(" - ")
        .filter { it.isLetterOrDigit() }

    private val lineHead = Regex("""^\[(\d+),(\d+)]""")
    private val wordMark = Regex("""\((\d+),(\d+),-?\d+\)""")

    internal fun parseYrc(text: String): List<LyricLine> = text.lines().mapNotNull { raw ->
        val head = lineHead.find(raw) ?: return@mapNotNull null
        val start = head.groupValues[1].toLong()
        val end = start + head.groupValues[2].toLong()
        val marks = wordMark.findAll(raw, head.range.last + 1).toList()
        if (marks.isEmpty()) {
            return@mapNotNull LyricLine(start, raw.substring(head.range.last + 1).trim(), endMs = end)
        }
        val text = StringBuilder()
        val tokens = ArrayList<SungToken>()
        marks.forEachIndexed { i, mark ->
            val piece = raw.substring(mark.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: raw.length)
            val core = piece.trim()
            if (core.isNotEmpty()) {
                val from = text.length + (piece.length - piece.trimStart().length)
                val at = mark.groupValues[1].toLong()
                tokens += SungToken(from until from + core.length, at, at + mark.groupValues[2].toLong())
            }
            text.append(piece)
        }
        LyricLine(start, text.toString().trimEnd(), endMs = end, tokens = tokens)
    }.filter { it.text.isNotBlank() }.sortedBy { it.atMs }

    /** Real word timing, not words cut into equal lengths: most multi-word lines vary. */
    internal fun hasWordTiming(lines: List<LyricLine>): Boolean {
        val judged = lines.filter { it.tokens.size >= 3 }
        if (judged.isEmpty()) return false
        return judged.count { !evenlySplit(it.tokens) } * 2 >= judged.size
    }

    private const val MIN_LINES = 3
}

/** True when every word got the same length: machine-split, so only the line timing is real. */
internal fun evenlySplit(tokens: List<SungToken>): Boolean {
    if (tokens.size < 2) return false
    val lengths = tokens.map { it.endMs - it.atMs }
    return lengths.max() - lengths.min() <= EVEN_SPLIT_TOLERANCE_MS
}

private const val EVEN_SPLIT_TOLERANCE_MS = 20L
