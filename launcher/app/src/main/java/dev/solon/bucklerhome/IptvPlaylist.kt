package dev.solon.bucklerhome

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One channel from an M3U playlist. */
data class IptvChannel(val name: String, val group: String, val url: String)

/**
 * An IPTV playlist (M3U): downloaded to the cache once, then read from there. Playlists run to
 * tens of thousands of channels, so it is streamed to disk rather than held as one string.
 */
object IptvPlaylist {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    private fun file(context: Context) = File(context.cacheDir, "multiview-playlist.m3u")

    /** Downloads [url]; the number of channels in it, or null when it could not be read. */
    fun refresh(context: Context, url: String): Int? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
        }
        if (conn.responseCode !in 200..299) return null
        val tmp = File(context.cacheDir, "multiview-playlist.tmp")
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
            }
        }
        conn.disconnect()
        tmp.renameTo(file(context))
        channels(context).size
    }.getOrNull()

    fun channels(context: Context): List<IptvChannel> = runCatching {
        file(context).bufferedReader().useLines { parse(it) }
    }.getOrDefault(emptyList())

    /** #EXTINF lines give the name (after the last comma) and group-title; the next address line is the stream. */
    fun parse(lines: Sequence<String>): List<IptvChannel> {
        val out = ArrayList<IptvChannel>()
        var name: String? = null
        var group = ""
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRe.findAll(line.substringBefore(',')).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = line.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] }
                    group = attrs["group-title"].orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    out += IptvChannel(name ?: line.substringAfterLast('/'), group, line)
                    name = null
                    group = ""
                }
            }
        }
        return out
    }
}
