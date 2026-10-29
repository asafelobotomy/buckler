package dev.solon.bucklerhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An app's icon as Home shows it: the chosen pack's icon (by package or by name), else, while
 * a pack is in use and [IconPackStore.fillGaps] is on, one drawn in the pack's style from the
 * app's own icon, else the app's own icon.
 */
fun appIcon(context: Context, component: ComponentName?, label: String, own: () -> Drawable?): Drawable? {
    component?.let { IconPacks.iconFor(context, it, label) }?.let { return it }
    val icon = own()
    if (icon == null || component == null || !IconPackStore.fillGaps(context) || !IconPacks.isActive(context)) return icon
    return PackStyleIcons.make(context, component.packageName, label, icon)
}

/**
 * Icons in the style of the line-drawn packs (Arcticons, Lawnicons) for apps a pack has no icon
 * for. The app's own logo is traced as a thin, smooth outline; only when there is no mark to
 * trace (nothing on the icon, or one solid block) does it get the app's initials in an outlined
 * badge. Both are drawn in the theme's text colour, as a themed pack is.
 *
 * Tracing works on a soft (anti-aliased) copy of the logo at [WORK] px: blurred a little, the
 * logo's edge becomes a ramp, and the line is the middle of that ramp, fading out to either side.
 * The result is scaled down to [OUT] px with filtering, so curves and diagonals stay smooth.
 */
object PackStyleIcons {
    private const val WORK = 512
    private const val OUT = 256

    /** The traced mark's longest side, as a share of the icon: about what pack icons fill. */
    private const val MARK = 0.72f

    /** Blur radius (px at [WORK]) that turns the logo's edge into a ramp; twice applied. */
    private const val SOFTEN = 3

    /** Half the line's width, in how far along the edge ramp it reaches: thin, like line packs. */
    private const val LINE = 0.38f

    /** How far from the background colour (sum of RGB differences) a pixel starts and ends being logo. */
    private const val INK_FROM = 50f
    private const val INK_TO = 130f

    /** By package, its update time and label, so a new version is traced again. */
    private val cache = LruCache<String, Bitmap>(48)

    fun make(context: Context, pkg: String, label: String, icon: Drawable): Drawable {
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg:$version:$label"
        val art = cache.get(key) ?: (traced(icon) ?: initials(label)).also { cache.put(key, it) }
        AppearanceStore.ensureLoaded(context)
        val colour = IconPackStore.tint(context) ?: Palettes.resolve(AppearanceStore.config).text.toArgb()
        val drawable = BitmapDrawable(context.resources, art).apply {
            setTint(colour)
            isFilterBitmap = true
        }
        return PackIconDrawable(drawable, 0f, themed = true)
    }

