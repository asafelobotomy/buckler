package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.random.Random

/** Frame timing shared by the canvas screensavers: seconds since the last frame, capped. */
private class FrameClock {
    private var last = 0L

    fun tick(): Float {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        return dt
    }
}

/**
 * After Windows "Mystify": bouncing quadrilaterals ([shapeCount], two by default) with fading
 * trails [trail] outlines long, slowly changing colour; [speed] 1 = normal.
 */
class MystifyScene(
    context: Context,
    shapeCount: Int = 2,
    private val trail: Int = TRAIL,
    private val speed: Float = 1f,
) : View(context) {
    private class Shape(var hue: Float) {
        val points = FloatArray(8)
        val velocity = FloatArray(8)
        val trail = ArrayDeque<FloatArray>()
    }

    private val random = Random(System.nanoTime())
    private val clock = FrameClock()
    // Hues spread evenly around the wheel so each shape starts in its own colour.
    private val shapes = List(shapeCount.coerceIn(1, 4)) { Shape(it * 360f / shapeCount.coerceIn(1, 4)) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val path = Path()
    private var sinceTrail = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val speed = 180f * resources.displayMetrics.density * speed
        for (shape in shapes) {
            for (i in 0 until 4) {
                shape.points[i * 2] = random.nextFloat() * w
                shape.points[i * 2 + 1] = random.nextFloat() * h
                shape.velocity[i * 2] = (random.nextFloat() * 0.8f + 0.4f) * speed * if (random.nextBoolean()) 1 else -1
                shape.velocity[i * 2 + 1] = (random.nextFloat() * 0.8f + 0.4f) * speed * if (random.nextBoolean()) 1 else -1
            }
            shape.trail.clear()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dt = clock.tick()
        sinceTrail += dt
        val snapshot = sinceTrail >= TRAIL_SECONDS
        if (snapshot) sinceTrail = 0f
        canvas.drawColor(Color.BLACK)
        for (shape in shapes) {
            move(shape, dt)
            shape.hue = (shape.hue + dt * 12f) % 360f
            if (snapshot) {
                shape.trail.addFirst(shape.points.copyOf())
                while (shape.trail.size > trail) shape.trail.removeLast()
            }
            val rgb = Color.HSVToColor(floatArrayOf(shape.hue, 0.85f, 1f))
            shape.trail.forEachIndexed { i, pts ->
                paint.color = rgb
                paint.alpha = (255 * (1f - i / trail.toFloat())).toInt()
                polygon(canvas, pts)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun move(shape: Shape, dt: Float) {
        for (i in 0 until 8) {
            val limit = if (i % 2 == 0) width.toFloat() else height.toFloat()
            var v = shape.points[i] + shape.velocity[i] * dt
            if (v < 0f || v > limit) {
                shape.velocity[i] = -shape.velocity[i]
                v = v.coerceIn(0f, limit)
            }
            shape.points[i] = v
        }
    }

    private fun polygon(canvas: Canvas, pts: FloatArray) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        for (i in 1 until 4) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        path.close()
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val TRAIL = 7
        const val TRAIL_SECONDS = 0.07f
    }
}

/** After Windows "Starfield": flying forward through [stars] stars, white or [tinted]; [speed] 1 = normal. */
class StarfieldScene(
    context: Context,
    private val stars: Int = STARS,
    tinted: Boolean = false,
    private val speed: Float = 1f,
) : View(context) {
    private val random = Random(System.nanoTime())
    private val clock = FrameClock()
    private val xs = FloatArray(stars)
    private val ys = FloatArray(stars)
    private val zs = FloatArray(stars) { random.nextFloat() }
    // Tinted: each star keeps a pale colour of its own.
    private val colours = IntArray(stars) {
        if (tinted) Color.HSVToColor(floatArrayOf(random.nextFloat() * 360f, 0.35f, 1f)) else Color.WHITE
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val density = resources.displayMetrics.density

    init {
        for (i in 0 until stars) respawn(i, z = random.nextFloat())
    }

    private fun respawn(i: Int, z: Float = 1f) {
        xs[i] = random.nextFloat() * 2f - 1f
        ys[i] = random.nextFloat() * 2f - 1f
        zs[i] = z.coerceAtLeast(0.05f)
    }

    override fun onDraw(canvas: Canvas) {
        val dt = clock.tick()
        canvas.drawColor(Color.BLACK)
        val cx = width / 2f
        val cy = height / 2f
        for (i in 0 until stars) {
            zs[i] -= dt * SPEED * speed
            if (zs[i] <= 0.02f) respawn(i)
            val x = cx + xs[i] / zs[i] * cx
            val y = cy + ys[i] / zs[i] * cx
            if (x < 0 || x > width || y < 0 || y > height) {
                respawn(i)
                continue
            }
            val near = 1f - zs[i]
            paint.color = colours[i]
            paint.alpha = (60 + 195 * near).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, (0.6f + near * near * 3.2f) * density, paint)
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val STARS = 420
        const val SPEED = 0.32f
    }
}
