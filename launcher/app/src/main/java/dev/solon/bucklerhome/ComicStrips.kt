package dev.solon.bucklerhome

import android.content.Context
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Newspaper comic strips whose US copyright has run out (1920s and before), from Wikimedia
 * Commons: the files tagged PD-US-expired that a search for "comic strip" finds. Only daily
 * strips (wide, one row of panels) are kept, as a Sunday page is unreadable in the Main Stage's
 * band. The list is cached for a few days, like the screensaver's photos.
 */
object ComicStrips {
    private const val COMMONS = "https://commons.wikimedia.org/w/api.php"
    private const val SEARCH = "\"comic strip\" hastemplate:PD-US-expired filetype:bitmap"
    private const val INDEX_TTL_MS = 3 * 24 * 3_600_000L

    /** Daily strips run about 3:1; wider than 2.2 leaves out pages and single panels. */
    val STRIP_SHAPE = 2.2..6.0

    /** Blocking: call off the main thread. Shuffled; empty when offline with nothing cached. */
    fun index(context: Context): List<CommonsImage> {
        val cache = File(context.cacheDir, "comics-index.json")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < INDEX_TTL_MS) {
            read(cache).takeIf { it.isNotEmpty() }?.let { return it.shuffled() }
        }
        // About 460 matches, a fifth of them strips: a few pages give a good mix.
        val fresh = listOf(0, 50, 100, 150, 200, 250, 300, 350, 400).shuffled().take(4)
            .flatMap(::page).distinctBy { it.url }
        if (fresh.isNotEmpty()) {
            runCatching { cache.writeText(JSONArray(fresh.map { JSONObject().put("u", it.url).put("c", it.credit) }).toString()) }
            return fresh.shuffled()
        }
        return read(cache).shuffled()
    }

    private fun page(offset: Int): List<CommonsImage> {
        val url = "$COMMONS?action=query&generator=search&gsrnamespace=6&gsrlimit=50&gsroffset=$offset" +
            "&gsrsearch=${URLEncoder.encode(SEARCH, StandardCharsets.UTF_8.name())}" +
            "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1600&format=json"
        val res = runCatching { HttpJson.get(url, headers = ScreensaverImages.UA) }.getOrNull() ?: return emptyList()
        return if (res.code in 200..299) ScreensaverImages.parseCommons(res.body, minWidth = 900, aspect = STRIP_SHAPE) else emptyList()
    }

    private fun read(file: File): List<CommonsImage> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { CommonsImage(it.getString("u"), it.optString("c")) } }
    }.getOrDefault(emptyList())
}
