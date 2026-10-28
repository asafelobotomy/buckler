package dev.solon.bucklerhome

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One timed line of synced lyrics. */
data class LyricLine(val atMs: Long, val text: String)

/** What a lookup found for one track. */
sealed interface Lyrics {
    /** Timed lines, highlighted as the song plays. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    /** Untimed lines; the stage moves through them in step with the track length. */
    data class Plain(val lines: List<String>) : Lyrics

    data object Instrumental : Lyrics

    data object NotFound : Lyrics
}

/**
 * Lyrics from LRCLIB (lrclib.net), an open, community-run lyrics database with a free API and no
 * key. Exact match first (title, artist, album, length), then a search that tolerates a different
 * album or edit. Results are kept in memory so replays and stage switches do not refetch.
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
        val seconds = durationMs / 1000
        if (artist.isNotBlank() && album.isNotBlank() && seconds > 0) {
            val exact = HttpJson.get(
                "$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                    "&album_name=${enc(album)}&duration=$seconds",
                headers = UA,
            )
            if (exact.code in 200..299) {
                HttpJson.parseObject(exact.body)?.let(::fromRecord)?.let { return it }
            } else if (exact.code != 404) {
                error("LRCLIB ${exact.code}")
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

    /** `[mm:ss.xx] text` lines; several stamps on one line repeat it. Blank lines keep a gap. */
    internal fun parseLrc(text: String): List<LyricLine> = text.lines().flatMap { raw ->
        val stamps = stamp.findAll(raw).toList()
        if (stamps.isEmpty()) return@flatMap emptyList()
        val words = raw.substring(stamps.last().range.last + 1).trim()
        stamps.map { m ->
            val (min, sec, frac) = m.destructured
            val ms = when (frac.length) {
                0 -> 0
                1 -> frac.toInt() * 100
                2 -> frac.toInt() * 10
                else -> frac.take(3).toInt()
            }
            LyricLine((min.toLong() * 60 + sec.toLong()) * 1000 + ms, words)
        }
    }.sortedBy { it.atMs }

    /** Index of the line playing at [positionMs]; -1 before the first line. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int =
        lines.indexOfLast { it.atMs <= positionMs }

    private fun enc(value: String): String = Uri.encode(value)
}
