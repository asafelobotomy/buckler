package dev.solon.bucklerhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An app's own picture (its TV banner or icon) fitted whole into a tile of another shape, on a
 * background of the picture's own edge colour, so a 16:9 banner becomes a 2:3 poster, or an icon
 * a banner, without cropping or stretching it. [share] is how much of the tile's limiting side
 * the picture takes. The drawable has no size of its own: it fills whatever tile it is given.
 */
class FittedArt(private val source: Drawable, private val fill: Int, private val share: Float) : Drawable() {
    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty) return
        canvas.drawRect(box, fillPaint)
        val sw = source.intrinsicWidth.takeIf { it > 0 } ?: box.height()
        val sh = source.intrinsicHeight.takeIf { it > 0 } ?: box.height()
        val scale = minOf(box.width() * share / sw, box.height() * share / sh)
        val w = (sw * scale).roundToInt()
        val h = (sh * scale).roundToInt()
        val left = box.left + (box.width() - w) / 2
        val top = box.top + (box.height() - h) / 2
        source.setBounds(left, top, left + w, top + h)
        source.draw(canvas)
    }

    private val fillPaint = android.graphics.Paint().apply { color = fill }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (fillPaint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    companion object {
        /**
         * The colour around the edge of [drawable], so the space around it continues the picture;
         * null when the edge is mostly transparent (a logo cut out on nothing).
         */
        fun edgeColour(drawable: Drawable): Int? {
            // A layered icon's edge is its mask; its background layer is the colour behind it.
            // A copy: the original may be on screen, drawing on the main thread.
            val own = drawable.constantState?.newDrawable()?.mutate() ?: drawable
            val sample = (own as? AdaptiveIconDrawable)?.background ?: own
            val sw = sample.intrinsicWidth.takeIf { it > 0 } ?: 48
            val sh = sample.intrinsicHeight.takeIf { it > 0 } ?: 48
            val k = 48f / max(sw, sh)
            val w = max(4, (sw * k).roundToInt())
            val h = max(4, (sh * k).roundToInt())
            val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            val old = sample.copyBounds()
            sample.setBounds(0, 0, w, h)
            sample.draw(Canvas(bitmap))
            sample.bounds = old
            var r = 0L
            var g = 0L
            var b = 0L
            var opaque = 0
            var total = 0
            for (x in 0 until w) for (y in 0 until h) {
                if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue
                total++
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) < 200) continue
                opaque++
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
            }
            bitmap.recycle()
            if (opaque * 2 < total) return null
            return Color.rgb((r / opaque).toInt(), (g / opaque).toInt(), (b / opaque).toInt())
        }
    }
}
