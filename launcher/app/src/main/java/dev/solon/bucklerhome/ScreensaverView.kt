package dev.solon.bucklerhome

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FADE_MS = 1_500L
private const val MAX_SLIDE_FAILURES = 6
private val DVD_COLORS = intArrayOf(
    0xFFD4A84B.toInt(), 0xFF8FAE7A.toInt(), 0xFF5DA9E9.toInt(), 0xFFE9728B.toInt(), 0xFFB48AE0.toInt(),
)

/**
 * The whole screensaver, shared by the system DreamService and the in-app preview so both always
 * behave the same. Plain views (not Compose) keep the dream light on the SHIELD.
 */
class ScreensaverView(context: Context, private val config: ScreensaverConfig) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = resources.displayMetrics.density
    private val clock = textView(sizeSp = 64f, light = true)
    private var driftX: ObjectAnimator? = null
    private var driftY: ObjectAnimator? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var nowPlaying: NowPlayingSaver? = null

    private val imageA = slideImage()
    private val imageB = slideImage()
    private var frontIsA = true
    private val credit = textView(sizeSp = 13f, light = false).apply { alpha = 0.75f }
    private val overlayClock = textView(sizeSp = 34f, light = true).apply { alpha = 0.9f }
    private val notice = textView(sizeSp = 20f, light = false).apply { alpha = 0.7f }

    init {
        setBackgroundColor(Palettes.of(config.theme).bgTop.toArgb())
    }

    fun start() {
        scope.launch {
            while (true) {
                val now = DisplayFormat.time(LocalTime.now(), config.use24h)
                clock.text = now
                overlayClock.text = now
                delay(15_000)
            }
        }
        when (config.mode) {
            ScreensaverMode.CLOCK -> startDriftingClock()
            ScreensaverMode.DVD -> startDvd()
            ScreensaverMode.PHOTOS, ScreensaverMode.UPLOADED -> startSlideshow()
            ScreensaverMode.NOW_PLAYING -> startNowPlaying()
            ScreensaverMode.MAZE -> startScene(MazeScene(context))
            ScreensaverMode.PIPES -> startScene(PipesScene(context))
            ScreensaverMode.MYSTIFY -> startScene(MystifyScene(context))
            ScreensaverMode.STARFIELD -> startScene(StarfieldScene(context))
        }
    }

    fun stop() {
        scope.cancel()
        nowPlaying?.stop()
        nowPlaying = null
        driftX?.cancel()
        driftY?.cancel()
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    // --- Drifting clock -------------------------------------------------------------------

    private fun startDriftingClock() {
        addView(clock, centered())
        scope.launch {
            while (true) {
                drift()
                delay(45_000)
            }
        }
    }

    private fun drift() {
        if (width == 0 || height == 0) return
        val maxX = (width - clock.width).coerceAtLeast(0)
        val maxY = (height - clock.height).coerceAtLeast(0)
        val targetX = if (maxX == 0) 0f else Random.nextInt(maxX).toFloat() - maxX / 2f
        val targetY = if (maxY == 0) 0f else Random.nextInt(maxY).toFloat() - maxY / 2f
        driftX?.cancel()
        driftY?.cancel()
        driftX = glide("translationX", clock.translationX, targetX)
        driftY = glide("translationY", clock.translationY, targetY)
    }

    private fun glide(property: String, from: Float, to: Float): ObjectAnimator =
        ObjectAnimator.ofFloat(clock, property, from, to).apply {
            duration = 4_500L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    // --- Now Playing and canvas scenes ----------------------------------------------------

    private fun startNowPlaying() {
        val saver = NowPlayingSaver(context, config)
        nowPlaying = saver
        addView(saver, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        saver.start()
    }

    /** Canvas scenes animate themselves while attached; the clock overlay sits on top. */
    private fun startScene(scene: View) {
        setBackgroundColor(Color.BLACK)
        addView(scene, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (config.showClock) {
            addView(overlayClock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(40), dp(24)) })
        }
    }

    // --- DVD bounce -----------------------------------------------------------------------

    private fun startDvd() {
        val mark = ImageView(context).apply { setImageResource(config.theme.markRes) }
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(mark, LinearLayout.LayoutParams(dp(110), dp(110)))
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            addView(clock, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(block, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        var colorIndex = 0
        // The mark keeps the colour scheme's colours, so only the clock text takes the bounce colour.
        fun recolor() {
            colorIndex = (colorIndex + 1) % DVD_COLORS.size
            clock.setTextColor(DVD_COLORS[colorIndex])
        }
        recolor()

        val speed = 150f * density
        val physics = DvdBounce(x = 0f, y = 0f, vx = speed, vy = speed * 0.72f)
        var lastNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNanos != 0L && block.width > 0) {
                    val dt = ((frameTimeNanos - lastNanos) / 1e9f).coerceAtMost(0.1f)
                    val hit = physics.step(dt, (width - block.width).toFloat(), (height - block.height).toFloat())
                    if (hit.any) recolor()
                    block.translationX = physics.x
                    block.translationY = physics.y
                }
                lastNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    // --- Photo slideshow ------------------------------------------------------------------

    private sealed interface Slide {
        data class Local(val file: File) : Slide
        data class Remote(val image: CommonsImage) : Slide
    }

    private fun startSlideshow() {
        addView(imageA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(credit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(40), 0, 0, dp(28)) })
        if (config.showClock) {
            addView(overlayClock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(40), dp(24)) })
        }
        scope.launch {
            val slides = withContext(Dispatchers.IO) { loadSlides() }
            if (slides.isEmpty()) {
                fallBackToClock(
                    when {
                        config.mode == ScreensaverMode.UPLOADED -> "No photos uploaded yet"
                        ScreensaverImages.online(context) -> "No photos found right now"
                        else -> "Photos unavailable offline"
                    },
                )
                return@launch
            }
            var failures = 0
            var index = 0
            while (true) {
                val slide = slides[index % slides.size]
                index++
                val shown = withContext(Dispatchers.IO) { resolve(slide) }
                if (shown == null) {
                    if (++failures >= MAX_SLIDE_FAILURES) {
                        fallBackToClock("Photos unavailable")
                        return@launch
                    }
                    continue
                }
                failures = 0
                show(shown.first, shown.second)
                delay(config.intervalSeconds * 1_000L)
            }
        }
    }

    private fun loadSlides(): List<Slide> = when (config.mode) {
        ScreensaverMode.UPLOADED -> ScreensaverImages.uploaded(context).shuffled().map { Slide.Local(it) }
        ScreensaverMode.PHOTOS ->
            ScreensaverImages.takeTurns(
                PhotoCategory.entries.filter { it in config.categories }
                    .map { ScreensaverImages.onlineIndex(context, it) },
            ).map { Slide.Remote(it) }
        ScreensaverMode.CLOCK, ScreensaverMode.DVD, ScreensaverMode.NOW_PLAYING, ScreensaverMode.MAZE,
        ScreensaverMode.PIPES, ScreensaverMode.MYSTIFY, ScreensaverMode.STARFIELD,
        -> emptyList()
    }

    private fun resolve(slide: Slide): Pair<Bitmap, String?>? = when (slide) {
        is Slide.Local -> ScreensaverImages.decode(slide.file)?.let { it to null }
        is Slide.Remote -> ScreensaverImages.download(context, slide.image)
            ?.let { ScreensaverImages.decode(it) }
            ?.let { it to slide.image.credit }
    }

    private fun show(bitmap: Bitmap, creditText: String?) {
        val incoming = if (frontIsA) imageB else imageA
        val outgoing = if (frontIsA) imageA else imageB
        frontIsA = !frontIsA
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { outgoing.setImageDrawable(null) }.start()
        // Slow Ken Burns zoom keeps a still photo from feeling frozen (skipped with reduced motion).
        if (!config.reduceMotion) {
            ObjectAnimator.ofFloat(incoming, View.SCALE_X, 1f, 1.08f).setDuration(config.intervalSeconds * 1_000L + FADE_MS).start()
            ObjectAnimator.ofFloat(incoming, View.SCALE_Y, 1f, 1.08f).setDuration(config.intervalSeconds * 1_000L + FADE_MS).start()
        }
        credit.text = creditText.orEmpty()
    }

    private fun fallBackToClock(message: String) {
        removeAllViews()
        notice.text = message
        addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(0, 0, 0, dp(40)) })
        startDriftingClock()
    }

    // --- helpers --------------------------------------------------------------------------

    private fun centered() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun slideImage() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    private fun textView(sizeSp: Float, light: Boolean) = TextView(context).apply {
        setTextColor(Color.parseColor("#E8ECF2"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = config.font.typeface(context, light)
        gravity = Gravity.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
}
