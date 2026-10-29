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
 * for. The app's own logo is traced as an outline when it is a simple mark (a letter, a gear,
 * a few bars); a busy picture would trace as noise, so it gets the app's initials in an outlined
 * badge instead. Both are drawn in the theme's text colour, as a themed pack is.
 */
object PackStyleIcons {
    private const val SIZE = 192

    /** The traced mark's longest side, as a share of the icon: about what pack icons fill. */
    private const val MARK = 0.72f

    /** Line width, matching the thin strokes of line packs at TV tile sizes. */
    private const val STROKE = 2

    /** Pixels further than this from the background colour are part of the logo. */
    private const val INK_DISTANCE = 90

    /** By package, its update time and label, so a new version is traced again. */
    private val cache = LruCache<String, Bitmap>(48)

    fun make(context: Context, pkg: String, label: String, icon: Drawable): Drawable {
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg:$version:$label"
        val art = cache.get(key) ?: (traced(icon) ?: initials(label)).also { cache.put(key, it) }
        AppearanceStore.ensureLoaded(context)
        val colour = IconPackStore.tint(context) ?: Palettes.resolve(AppearanceStore.config).text.toArgb()
        val drawable = BitmapDrawable(context.resources, art).apply { setTint(colour) }
        return PackIconDrawable(drawable, 0f, themed = true)
    }

    /** The logo's outline, or null when there is no clear mark to trace. */
    internal fun traced(icon: Drawable): Bitmap? {
        val layer = (icon as? AdaptiveIconDrawable)?.foreground ?: icon
        val background = (icon as? AdaptiveIconDrawable)?.background?.let(FittedArt::edgeColour)
            ?: FittedArt.edgeColour(icon)
        val pixels = render(layer)
        val ink = BooleanArray(SIZE * SIZE) { i ->
            val p = pixels[i]
            Color.alpha(p) >= 128 && (background == null || distance(p, background) > INK_DISTANCE)
        }
        val box = bounds(ink) ?: return null
        val share = ink.count { it }.toFloat() / (SIZE * SIZE)
        // Next to nothing, or a solid block that is the whole icon: no mark to trace.
        if (share < 0.01f || share > 0.6f) return null
        if (busy(ink, box)) return null
        return outline(fit(ink, box))
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

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** Left, top, right, bottom of the mark (inclusive), or null when there is none. */
    private fun bounds(ink: BooleanArray): IntArray? {
        var l = SIZE
        var t = SIZE
        var r = -1
        var b = -1
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!ink[y * SIZE + x]) continue
            l = min(l, x)
            t = min(t, y)
            r = max(r, x)
            b = max(b, y)
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    /** Text, photos and gradients switch between mark and background many times per row. */
    private fun busy(ink: BooleanArray, box: IntArray): Boolean {
        var switches = 0
        var rows = 0
        for (y in box[1]..box[3]) {
            var row = 0
            for (x in box[0] + 1..box[2]) if (ink[y * SIZE + x] != ink[y * SIZE + x - 1]) row++
            if (row > 0) {
                switches += row
                rows++
            }
        }
        return rows == 0 || switches.toFloat() / rows > 6f
    }

    /** The mark scaled so its longest side is [MARK] of the icon, centred. */
    private fun fit(ink: BooleanArray, box: IntArray): BooleanArray {
        val w = box[2] - box[0] + 1
        val h = box[3] - box[1] + 1
        val scale = SIZE * MARK / max(w, h)
        val ox = (SIZE - w * scale) / 2f
        val oy = (SIZE - h * scale) / 2f
        return BooleanArray(SIZE * SIZE) { i ->
            val sx = ((i % SIZE - ox) / scale).roundToInt() + box[0]
            val sy = ((i / SIZE - oy) / scale).roundToInt() + box[1]
            sx in box[0]..box[2] && sy in box[1]..box[3] && ink[sy * SIZE + sx]
        }
    }

    /** The mark's edge as a line [STROKE] px either side, white on transparent (tinted when drawn). */
    private fun outline(ink: BooleanArray): Bitmap {
        val edge = BooleanArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!ink[y * SIZE + x]) continue
            val border = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1 ||
                !ink[y * SIZE + x - 1] || !ink[y * SIZE + x + 1] || !ink[(y - 1) * SIZE + x] || !ink[(y + 1) * SIZE + x]
            if (border) edge[y * SIZE + x] = true
        }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!edge[y * SIZE + x]) continue
            for (dy in -STROKE..STROKE) for (dx in -STROKE..STROKE) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until SIZE && ny in 0 until SIZE) pixels[ny * SIZE + nx] = Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** One or two letters in a thin outlined rounded square. */
    internal fun initials(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = STROKE * 2.5f
        }
        val inset = SIZE * 0.16f
        canvas.drawRoundRect(RectF(inset, inset, SIZE - inset, SIZE - inset), SIZE * 0.14f, SIZE * 0.14f, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = SIZE * 0.26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val y = SIZE / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(lettersOf(label), SIZE / 2f, y, text)
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
