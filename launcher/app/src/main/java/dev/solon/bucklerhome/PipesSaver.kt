package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.random.Random

private const val GX = 13
private const val GY = 8
private const val GZ = 8
private const val CAMERA = 5.5f
private const val RADIUS = 0.17f
private const val STEP_SECONDS = 0.055f
private const val MAX_PIPES = 9
private const val HOLD_SECONDS = 3f

private val PIPE_COLORS = intArrayOf(
    0xFFD23C3C.toInt(), 0xFF3CB44B.toInt(), 0xFF3C78D8.toInt(), 0xFFE6C229.toInt(),
    0xFF2EC4C4.toInt(), 0xFFC23CB4.toInt(), 0xFFE8E8E8.toInt(), 0xFFE5832B.toInt(),
)
private val STEPS = arrayOf(
    intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0),
    intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
)

/**
 * After the Windows "3D Pipes" screensaver: pipes grow cell by cell through a 3D grid, turning at
 * random with a ball joint, until the grid fills up and it starts over. Everything is drawn with
 * gradients (no bundled art) and sorted far-to-near each frame so closer pipes overlap farther ones.
 */
/** At most [maxPipes] pipes per scene before it starts over; [speed] 1 = normal. */
class PipesScene(context: Context, private val maxPipes: Int = MAX_PIPES, speed: Float = 1f) : View(context) {
    private val stepSeconds = STEP_SECONDS / speed
    private class Piece(val depth: Float, val draw: (Canvas) -> Unit)

    private val random = Random(System.nanoTime())
    private val occupied = BooleanArray(GX * GY * GZ)
    private val pieces = ArrayList<Piece>()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG)
    private var head: IntArray? = null
    private var dir = 0
    private var color = PIPE_COLORS[0]
    private var pipes = 0
    private var length = 0
    private var acc = 0f
    private var hold = 0f
    private var lastNanos = 0L
    private var focal = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Fit the middle of the grid's depth to the screen width.
        focal = (w / 2f) / ((GX - 1) / 2f) * (CAMERA + GZ / 2f) * 0.92f
        reset()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        if (hold > 0f) {
            hold -= dt
            if (hold <= 0f) reset()
        } else {
            acc += dt
            while (acc >= stepSeconds && hold <= 0f) {
                acc -= stepSeconds
                step()
            }
        }
        canvas.drawColor(Color.BLACK)
        pieces.forEach { it.draw(canvas) }
        postInvalidateOnAnimation()
    }

    private fun reset() {
        occupied.fill(false)
        pieces.clear()
        head = null
        pipes = 0
    }

    private fun index(c: IntArray) = (c[2] * GY + c[1]) * GX + c[0]

    private fun free(c: IntArray) =
        c[0] in 0 until GX && c[1] in 0 until GY && c[2] in 0 until GZ && !occupied[index(c)]

    private fun moved(c: IntArray, d: Int) = intArrayOf(c[0] + STEPS[d][0], c[1] + STEPS[d][1], c[2] + STEPS[d][2])

    private fun step() {
        val current = head ?: return startPipe()
        val straight = moved(current, dir)
        val keep = random.nextFloat() < 0.78f && free(straight)
        val nextDir = if (keep) dir else STEPS.indices.filter { free(moved(current, it)) }.randomOrNull(random)
        if (nextDir == null || length-- <= 0) {
            addBall(current, 1.2f)
            head = null
            return
        }
        if (nextDir != dir) addBall(current, 1.25f)
        dir = nextDir
        val next = moved(current, dir)
        occupied[index(next)] = true
        addSegment(current, next)
        head = next
    }

    private fun startPipe() {
        if (pipes >= maxPipes) {
            hold = HOLD_SECONDS
            return
        }
        val start = (0 until 60).asSequence()
            .map { intArrayOf(random.nextInt(GX), random.nextInt(GY), random.nextInt(GZ)) }
            .firstOrNull { free(it) }
        if (start == null) {
            hold = HOLD_SECONDS
            return
        }
        occupied[index(start)] = true
        color = PIPE_COLORS[random.nextInt(PIPE_COLORS.size)]
        dir = random.nextInt(STEPS.size)
        pipes++
        length = random.nextInt(30, 80)
        head = start
        addBall(start, 1.2f)
    }

    // --- projection and drawing -------------------------------------------------------------

    private fun depth(c: IntArray) = c[2] + CAMERA
    private fun sx(c: IntArray) = width / 2f + (c[0] - (GX - 1) / 2f) * focal / depth(c)
    private fun sy(c: IntArray) = height / 2f + (c[1] - (GY - 1) / 2f) * focal / depth(c)
    private fun radius(c: IntArray) = RADIUS * focal / depth(c)

    private fun addSegment(a: IntArray, b: IntArray) {
        val x0 = sx(a)
        val y0 = sy(a)
        val x1 = sx(b)
        val y1 = sy(b)
        val r = (radius(a) + radius(b)) / 2f
        val base = color
        val dark = blend(base, Color.BLACK, 0.55f)
        val light = blend(base, Color.WHITE, 0.55f)
        insert(Piece((a[2] + b[2]) / 2f) { canvas ->
            stroke.color = dark
            stroke.strokeWidth = r * 2f
            canvas.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = base
            stroke.strokeWidth = r * 1.45f
            canvas.drawLine(x0 - r * 0.12f, y0 - r * 0.12f, x1 - r * 0.12f, y1 - r * 0.12f, stroke)
            stroke.color = light
            stroke.strokeWidth = r * 0.45f
            canvas.drawLine(x0 - r * 0.38f, y0 - r * 0.38f, x1 - r * 0.38f, y1 - r * 0.38f, stroke)
        })
    }

    private fun addBall(c: IntArray, scale: Float) {
        val x = sx(c)
        val y = sy(c)
        val r = radius(c) * scale
        val shader = RadialGradient(
            x - r * 0.35f, y - r * 0.35f, r * 1.35f,
            intArrayOf(blend(color, Color.WHITE, 0.6f), color, blend(color, Color.BLACK, 0.6f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Slightly nearer than the segments meeting here so the joint covers their ends.
        insert(Piece(c[2] - 0.01f) { canvas ->
            ball.shader = shader
            canvas.drawCircle(x, y, r, ball)
        })
    }

    /** Keeps [pieces] ordered far-to-near. */
    private fun insert(piece: Piece) {
        var i = pieces.size
        while (i > 0 && pieces[i - 1].depth < piece.depth) i--
        pieces.add(i, piece)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }
}
