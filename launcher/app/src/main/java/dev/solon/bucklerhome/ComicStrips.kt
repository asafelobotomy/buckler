package dev.solon.bucklerhome

import android.content.Context
import android.graphics.BitmapFactory
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

    /**
     * The colour of [file]'s paper, from the pixels along its outer edge in a reduced copy (see
     * [edgeColour]). Blocking; null when the file cannot be read.
     */
    fun paperColour(file: File): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        // About 200 px wide: decoding smaller averages bigger blocks, which pulls in more of the
        // panel lines and lettering beside the edge and greys the paper.
        while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
        val small = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val colour = edgeColour(pixels, small.width, small.height)
        small.recycle()
        return colour
    }

    /**
     * The paper among the outer [ring] pixels of a [width] x [height] image: the one nine tenths of
     * the way up by brightness (on the strips tried, within a level or two of the real edge). Ink, panel borders and lettering along the edge (and the blocks
     * a reduced copy averages them into) are darker than the paper, so they fall below it, and a
     * stray white speck stays above. An edge that really is dark, such as a strip photographed on
     * black cloth, gives a dark colour, which is what meets the card there.
     */
    fun edgeColour(pixels: IntArray, width: Int, height: Int, ring: Int = 2): Int {
        val edge = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            if (x < ring || y < ring || x >= width - ring || y >= height - ring) edge += pixels[y * width + x]
        }
        fun brightness(c: Int) = ((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114
        return edge.sortedBy(::brightness)[edge.size * 9 / 10] or (0xFF shl 24)
    }
}
