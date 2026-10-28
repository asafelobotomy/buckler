package dev.solon.bucklerhome

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One timed word (or syllable) of a line: where it sits in the text and when it is sung. */
data class SungToken(val range: IntRange, val atMs: Long, val endMs: Long)

/**
 * One timed line of synced lyrics. [wordTimes] holds a start per word (split on spaces) when the
 * record is enhanced LRC (`<mm:ss.xx>` before each word). [tokens] and [endMs] come from NetEase
 * YRC, which times every word with its own length and ends the line. All empty for line-only LRC.
 */
data class LyricLine(
    val atMs: Long,
    val text: String,
    val wordTimes: List<Long> = emptyList(),
    val endMs: Long? = null,
    val tokens: List<SungToken> = emptyList(),
)

/** What a lookup found for one track. */
sealed interface Lyrics {
    /** Timed lines, highlighted as the song plays; [source] is credited on the stage. */
    data class Synced(val lines: List<LyricLine>, val source: String = "LRCLIB") : Lyrics

    /** Untimed lines; the stage moves through them in step with the track length. */
    data class Plain(val lines: List<String>) : Lyrics

    data object Instrumental : Lyrics

    data object NotFound : Lyrics
}

/**
 * Lyrics for the playing track. NetEase YRC first, for real word timing; then LRCLIB (lrclib.net),
 * an open, community-run database with a free API and no key: exact match (title, artist, album,
 * length), then a search that tolerates a different album or edit. Results are kept in memory so
 * replays and stage switches do not refetch.
 */
object LyricsClient {
    private const val BASE = "https://lrclib.net/api"
    private const val CACHE_SIZE = 24
    private val UA = mapOf(
        "User-Agent" to "buckler-home/${BuildConfig.VERSION_NAME} (https://github.com/${BuildConfig.GITHUB_REPO})",
    )
    private val cache = object : LinkedHashMap<String, Lyrics>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) = size > CACHE_SIZE
    }

    /** Blocking; call off the main thread. Null means the lookup failed (offline), not "no lyrics". */
    fun lookup(title: String, artist: String, album: String, durationMs: Long): Lyrics? {
        if (title.isBlank()) return Lyrics.NotFound
        val key = listOf(title, artist, album).joinToString("\n").lowercase()
        synchronized(cache) { cache[key] }?.let { return it }
        val found = runCatching { fetch(title, artist, album, durationMs) }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = found }
        return found
    }

    private fun fetch(title: String, artist: String, album: String, durationMs: Long): Lyrics {
        // NetEase is unofficial: any failure or miss just means LRCLIB decides.
        val netEase = runCatching { NetEaseLyrics.lookup(title, artist, durationMs) }.getOrNull()
        if (netEase != null) return Lyrics.Synced(netEase, "NetEase")
        return fetchLrclib(title, artist, album, durationMs)
    }

    private fun fetchLrclib(title: String, artist: String, album: String, durationMs: Long): Lyrics {
        val seconds = durationMs / 1000
        // Exact match first; when it misses, or LRCLIB answers it busy (it can 503 /get while
        // /search still works), the search below still finds the song.
        if (artist.isNotBlank() && album.isNotBlank() && seconds > 0) {
            val exact = runCatching {
                HttpJson.get(
                    "$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                        "&album_name=${enc(album)}&duration=$seconds",
                    headers = UA,
                )
            }.getOrNull()
            if (exact != null && exact.code in 200..299) {
                HttpJson.parseObject(exact.body)?.let(::fromRecord)?.let { return it }
            }
        }
        val query = "track_name=${enc(title)}" + if (artist.isNotBlank()) "&artist_name=${enc(artist)}" else ""
        val search = HttpJson.get("$BASE/search?$query", headers = UA)
        if (search.code !in 200..299) error("LRCLIB ${search.code}")
        val records = runCatching { JSONArray(search.body) }.getOrNull() ?: return Lyrics.NotFound
        return pick(records, seconds)?.let(::fromRecord) ?: Lyrics.NotFound
    }

    /** Closest length wins (within 5 s when the length is known), synced over plain. */
    internal fun pick(records: JSONArray, seconds: Long): JSONObject? {
        val candidates = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
            .filter { seconds <= 0 || abs(it.optDouble("duration", 0.0) - seconds) <= 5 }
        return candidates.minWithOrNull(
            compareBy<JSONObject>(
                { it.optString("syncedLyrics").isBlank() },
                { if (seconds > 0) abs(it.optDouble("duration", 0.0) - seconds) else 0.0 },
            ),
        )
    }

    internal fun fromRecord(record: JSONObject): Lyrics? {
        if (record.optBoolean("instrumental")) return Lyrics.Instrumental
        val synced = parseLrc(record.optString("syncedLyrics").takeUnless { record.isNull("syncedLyrics") }.orEmpty())
        if (synced.isNotEmpty()) return Lyrics.Synced(synced)
        val plain = record.optString("plainLyrics").takeUnless { record.isNull("plainLyrics") }.orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (plain.isNotEmpty()) Lyrics.Plain(plain) else null
    }

    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /**
     * `[mm:ss.xx] text` lines; several stamps on one line repeat it. Blank lines keep a gap.
     * Enhanced LRC word stamps (`<mm:ss.xx>word`) become [LyricLine.wordTimes].
     */
    internal fun parseLrc(text: String): List<LyricLine> = text.lines().flatMap { raw ->
        val stamps = stamp.findAll(raw).toList()
        if (stamps.isEmpty()) return@flatMap emptyList()
        val body = raw.substring(stamps.last().range.last + 1)
        val (words, wordTimes) = splitWordStamps(body)
        stamps.map { m -> LyricLine(toMs(m), words, wordTimes) }
    }.sortedBy { it.atMs }

    /** Plain text plus one time per word when every word carries a `<mm:ss.xx>` stamp. */
    private fun splitWordStamps(body: String): Pair<String, List<Long>> {
        val tags = wordStamp.findAll(body).toList()
        val plain = wordStamp.replace(body, " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tags.isEmpty()) return plain.joinToString(" ") to emptyList()
        val times = ArrayList<Long>()
        tags.forEachIndexed { i, tag ->
            val end = tags.getOrNull(i + 1)?.range?.first ?: body.length
            val chunk = body.substring(tag.range.last + 1, end).trim()
            chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { _ -> times += toMs(tag) }
        }
        return plain.joinToString(" ") to (if (times.size == plain.size) times else emptyList())
    }

    private fun toMs(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val ms = when (frac.length) {
            0 -> 0
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.take(3).toInt()
        }
        return (min.toLong() * 60 + sec.toLong()) * 1000 + ms
    }

    /** Index of the line playing at [positionMs]; -1 before the first line. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int =
        lines.indexOfLast { it.atMs <= positionMs }

    private fun enc(value: String): String = Uri.encode(value)
}
