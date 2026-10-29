package dev.solon.bucklerhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a pack draws its icons, measured from its own icons for apps on this TV, so an icon Buckler
 * draws for an app the pack lacks matches the real ones: line width and the mark's size as shares
 * of the icon (or, for a layered pack, of its foreground layer), the line colour, and the backing
 * plate's colour when the pack's icons are layered (Lawnicons). [colourful] packs are drawn in many
 * colours; a traced line would not match them, so their icons' common outline ([shape], when they
 * share one: a circle, a squircle, a rounded square) is kept instead, to fit an app's own icon into.
 */
data class PackStyle(
    val line: Float,
    val mark: Float,
    val ink: Int,
    val plate: Int?,
    val filled: Boolean,
    val colourful: Boolean,
    /** The pack icons' shared outline, [MASK]² alpha 0..1 over the whole icon, or null. */
    val shape: FloatArray? = null,
) {
    companion object {
        /** Resolution of [shape]. */
        const val MASK = 128

        /** Large enough that a pack's thin lines are many pixels wide, for a precise width. */
        private const val SIZE = 512

        /** Measures up to a dozen of the pack's icons; null when none could be read. */
        fun measure(icons: List<Drawable>): PackStyle? {
            val samples = icons.take(12).mapNotNull(::sample)
            if (samples.isEmpty()) return null
            val plates = samples.mapNotNull { it.plate }
            // Full colour: each icon a picture of many colours, or each in its own strong colour
            // (a green circle, a red one); a line pack's icons share one plain or tinted colour.
            val vivid = samples.count { saturation(it.ink) > 0.3f } * 2 > samples.size
            val varied = samples.map { it.ink }.let { inks -> inks.any { a -> inks.any { b -> colourDistance(a, b) > 150 } } }
            val colourful = samples.count { it.colourful } * 2 > samples.size || (vivid && varied)
            return PackStyle(
                line = samples.map { it.line }.median(),
                mark = samples.map { it.mark }.median(),
                ink = samples.map { it.ink }.mostCommonColour(),
                plate = if (plates.size * 2 > samples.size) plates.mostCommonColour() else null,
                filled = samples.count { it.filled } * 2 > samples.size,
                colourful = colourful,
                shape = if (colourful) sharedShape(samples.map { it.outline }) else null,
            )
        }

        /**
         * The outline most of the icons share (the average of their silhouettes), when they do:
         * each must overlap it by at least 85%, or the pack has free-form icons and no shape.
         */
        private fun sharedShape(outlines: List<FloatArray>): FloatArray? {
            if (outlines.size < 3) return null
            val mean = FloatArray(MASK * MASK) { i -> outlines.sumOf { it[i].toDouble() }.toFloat() / outlines.size }
            fun overlap(a: FloatArray): Float {
                var both = 0
                var either = 0
                for (i in a.indices) {
                    val x = a[i] >= 0.5f
                    val y = mean[i] >= 0.5f
                    if (x && y) both++
                    if (x || y) either++
                }
                return if (either == 0) 0f else both.toFloat() / either
            }
            return mean.takeIf { outlines.count { overlap(it) >= 0.85f } * 4 >= outlines.size * 3 }
        }

        private class Sample(
            val line: Float,
            val mark: Float,
            val ink: Int,
            val plate: Int?,
            val filled: Boolean,
            val colourful: Boolean,
            val outline: FloatArray,
        )

        private fun sample(icon: Drawable): Sample? {
            val adaptive = icon as? AdaptiveIconDrawable
            val plate = adaptive?.background?.let(FittedArt::edgeColour)
            val pixels = render(adaptive?.foreground ?: icon)
            val ink = BooleanArray(SIZE * SIZE) { i ->
                val p = pixels[i]
                Color.alpha(p) >= 128 && (plate == null || colourDistance(p, plate) > 90)
            }
            var area = 0
            var edge = 0
            var l = SIZE
            var t = SIZE
            var r = -1
            var b = -1
            var red = 0L
            var green = 0L
            var blue = 0L
            var spread = 0L
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val i = y * SIZE + x
                if (!ink[i]) continue
                area++
                val border = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1 ||
                    !ink[i - 1] || !ink[i + 1] || !ink[i - SIZE] || !ink[i + SIZE]
                if (border) edge++
                l = min(l, x)
                t = min(t, y)
                r = max(r, x)
                b = max(b, y)
                red += Color.red(pixels[i])
                green += Color.green(pixels[i])
                blue += Color.blue(pixels[i])
            }
            if (area < 20 || edge == 0) return null
            val mean = Color.rgb((red / area).toInt(), (green / area).toInt(), (blue / area).toInt())
            for (i in pixels.indices) if (ink[i]) spread += colourDistance(pixels[i], mean)
            val width = strokeWidth(ink)
            val mark = (max(r - l, b - t) + 1f) / SIZE
            return Sample(
                line = width / SIZE,
                mark = mark,
                ink = mean,
                plate = plate,
                // Solid shapes are much wider than any line relative to their size.
                filled = width / SIZE > mark * 0.12f,
                colourful = spread / area > 60,
                outline = silhouette(icon),
            )
        }

        /** The whole icon's alpha at [MASK] px, 0..1: its outline, whatever is drawn inside. */
        private fun silhouette(icon: Drawable): FloatArray {
            val bitmap = Bitmap.createBitmap(MASK, MASK, Bitmap.Config.ARGB_8888)
            val old = icon.copyBounds()
            icon.setBounds(0, 0, MASK, MASK)
            icon.draw(Canvas(bitmap))
            icon.bounds = old
            val pixels = IntArray(MASK * MASK)
            bitmap.getPixels(pixels, 0, MASK, 0, 0, MASK, MASK)
            bitmap.recycle()
            return FloatArray(MASK * MASK) { Color.alpha(pixels[it]) / 255f }
        }

        /**
         * Typical stroke width in px: twice the distance from a stroke's middle to its edge,
         * read at the stroke's ridge (pixels at least as far in as their neighbours). A chamfer
         * distance treats diagonals and curves like straight lines, which counting edges does not.
         */
        internal fun strokeWidth(ink: BooleanArray, size: Int = SIZE): Float {
            val far = 1 shl 20
            val d = IntArray(size * size) { if (ink[it]) far else 0 }
            fun relax(i: Int, j: Int, cost: Int) {
                if (d[j] + cost < d[i]) d[i] = d[j] + cost
            }
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (d[i] == 0) continue
                if (x == 0 || y == 0) { d[i] = 3; continue }
                relax(i, i - 1, 3); relax(i, i - size, 3); relax(i, i - size - 1, 4)
                if (x < size - 1) relax(i, i - size + 1, 4)
            }
            for (y in size - 1 downTo 0) for (x in size - 1 downTo 0) {
                val i = y * size + x
                if (d[i] == 0) continue
                if (x == size - 1 || y == size - 1) { d[i] = minOf(d[i], 3); continue }
                relax(i, i + 1, 3); relax(i, i + size, 3); relax(i, i + size + 1, 4)
                if (x > 0) relax(i, i + size - 1, 4)
            }
            val ridge = ArrayList<Int>()
            for (y in 1 until size - 1) for (x in 1 until size - 1) {
                val i = y * size + x
                val v = d[i]
                if (v == 0) continue
                if (v >= d[i - 1] && v >= d[i + 1] && v >= d[i - size] && v >= d[i + size]) ridge += v
            }
            if (ridge.isEmpty()) return 0f
            ridge.sort()
            // The middle half of the ridge, averaged: joins and ends neither inflate nor thin it.
            val mid = ridge.subList(ridge.size / 4, max(ridge.size / 4 + 1, ridge.size * 3 / 4))
            // Chamfer units are thirds of a pixel; a 1 px line's middle is 1 px from outside.
            return 2f * mid.average().toFloat() / 3f - 1f
        }

        private fun render(drawable: Drawable): IntArray {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val old = drawable.copyBounds()
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = old
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            bitmap.recycle()
            return pixels
        }

        private fun List<Float>.median(): Float = sorted()[size / 2]

        private fun saturation(colour: Int): Float {
            val hsv = FloatArray(3)
            Color.colorToHSV(colour, hsv)
            return hsv[1] * hsv[2]
        }

        /** The colour most samples share, to the nearest step, so one odd icon does not decide it. */
        private fun List<Int>.mostCommonColour(): Int =
            groupBy { Color.rgb(Color.red(it) / 16 * 16, Color.green(it) / 16 * 16, Color.blue(it) / 16 * 16) }
                .maxBy { it.value.size }.value.first()
    }
}

