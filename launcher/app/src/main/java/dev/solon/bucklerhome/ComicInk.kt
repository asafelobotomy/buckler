package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.pow
import org.json.JSONObject

/** One panel's box in a strip, as fractions of its width and height. */
data class PanelBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A strip for the Comics stage: where to fetch it, its credit, its panels (none: show it whole),
 * and a larger copy for the reader ([url] when there is none).
 */
data class ComicStrip(val url: String, val credit: String, val panels: List<PanelBox> = emptyList(), val largeUrl: String = url)

/**
 * A strip made ready to draw: each panel's ink (or the whole strip's, trimmed) on a clear
 * background, in the strip's own ink colour, and the paper colour the card takes.
 */
class InkStrip(val cells: List<Bitmap>, val paper: Int)

/**
 * The curated strips: the 1930 Mickey Mouse dailies from the Internet Archive, with the panel
 * boxes `scripts/gen_comic_panels.py` found and a person checked, bundled as an asset.
 */
object CuratedComics {
    private const val ASSET = "comics_mickey1930.json"
    private const val WIDTH = 1600

    /** The reader's copy: twice the stage's, so a panel zoomed to the screen stays sharp. */
    private const val LARGE_WIDTH = 3200

    @Volatile
    private var cache: List<ComicStrip>? = null

    /** Blocking (reads the asset once); call off the main thread. */
    fun strips(context: Context): List<ComicStrip> =
        cache ?: runCatching {
            parse(context.assets.open(ASSET).use { it.readBytes().decodeToString() })
        }.getOrDefault(emptyList()).also { cache = it }

    fun parse(json: String): List<ComicStrip> {
        val root = JSONObject(json)
        val credit = root.optString("credit")
        val list = root.getJSONArray("strips")
        return (0 until list.length()).map { i ->
            val s = list.getJSONObject(i)
            val boxes = s.optJSONArray("panels")
            val panels = (0 until (boxes?.length() ?: 0)).map { p ->
                val b = boxes!!.getJSONArray(p)
                PanelBox(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat())
            }
            val item = s.getString("item")
            val file = s.getString("file")
            ComicStrip(iiif(item, file), "${s.getString("date")} · $credit", panels, iiif(item, file, LARGE_WIDTH))
        }
    }

    /** The strip [width] px wide from the Archive's IIIF service, a few hundred KB instead of several MB. */
    fun iiif(item: String, file: String, width: Int = WIDTH): String {
        val id = URLEncoder.encode("$item/$file", StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "https://iiif.archive.org/image/iiif/3/$id/full/$width,/0/default.jpg"
    }
}

/**
 * Separates a strip's line drawing from its paper: every pixel becomes the strip's ink colour at
 * an opacity from how dark it was, so grey, speckled newsprint drops away and the drawing sits on
 * the card with no edge. Pure parts are on the companion for tests.
 */
object ComicInk {
    /** Paper this dark is a photo of a strip on a dark cloth or the like: shown as it is. */
    private const val DARKEST_PAPER = 110

    /**
     * [file] cut into [panels] (or trimmed whole), each scaled to at most [height] px tall. Null
     * when it cannot be read or its paper is too dark to separate. Blocking.
     */
    fun prepare(file: File, panels: List<PanelBox>, height: Int): InkStrip? {
        val source = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        val w = source.width
        val h = source.height
        val argb = IntArray(w * h)
        source.getPixels(argb, 0, w, 0, 0, w, h)
        source.recycle()
        val lum = IntArray(argb.size) { luminance(argb[it]) }
        val paper = percentile(lum, 0.9)
        if (paper < DARKEST_PAPER) return null
        val threshold = otsu(lum)
        val inkLum = percentile(lum, 0.02).coerceAtMost(threshold - 1)
        val inkColour = inkColour(argb, lum, threshold)
        val paperColour = paperColour(argb, lum, paper)
        val boxes = panels.map { p ->
            intArrayOf((p.left * w).toInt(), (p.top * h).toInt(), (p.right * w).toInt().coerceAtMost(w), (p.bottom * h).toInt().coerceAtMost(h))
        }.ifEmpty { listOf(inkBounds(lum, w, h, threshold)) }
        val cells = boxes.mapNotNull { (l, t, r, b) ->
            val cw = r - l
            val ch = b - t
            if (cw <= 0 || ch <= 0) return@mapNotNull null
            val out = IntArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val a = inkAlpha(lum[(t + y) * w + l + x], paper, inkLum)
                out[y * cw + x] = (a shl 24) or (inkColour and 0xFFFFFF)
            }
            val cell = Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
            if (ch <= height) {
                cell
            } else {
                Bitmap.createScaledBitmap(cell, cw * height / ch, height, true).also { if (it !== cell) cell.recycle() }
            }
        }
        if (cells.isEmpty()) return null
        return InkStrip(cells, paperColour)
    }