    /** The logo's outline, or null when there is no mark to trace. */
    internal fun traced(icon: Drawable): Bitmap? {
        val layer = (icon as? AdaptiveIconDrawable)?.foreground ?: icon
        val background = (icon as? AdaptiveIconDrawable)?.background?.let(FittedArt::edgeColour)
            ?: FittedArt.edgeColour(icon)
        val pixels = render(layer)
        // How much each pixel is logo, 0..1, with the icon's own anti-aliasing kept.
        val ink = FloatArray(WORK * WORK) { i ->
            val p = pixels[i]
            val shown = ramp(Color.alpha(p) / 255f, 0.25f, 0.75f)
            if (background == null) shown else shown * ramp(distance(p, background).toFloat(), INK_FROM, INK_TO)
        }
        val box = bounds(ink) ?: return null
        val share = ink.count { it >= 0.5f }.toFloat() / (WORK * WORK)
        // Next to nothing, or a solid block that is the whole icon: no mark to trace.
        if (share < 0.005f || share > 0.6f) return null
        val soft = blur(blur(fit(ink, box), SOFTEN), SOFTEN)
        val line = IntArray(WORK * WORK) { i ->
            val a = (1f - kotlin.math.abs(soft[i] - 0.5f) / LINE).coerceIn(0f, 1f)
            // Smoothstep the falloff so the line's sides fade rather than step.
            val alpha = a * a * (3f - 2f * a)
            Color.argb((alpha * 255).roundToInt(), 255, 255, 255)
        }
        val full = Bitmap.createBitmap(line, WORK, WORK, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(full, OUT, OUT, true).also { if (it !== full) full.recycle() }
    }

    private fun render(drawable: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(WORK, WORK, Bitmap.Config.ARGB_8888)
        val old = drawable.copyBounds()
        drawable.setBounds(0, 0, WORK, WORK)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = old
        val pixels = IntArray(WORK * WORK)
        bitmap.getPixels(pixels, 0, WORK, 0, 0, WORK, WORK)
        bitmap.recycle()
        return pixels
    }

    private fun ramp(v: Float, from: Float, to: Float): Float = ((v - from) / (to - from)).coerceIn(0f, 1f)

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** Left, top, right, bottom of the mark (inclusive), or null when there is none. */
    private fun bounds(ink: FloatArray): IntArray? {
        var l = WORK
        var t = WORK
        var r = -1
        var b = -1
        for (y in 0 until WORK) for (x in 0 until WORK) {
            if (ink[y * WORK + x] < 0.5f) continue
            l = min(l, x)
            t = min(t, y)
            r = max(r, x)
            b = max(b, y)
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    /** The mark scaled so its longest side is [MARK] of the icon, centred, sampled smoothly. */
    private fun fit(ink: FloatArray, box: IntArray): FloatArray {
        val w = box[2] - box[0] + 1
        val h = box[3] - box[1] + 1
        val scale = WORK * MARK / max(w, h)
        val ox = (WORK - w * scale) / 2f
        val oy = (WORK - h * scale) / 2f
        fun at(x: Int, y: Int): Float =
            if (x < box[0] || y < box[1] || x > box[2] || y > box[3]) 0f else ink[y * WORK + x]
        return FloatArray(WORK * WORK) { i ->
            val sx = (i % WORK - ox) / scale + box[0]
            val sy = (i / WORK - oy) / scale + box[1]
            val x0 = kotlin.math.floor(sx).toInt()
            val y0 = kotlin.math.floor(sy).toInt()
            val fx = sx - x0
            val fy = sy - y0
            val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
            val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
            top * (1 - fy) + bottom * fy
        }
    }

    /** Box blur of radius [r], horizontal then vertical. */
    private fun blur(src: FloatArray, r: Int): FloatArray {
        val n = 2 * r + 1
        val mid = FloatArray(WORK * WORK)
        for (y in 0 until WORK) {
            var sum = 0f
            for (x in -r..r) sum += src[y * WORK + x.coerceIn(0, WORK - 1)]
            for (x in 0 until WORK) {
                mid[y * WORK + x] = sum / n
                sum += src[y * WORK + (x + r + 1).coerceAtMost(WORK - 1)] - src[y * WORK + (x - r).coerceAtLeast(0)]
            }
        }
        val out = FloatArray(WORK * WORK)
        for (x in 0 until WORK) {
            var sum = 0f
            for (y in -r..r) sum += mid[y.coerceIn(0, WORK - 1) * WORK + x]
            for (y in 0 until WORK) {
                out[y * WORK + x] = sum / n
                sum += mid[(y + r + 1).coerceAtMost(WORK - 1) * WORK + x] - mid[(y - r).coerceAtLeast(0) * WORK + x]
            }
        }
        return out
    }

    /** One or two letters in a thin outlined rounded square. */
    internal fun initials(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = OUT * 0.016f
        }
        val inset = OUT * 0.16f
        canvas.drawRoundRect(RectF(inset, inset, OUT - inset, OUT - inset), OUT * 0.14f, OUT * 0.14f, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = OUT * 0.26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val y = OUT / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(lettersOf(label), OUT / 2f, y, text)
        return bitmap
    }

    /** "Live Channels" → "LC", "LiveSubs" → "LS", "Settings" → "Se". */
    internal fun lettersOf(label: String): String {
        val words = label.split(Regex("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
            else -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        }
    }
}
