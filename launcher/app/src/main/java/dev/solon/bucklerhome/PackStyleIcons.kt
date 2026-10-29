package dev.solon.bucklerhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
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
fun appIcon(
    context: Context,
    component: ComponentName?,
    label: String,
    packages: Collection<String>,
    own: () -> Drawable?,
): Drawable? {
    component?.let { IconPacks.iconFor(context, it, label) }?.let { return it }
    val icon = own()
    if (icon == null || component == null || !IconPackStore.fillGaps(context) || !IconPacks.isActive(context)) return icon
    return PackStyleIcons.make(context, component.packageName, label, icon, packages)
}

/**
 * Icons for apps a pack has no icon for, made to match the pack's own ([PackStyle], measured from
 * its icons): its line width and colour, the mark's size, and its backing plate when its icons are
 * layered. A pack with its own rule for such apps ([PackFallback]) gets that rule instead, and a
 * full-colour pack without one leaves the app's icon as it is, since no traced line would match.
 *
 * The app's logo is traced on a soft (anti-aliased) copy at [WORK] px: blurred, its edge becomes a
 * ramp, and the line is the band of that ramp as wide as the pack's lines, with a one-pixel soft
 * edge; a filled pack gets the logo's solid shape instead. Only an icon with no mark at all gets
 * the app's initials. The result is scaled down to [OUT] px with filtering.
 */
object PackStyleIcons {
    private const val WORK = 512
    private const val OUT = 256

    /** How far from the background colour (sum of RGB differences) a pixel starts and ends being logo. */
    private const val INK_FROM = 50f
    private const val INK_TO = 130f

    /** Arcticons' measured look, for a pack whose icons could not be read. */
    private val FALLBACK_STYLE = PackStyle(line = 0.0215f, mark = 0.84f, ink = Color.WHITE, plate = null, filled = false, colourful = false)

    /** By package, its update time, label and the pack's look, so a change draws it again. */
    private val cache = LruCache<String, Bitmap>(48)

    fun make(context: Context, pkg: String, label: String, icon: Drawable, packages: Collection<String>): Drawable {
        val style = IconPacks.style(context, packages) ?: FALLBACK_STYLE
        val rule = IconPacks.fallback(context)
        // A full-colour pack: drawn like its own icons when they share a shape (the app's colour
        // filling it, its logo on top), else by the pack's own rule, else just at the pack's size.
        if (style.colourful && (style.shape != null || rule == null)) {
            val margin = if (style.plate != null) 0f else IconPacks.FLAT_MARGIN
            return PackIconDrawable(BitmapDrawable(context.resources, reshaped(icon, style)), margin, themed = false)
        }
        if (rule != null) return PackIconDrawable(BitmapDrawable(context.resources, rule.apply(icon)), 0f, themed = false)
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg:$version:$label:$style"
        val art = cache.get(key) ?: (traced(icon, style) ?: initials(label, style)).also { cache.put(key, it) }
        val tint = IconPackStore.tint(context)
        val glyph = BitmapDrawable(context.resources, art).apply {
            setTint(tint ?: style.ink)
            isFilterBitmap = true
        }
        val plate = style.plate
        return when {
            // A layered pack (Lawnicons): tinted, only the symbol shows, as with its own icons.
            plate != null && tint != null -> PackIconDrawable(glyph, 0f, themed = true)
            plate != null -> PackIconDrawable(AdaptiveIconDrawable(ColorDrawable(plate), glyph), 0f, themed = false)
            else -> PackIconDrawable(glyph, IconPacks.FLAT_MARGIN, themed = tint != null)
        }
    }

    /**
     * The app's own icon in a full-colour pack's look: the pack's shared outline filled with the
     * app's own background colour, and the app's logo on it at the size pack logos sit (an
     * adaptive icon's foreground layer; a flat icon whole, on its matching colour); or, for a pack
     * with free-form icons, simply the app's icon at the size the pack's icons are drawn.
     */
    internal fun reshaped(icon: Drawable, style: PackStyle): Bitmap {
        val bitmap = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shape = style.shape
        if (shape == null) {
            val side = (OUT * style.mark).roundToInt()
            val at = (OUT - side) / 2
            drawInto(icon, canvas, at, at, at + side, at + side)
            return bitmap
        }
        val box = shapeBounds(shape)
        val adaptive = icon as? AdaptiveIconDrawable
        canvas.drawColor(adaptive?.background?.let(FittedArt::edgeColour) ?: FittedArt.edgeColour(icon) ?: FILL_FALLBACK)
        val side = box[2] - box[0]
        val cx = (box[0] + box[2]) / 2
        val cy = (box[1] + box[3]) / 2
        val foreground = adaptive?.foreground
        if (foreground != null) {
            // A layer is 108 units with the logo in the middle 72: that middle takes [LOGO] of the shape.
            val half = (side * LOGO * 108f / 72f / 2f).roundToInt()
            drawInto(foreground, canvas, cx - half, cy - half, cx + half, cy + half)
        } else {
            val half = (side * LOGO / 2f).roundToInt()
            drawInto(icon, canvas, cx - half, cy - half, cx + half, cy + half)
        }
        // The averaged outline is soft (icons' edges and faint shadows blend): cut it at its middle,
        // with a narrow ramp for anti-aliasing, so the circle is the pack's size and has no halo.
        val mask = Bitmap.createBitmap(
            IntArray(PackStyle.MASK * PackStyle.MASK) {
                val a = ((shape[it] - 0.5f) * 4f + 0.5f).coerceIn(0f, 1f)
                Color.argb((a * 255).roundToInt(), 255, 255, 255)
            },
            PackStyle.MASK, PackStyle.MASK, Bitmap.Config.ARGB_8888,
        )
        val scaled = Bitmap.createScaledBitmap(mask, OUT, OUT, true)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        if (scaled !== mask) scaled.recycle()
        mask.recycle()
        return bitmap
    }

