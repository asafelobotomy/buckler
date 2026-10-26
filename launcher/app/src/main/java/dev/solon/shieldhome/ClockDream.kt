package dev.solon.shieldhome

import android.graphics.Color
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
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
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")
    private val tick = object : Runnable {
        override fun run() {
            if (!::clock.isInitialized) return
            clock.text = LocalTime.now().format(formatter)
            drift()
            clock.postDelayed(this, 30_000L)
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
        tick.run()
    }

    override fun onDetachedFromWindow() {
        if (::clock.isInitialized) {
            clock.removeCallbacks(tick)
        }
        super.onDetachedFromWindow()
    }

    private fun drift() {
        val parent = clock.parent as? FrameLayout ?: return
        val maxX = (parent.width - clock.width).coerceAtLeast(0)
        val maxY = (parent.height - clock.height).coerceAtLeast(0)
        clock.translationX = if (maxX == 0) 0f else Random.nextInt(maxX).toFloat() - maxX / 2f
        clock.translationY = if (maxY == 0) 0f else Random.nextInt(maxY).toFloat() - maxY / 2f
    }
}
