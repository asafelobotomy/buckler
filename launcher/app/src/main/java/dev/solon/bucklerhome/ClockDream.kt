package dev.solon.bucklerhome

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Minimal drifting clock screensaver — no network, no photos.
 */
class ClockDream : DreamService() {
    private lateinit var clock: TextView
    private var driftX: ObjectAnimator? = null
    private var driftY: ObjectAnimator? = null
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")
    private val tick = object : Runnable {
        override fun run() {
            if (!::clock.isInitialized) return
            clock.text = LocalTime.now().format(formatter)
            animateDrift()
            clock.postDelayed(this, 45_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }
        clock = TextView(this).apply {
            setTextColor(Color.parseColor("#E8ECF2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        root.addView(
            clock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)
        clock.post { tick.run() }
    }

    override fun onDetachedFromWindow() {
        if (::clock.isInitialized) {
            clock.removeCallbacks(tick)
        }
        driftX?.cancel()
        driftY?.cancel()
        super.onDetachedFromWindow()
    }

    private fun animateDrift() {
        val parent = clock.parent as? FrameLayout ?: return
        if (parent.width == 0 || parent.height == 0) return
        val maxX = (parent.width - clock.width).coerceAtLeast(0)
        val maxY = (parent.height - clock.height).coerceAtLeast(0)
        val targetX = if (maxX == 0) 0f else Random.nextInt(maxX).toFloat() - maxX / 2f
        val targetY = if (maxY == 0) 0f else Random.nextInt(maxY).toFloat() - maxY / 2f

        driftX?.cancel()
        driftY?.cancel()
        driftX = ObjectAnimator.ofFloat(clock, "translationX", clock.translationX, targetX).apply {
            duration = 4_500L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        driftY = ObjectAnimator.ofFloat(clock, "translationY", clock.translationY, targetY).apply {
            duration = 4_500L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
