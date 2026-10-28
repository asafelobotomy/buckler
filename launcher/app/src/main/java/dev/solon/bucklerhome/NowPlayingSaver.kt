package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val YOUTUBE_RED = 0xFFFF0033.toInt()
private const val TWITCH_PURPLE = 0xFF9146FF.toInt()
private const val LIVE_RED = 0xFFE53935.toInt()
private const val NUDGE_MS = 60_000L

/**
 * Screensaver style that shows whatever is playing: album art for music, a full-screen backdrop
 * for films and TV, a thumbnail with the channel for YouTube, a LIVE card for Twitch and IPTV
 * (TiviMate and friends). Video apps usually drop their session once covered, so the last thing
 * seen in the past half hour is shown as paused. Otherwise a clock.
 */
class NowPlayingSaver(context: Context, private val config: ScreensaverConfig) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model = NowPlayingModel(context)
    private val density = resources.displayMetrics.density
    private var shownKey: String? = null
    private var artJob: Job? = null
    private var block: View? = null
    private val clock = text(22f, light = true).apply { alpha = 0.85f }

    fun start() {
        setBackgroundColor(Color.BLACK)
        model.onChange = { render() }
        model.start()
        render()
        scope.launch {
            while (true) {
                clock.text = DisplayFormat.time(LocalTime.now(), config.use24h)
                delay(15_000)
            }
        }
        // Small moves keep a still layout from burning into the panel.
        scope.launch {
            while (true) {
                delay(NUDGE_MS)
                block?.animate()
                    ?.translationX(Random.nextInt(-24, 25) * density)
                    ?.translationY(Random.nextInt(-16, 17) * density)
                    ?.setDuration(3_000)?.start()
            }
        }
    }

    fun stop() {
        model.onChange = null
        model.stop()
        scope.cancel()
    }

    private fun render() {
        val info = model.info ?: RecentMedia.recent()
        val key = info?.let { "${it.packageName}|${it.title}|${it.subtitle}|${it.playing}|${it.art?.generationId}|${it.artUri}" }
            ?: "idle:${model.accessGranted}"
        if (key == shownKey) return
        shownKey = key
        artJob?.cancel()
        removeAllViews()
        (clock.parent as? ViewGroup)?.removeView(clock)
        setBackgroundColor(Color.BLACK)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        if (info == null) {
            showIdle()
            return
        }
        val backdrop = image(ImageView.ScaleType.CENTER_CROP).apply { alpha = 0f }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val art = when (info.kind) {
            MediaKind.MUSIC -> showMusic(info)
            MediaKind.VIDEO -> showVideo(info)
            MediaKind.YOUTUBE -> showStream(info, chip("YouTube", YOUTUBE_RED))
            MediaKind.TWITCH -> showStream(info, chip("● LIVE", TWITCH_PURPLE))
            MediaKind.LIVE_TV -> showLiveTv(info)
        }
        // The corner clock while something plays; with nothing playing the big clock always shows.
        if (config.showClock) {
            addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(28), dp(40), 0) })
        }
        artJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadArt(info) }
            val bitmap = loaded ?: withContext(Dispatchers.IO) { appArt(info.packageName) } ?: return@launch
            if (info.kind == MediaKind.VIDEO && loaded != null) {
                backdrop.setImageBitmap(bitmap)
                backdrop.animate().alpha(1f).setDuration(1_200).start()
                return@launch
            }
            art.setImageBitmap(bitmap)
            art.visibility = View.VISIBLE
            if (info.kind != MediaKind.LIVE_TV && config.on(SaverOption.BLUR)) {
                backdrop.setImageBitmap(softBlur(bitmap))
                backdrop.animate().alpha(0.4f).setDuration(1_200).start()
            }
        }
    }

    private fun showIdle() {
        val column = column(Gravity.CENTER_HORIZONTAL)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        column.addView(clock)
        val note = if (model.accessGranted) "Nothing playing" else "Allow media access in Settings → Header to show Now Playing"
        column.addView(
            text(18f, light = false).apply { text = note; alpha = 0.7f },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        place(column, Gravity.CENTER)
    }

    private fun showMusic(info: NowPlayingInfo): ImageView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = rounded(image(ImageView.ScaleType.CENTER_CROP))
        row.addView(art, LinearLayout.LayoutParams(dp(260), dp(260)))
        val column = column(Gravity.START).apply { setPadding(dp(40), 0, 0, 0) }
        column.addView(text(34f, light = false, bold = true).apply { text = info.title; maxLines = 2 })
        if (info.subtitle.isNotBlank()) column.addView(text(20f, light = true).apply { text = info.subtitle; maxLines = 2 })
        column.addView(text(16f, light = false).apply { text = sourceLine(info, "♪"); alpha = 0.7f; setPadding(0, dp(18), 0, 0) })
        row.addView(column, LinearLayout.LayoutParams(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT))
        place(row, Gravity.CENTER)
        return art
    }

    /** Returns a centred card that is only shown when the app sends no artwork (its banner goes there). */
    private fun showVideo(info: NowPlayingInfo): ImageView {
        val shade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000, 0x00000000, 0xDD000000.toInt()),
            )
        }
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val column = column(Gravity.START)
        column.addView(text(36f, light = false, bold = true).apply { text = info.title; maxLines = 2 })
        if (info.subtitle.isNotBlank()) column.addView(text(20f, light = true).apply { text = info.subtitle; maxLines = 2 })
        column.addView(text(16f, light = false).apply { text = sourceLine(info, "▶"); alpha = 0.75f; setPadding(0, dp(10), 0, 0) })
        val card = rounded(image(ImageView.ScaleType.FIT_CENTER)).apply { visibility = View.GONE }
        addView(card, LayoutParams(dp(384), dp(216), Gravity.CENTER).apply { bottomMargin = dp(110) })
        place(column, Gravity.BOTTOM or Gravity.START, marginStart = 48, marginBottom = 40)
        return card
    }

    private fun showStream(info: NowPlayingInfo, badge: TextView): ImageView {
        val column = column(Gravity.CENTER_HORIZONTAL)
        val art = rounded(image(ImageView.ScaleType.CENTER_CROP))
        column.addView(art, LinearLayout.LayoutParams(dp(480), dp(270)))
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        line.addView(badge)
        line.addView(text(24f, light = false, bold = true).apply {
            text = info.title
            maxLines = 1
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(400), LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(line)
        if (info.subtitle.isNotBlank()) column.addView(text(18f, light = true).apply { text = info.subtitle; maxLines = 1 })
        column.addView(text(15f, light = false).apply { text = sourceLine(info, null); alpha = 0.7f })
        place(column, Gravity.CENTER)
        return art
    }

    private fun showLiveTv(info: NowPlayingInfo): ImageView {
        setBackgroundColor(Color.parseColor("#101418"))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = rounded(image(ImageView.ScaleType.FIT_CENTER)).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            (background as GradientDrawable).setColor(Color.parseColor("#1E252D"))
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(220), dp(220)))
        val column = column(Gravity.START).apply { setPadding(dp(40), 0, 0, 0) }
        column.addView(chip("● LIVE", LIVE_RED))
        column.addView(text(32f, light = false, bold = true).apply { text = info.title; maxLines = 2; setPadding(0, dp(14), 0, 0) })
        if (info.subtitle.isNotBlank()) column.addView(text(20f, light = true).apply { text = info.subtitle; maxLines = 2 })
        column.addView(text(16f, light = false).apply { text = sourceLine(info, null); alpha = 0.7f; setPadding(0, dp(14), 0, 0) })
        row.addView(column, LinearLayout.LayoutParams(dp(460), LinearLayout.LayoutParams.WRAP_CONTENT))
        place(row, Gravity.CENTER)
        return logo
    }

    private fun sourceLine(info: NowPlayingInfo, glyph: String?): String {
        val state = if (info.playing) "" else " · Paused"
        return listOfNotNull(glyph, info.appLabel).joinToString(" ") + state
    }

    // --- artwork --------------------------------------------------------------------------

    /**
     * The playing app's artwork. Any app with a media session chooses this URI, so only https and
     * content / resource URIs are read (no file paths), at most [ART_MAX_BYTES], and decoded scaled
     * down to the screen rather than at whatever size the app offers.
     */
    private fun loadArt(info: NowPlayingInfo): Bitmap? {
        info.art?.let { return it }
        val raw = info.artUri ?: return null
        val uri = Uri.parse(raw)
        return runCatching {
            val bytes = when (uri.scheme) {
                "https" -> {
                    val conn = URL(raw).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    try {
                        conn.inputStream.use { readCapped(it) }
                    } finally {
                        conn.disconnect()
                    }
                }
                "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
                else -> null
            } ?: return@runCatching null
            decodeScaled(bytes)
        }.getOrNull()
    }

    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > ART_MAX_BYTES) return null
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > ART_MAX_DIM || bounds.outHeight / sample > ART_MAX_DIM) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Down to 12 px wide and back up in two filtered steps: a soft blur that works on Android 11. */
    private fun softBlur(bitmap: Bitmap): Bitmap {
        fun scaled(src: Bitmap, w: Int) =
            Bitmap.createScaledBitmap(src, w, (w * src.height / src.width.coerceAtLeast(1)).coerceAtLeast(1), true)
        return scaled(scaled(scaled(bitmap, 48), 12), 160)
    }

    /** The playing app's TV banner (or icon) when the session sends no artwork. */
    private fun appArt(packageName: String): Bitmap? = runCatching {
        val drawable = CoverResolver.leanbackBanner(context, packageName)
            ?: context.packageManager.getApplicationIcon(packageName)
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 320
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
        drawable.toBitmap(w * 2, h * 2)
    }.getOrNull()

    // --- view helpers ---------------------------------------------------------------------

    private fun place(view: View, gravity: Int, marginStart: Int = 0, marginBottom: Int = 0) {
        block = view
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity)
            .apply { setMargins(dp(marginStart), 0, 0, dp(marginBottom)) })
    }

    private fun column(gravity: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun image(scale: ImageView.ScaleType) = ImageView(context).apply { scaleType = scale }

    private fun rounded(view: ImageView): ImageView = view.apply {
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.TRANSPARENT)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    private fun chip(label: String, color: Int) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = config.font.typeface(context, light = false)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(color)
        }
    }

    private fun text(sizeSp: Float, light: Boolean, bold: Boolean = false) = TextView(context).apply {
        setTextColor(Color.parseColor("#F2F4F7"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = config.font.typeface(context, light)
        if (bold) paint.isFakeBoldText = true
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

private const val ART_MAX_BYTES = 8 * 1024 * 1024
private const val ART_MAX_DIM = 1080