    fun luminance(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /**
     * Opacity of a pixel of brightness [lum] on [paper], with [ink] fully opaque. A gentle curve
     * keeps faint pencil-light lines visible, and anything barely off the paper (its grain,
     * show-through from the other side) is dropped.
     */
    fun inkAlpha(lum: Int, paper: Int, ink: Int): Int {
        val depth = (paper - lum).toFloat() / (paper - ink).coerceAtLeast(1)
        if (depth <= 0.12f) return 0
        return (255 * depth.coerceAtMost(1f).pow(0.7f)).toInt()
    }

    /** Otsu's threshold: the brightness that best splits ink from paper. */
    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        lum.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = lum.size.toDouble()
        val sum = hist.indices.sumOf { it.toDouble() * hist[it] }
        var below = 0.0
        var count = 0.0
        var best = 0.0
        var threshold = 128
        for (i in 0 until 256) {
            count += hist[i]
            if (count == 0.0 || count == total) continue
            below += i.toDouble() * hist[i]
            val low = below / count
            val high = (sum - below) / (total - count)
            val spread = count * (total - count) * (low - high) * (low - high)
            if (spread > best) {
                best = spread
                threshold = i
            }
        }
        return threshold
    }

    fun percentile(lum: IntArray, p: Double): Int {
        val hist = IntArray(256)
        lum.forEach { hist[it.coerceIn(0, 255)]++ }
        val target = (lum.size * p).toLong()
        var seen = 0L
        for (i in 0 until 256) {
            seen += hist[i]
            if (seen > target) return i
        }
        return 255
    }

    /** The box holding the strip's ink (rows and columns with more than a speck of it), as l, t, r, b. */
    fun inkBounds(lum: IntArray, w: Int, h: Int, threshold: Int): IntArray {
        val rows = IntArray(h)
        val cols = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            if (lum[y * w + x] < threshold) {
                rows[y]++
                cols[x]++
            }
        }
        val top = rows.indexOfFirst { it > w / 250 }.takeIf { it >= 0 } ?: 0
        val bottom = rows.indexOfLast { it > w / 250 }.takeIf { it >= 0 } ?: (h - 1)
        val left = cols.indexOfFirst { it > h / 250 }.takeIf { it >= 0 } ?: 0
        val right = cols.indexOfLast { it > h / 250 }.takeIf { it >= 0 } ?: (w - 1)
        return intArrayOf(left, top, right + 1, bottom + 1)
    }

    /** Median colour of the clearly dark pixels: black for most strips, brown on a sepia one. */
    private fun inkColour(argb: IntArray, lum: IntArray, threshold: Int): Int =
        medianColour(argb.indices.filter { lum[it] < threshold * 0.6 && it % 7 == 0 }.map { argb[it] }, 0xFF141414.toInt())

    /** Median colour of the pixels as light as the paper. */
    private fun paperColour(argb: IntArray, lum: IntArray, paper: Int): Int =
        medianColour(argb.indices.filter { lum[it] >= paper - 4 && it % 7 == 0 }.map { argb[it] }, 0xFFF4F0E6.toInt())

    private fun medianColour(pixels: List<Int>, fallback: Int): Int {
        if (pixels.isEmpty()) return fallback
        fun median(shift: Int) = pixels.map { (it shr shift) and 0xFF }.sorted()[pixels.size / 2]
        return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
    }
}