internal fun colourDistance(a: Int, b: Int): Int =
    kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
        kotlin.math.abs(Color.blue(a) - Color.blue(b))

/**
 * A pack's own rule for apps it has no icon for, from its `appfilter.xml` (`<iconback>`,
 * `<iconmask>`, `<iconupon>`, `<scale>`), as launchers apply it: the app's icon, scaled, on the
 * pack's backing, cut by its mask, with its overlay on top.
 */
class PackFallback(
    private val back: Drawable?,
    private val mask: Drawable?,
    private val upon: Drawable?,
    private val scale: Float,
) {
    fun apply(icon: Drawable): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back?.draw(canvas, size)
        val inset = (size * (1f - scale) / 2f).roundToInt()
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        icon.copyBounds().let { old ->
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(layerCanvas)
            icon.bounds = old
        }
        mask?.let { m ->
            val cut = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            m.draw(Canvas(cut), size)
            layerCanvas.drawBitmap(cut, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            cut.recycle()
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        upon?.draw(canvas, size)
        return bitmap
    }

    private fun Drawable.draw(canvas: Canvas, size: Int) {
        val old = copyBounds()
        setBounds(0, 0, size, size)
        draw(canvas)
        bounds = old
    }

    val isEmpty: Boolean get() = back == null && mask == null && upon == null
}