    /** How much of a full-colour pack's shape an app's logo takes, about as its own icons do. */
    private const val LOGO = 0.72f

    /** Fill for an icon with no background colour to take (a logo cut out on nothing). */
    private const val FILL_FALLBACK = 0xFF3A3A3E.toInt()

    /** The shape's left, top, right, bottom at [OUT] px. */
    private fun shapeBounds(shape: FloatArray): IntArray {
        val m = PackStyle.MASK
        var l = m
        var t = m
        var r = -1
        var b = -1
        for (y in 0 until m) for (x in 0 until m) {
            if (shape[y * m + x] < 0.5f) continue
            l = min(l, x)
            t = min(t, y)
            r = max(r, x)
            b = max(b, y)
        }
        if (r < 0) return intArrayOf(0, 0, OUT, OUT)
        val k = OUT.toFloat() / m
        return intArrayOf((l * k).roundToInt(), (t * k).roundToInt(), ((r + 1) * k).roundToInt(), ((b + 1) * k).roundToInt())
    }

    private fun drawInto(drawable: Drawable, canvas: Canvas, l: Int, t: Int, r: Int, b: Int) {
        val old = drawable.copyBounds()
        drawable.setBounds(l, t, r, b)
        drawable.draw(canvas)
        drawable.bounds = old
    }

    /** The logo's outline, or null when there is no mark to trace. */
    internal fun traced(icon: Drawable, style: PackStyle): Bitmap? {
        val layer = (icon as? AdaptiveIconDrawable)?.foreground ?: icon
        val background = (icon as? AdaptiveIconDrawable)?.background?.let(FittedArt::edgeColour)
            ?: FittedArt.edgeColour(icon)
        val pixels = render(layer)
        // How much each pixel is logo, 0..1, with the icon's own anti-aliasing kept.
        val ink = FloatArray(WORK * WORK) { i ->
            val p = pixels[i]
            val shown = ramp(Color.alpha(p) / 255f, 0.25f, 0.75f)
            if (background == null) shown else shown * ramp(colourDistance(p, background).toFloat(), INK_FROM, INK_TO)
        }
        val box = bounds(ink) ?: return null
        val share = ink.count { it >= 0.5f }.toFloat() / (WORK * WORK)
        // Next to nothing, or a solid block that is the whole icon: no mark to trace.
        if (share < 0.005f || share > 0.6f) return null
        // A crisp edge first: the blur below then ramps it predictably, so width is what is asked.
        val fitted = fit(ink, box, style.mark).also { f ->
            for (i in f.indices) f[i] = ((f[i] - 0.5f) * 6f + 0.5f).coerceIn(0f, 1f)
        }
        val line = style.line * WORK
        // How thick the logo's own parts are. Parts not clearly thicker than two of the pack's
        // lines would outline into each other (thin bars, a busy logo under heavy lines): draw
        // them solid instead, as strokes, thickened to the pack's line width if they are thinner.
        val parts = PackStyle.strokeWidth(BooleanArray(WORK * WORK) { fitted[it] >= 0.5f }, WORK)
        if (style.filled || parts < line * 2.5f) {
            return draw(fitted, line, solid = true, grow = max(0f, (line - parts) / 2f))
        }
        var width = line
        var art = draw(fitted, width, solid = false)
        // Measure the result as the pack's icons were measured, and correct until it matches.
        repeat(3) {
            val got = PackStyle.measure(listOf(BitmapDrawable(null, art)))?.line ?: return art
            // Close enough not to be noticed beside the pack's own icons.
            if (got <= 0f || kotlin.math.abs(got - style.line) <= style.line * 0.06f) return art
            width *= style.line / got
            art.recycle()
            art = draw(fitted, width, solid = false)
        }
        return art
    }

    /**
     * The traced line ([width] px at [WORK]) or, [solid], the shape itself grown by [grow] px,
     * at [OUT] px.
     */
    private fun draw(fitted: FloatArray, width: Float, solid: Boolean, grow: Float = 0f): Bitmap {
        val half = width / 2f
        // Solid shapes blur only as far as they grow, so the logo's narrow gaps stay open.
        val r = kotlin.math.ceil(if (solid) grow else half).toInt() + 2
        val n = 2 * r + 1
        // After two box blurs a hard edge ramps with slope 1/n at its middle: distance ≈ |s - ½|·n.
        val soft = blur(blur(fitted, r), r)
        val pixels = IntArray(WORK * WORK) { i ->
            val alpha = if (solid) {
                ((soft[i] - 0.5f) * n + 0.5f + grow).coerceIn(0f, 1f)
            } else {
                (half - kotlin.math.abs(soft[i] - 0.5f) * n + 0.5f).coerceIn(0f, 1f)
            }
            Color.argb((alpha * 255).roundToInt(), 255, 255, 255)
        }
        val full = Bitmap.createBitmap(pixels, WORK, WORK, Bitmap.Config.ARGB_8888)
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

    /** The mark scaled so its longest side is [mark] of the icon, centred, sampled smoothly. */
    private fun fit(ink: FloatArray, box: IntArray, mark: Float): FloatArray {
        val w = box[2] - box[0] + 1
        val h = box[3] - box[1] + 1
        val scale = WORK * mark / max(w, h)
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

    /** One or two letters in an outlined rounded square, at the pack's line width and size. */
    internal fun initials(label: String, look: PackStyle): Bitmap {
        val bitmap = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = OUT * look.line
        }
        val inset = OUT * (1f - look.mark) / 2f + line.strokeWidth / 2f
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
