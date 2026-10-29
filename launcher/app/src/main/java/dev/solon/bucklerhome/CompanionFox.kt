package dev.solon.bucklerhome

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.abs

// A little orange fox with a white-tipped tail, drawn in design units with its feet at y = 0.
// Orange reads on light and dark cards alike, so unlike the cat it needs no outline.

private val Orange = Color(0xFFE2772F)
private val Cream = Color(0xFFF7EDE0)
private val Dark = Color(0xFF3B2A22)
private val EyeDark = Color(0xFF2A1E18)
private val MouthOpen = Color(0xFF7A3346)
private val Petal = Color(0xFFF29BB8)
private val PetalHeart = Color(0xFFFFD45A)
private val StemGreen = Color(0xFF4E9A4B)
private val Wing = Color(0xFF7FB6F0)
private val Firefly = Color(0xFFFFE27A)

private enum class FoxEyes { OPEN, SLEEP, HAPPY }

/** The fox's pose for [phase]; it shelters under a big leaf when [sky] is wet. */
internal fun DrawScope.drawFox(t: Float, phase: DayPhase, sky: Sky, kit: SceneKit) {
    if (sky.wet) drawLeafShelter(t, kit)
    when (phase) {
        DayPhase.NIGHT -> foxCurled(t, kit)
        DayPhase.DAWN -> {
            // Waking slowly: a big yawn with the eyes screwed shut.
            val yawn = hump(cycle(t, 8f), 0.3f, 0.65f)
            foxSitting(t, kit, tilt = -6f * yawn, eyes = if (yawn > 0.3f) FoxEyes.HAPPY else FoxEyes.OPEN, yawn = yawn)
        }
        DayPhase.MORNING -> {
            // Sniffing a flower: the head dips toward it and the nose twitches.
            drawFlower(t, -27f)
            val sniff = hump(cycle(t, 5f), 0.15f, 0.85f)
            val twitch = if (sniff > 0.6f) 0.5f * wave(t, 0.25f) else 0f
            foxSitting(
                t, kit,
                headX = -5f * sniff + twitch,
                headY = -37f + 5f * sniff,
                tilt = -16f * sniff,
                lookX = -1f,
                lookY = sniff,
                eyes = if (sniff > 0.7f) FoxEyes.HAPPY else FoxEyes.OPEN,
            )
        }
        DayPhase.MIDDAY -> {
            // A playful pounce: a crouch, a hop with a forward tip, and a landing.
            val f = cycle(t, 4f)
            val hop = hump(f, 0.5f, 0.8f)
            val crouch = hump(f, 0.35f, 0.55f) + hump(f, 0.78f, 0.9f)
            translate(0f, -14f * hop) {
                rotate(10f * hop, Offset(0f, -10f)) {
                    foxSitting(t, kit, headY = -37f + 3f * crouch, lookY = 1f, tailLift = 20f * hop)
                }
            }
        }
        DayPhase.AFTERNOON -> {
            // Watching a butterfly flutter round its head.
            val bx = 24f + 12f * wave(t, 8f)
            val by = -54f + 6f * wave(t, 3f, 0.2f)
            foxSitting(t, kit, tilt = 6f * wave(t, 8f), lookX = (bx / 30f).coerceIn(-1f, 1f), lookY = -1f)
            drawButterfly(t, bx, by)
        }
        DayPhase.EVENING -> {
            foxSitting(t, kit, lookX = wave(t, 10f), lookY = -0.6f, tailSpeed = 6f)
            drawFireflies(t)
        }
    }
}

/** The sitting fox, front on, with the pose knobs each phase turns. */
@Suppress("LongParameterList")
private fun DrawScope.foxSitting(
    t: Float,
    kit: SceneKit,
    headX: Float = 0f,
    headY: Float = -37f,
    tilt: Float = 0f,
    lookX: Float = 0f,
    lookY: Float = 0f,
    eyes: FoxEyes = FoxEyes.OPEN,
    yawn: Float = 0f,
    tailSpeed: Float = 4f,
    tailLift: Float = 0f,
) {
    drawTail(-35f - tailLift + 6f * wave(t, tailSpeed), 8f, -6f, 22f, -6f)
    drawOval(Orange, Offset(-12f, -30f), Size(24f, 30f))
    drawOval(Orange, Offset(-15f, -10f), Size(12f, 10f))
    drawOval(Orange, Offset(3f, -10f), Size(12f, 10f))
    drawOval(Cream, Offset(-6.5f, -28f), Size(13f, 18f))
    drawRoundRect(Dark, Offset(-6.4f, -12f), Size(4.4f, 12f), CornerRadius(2.2f))
    drawRoundRect(Dark, Offset(2f, -12f), Size(4.4f, 12f), CornerRadius(2.2f))
    rotate(tilt, Offset(headX, headY)) { foxHead(t, kit, headX, headY, eyes, lookX, lookY, yawn) }
}

/** Curled up asleep with the tail wrapped round the front and its white tip under the chin. */
private fun DrawScope.foxCurled(t: Float, kit: SceneKit) {
    drawOval(Orange, Offset(-18f, -17f), Size(40f, 17f))
    rotate(-10f, Offset(-11f, -12f)) {
        scale(0.75f, Offset(-11f, -12f)) { foxHead(t, kit, -11f, -12f, FoxEyes.SLEEP, 0f, 0f, 0f) }
    }
    drawTail(180f + wave(t, 10f), 4f, -4f, 4f, -4f, thin = true)
    drawZs(t, -2f, -28f, kit.ink.muted)
}

/**
 * The bushy tail: an oval centred on ([cx], [cy]) turned [angle] degrees round ([px], [py]), its far
 * end clipped into a white tip. [thin] flattens it to lie along the ground.
 */
private fun DrawScope.drawTail(angle: Float, px: Float, py: Float, cx: Float, cy: Float, thin: Boolean = false) {
    val rx = if (thin) 21f else 15f
    val ry = if (thin) 5.5f else 7f
    rotate(angle, Offset(px, py)) {
        drawOval(Orange, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
        clipRect(cx + rx - 8f, cy - ry, cx + rx, cy + ry) {
            drawOval(Cream, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
        }
    }
}

/** Ears, face mask, eyes and nose, centred on ([hx], [hy]). */
@Suppress("LongParameterList")
private fun DrawScope.foxHead(
    t: Float,
    kit: SceneKit,
    hx: Float,
    hy: Float,
    eyes: FoxEyes,
    lookX: Float,
    lookY: Float,
    yawn: Float,
) {
    val p = kit.path
    for (side in 0..1) {
        val s = if (side == 0) -1f else 1f
        p.reset()
        p.moveTo(hx + s * 14f, hy - 4f)
        p.lineTo(hx + s * 10f, hy - 22f)
        p.lineTo(hx + s * 2f, hy - 9f)
        p.close()
        drawPath(p, Orange)
        p.reset()
        p.moveTo(hx + s * 12f, hy - 7f)
        p.lineTo(hx + s * 10.2f, hy - 17f)
        p.lineTo(hx + s * 5f, hy - 9.5f)
        p.close()
        drawPath(p, Cream)
        p.reset()
        p.moveTo(hx + s * 11.5f, hy - 15.2f)
        p.lineTo(hx + s * 10f, hy - 22f)
        p.lineTo(hx + s * 7f, hy - 17f)
        p.close()
        drawPath(p, Dark)
    }
    p.reset()
    p.moveTo(hx - 15f, hy - 3f)
    p.quadraticTo(hx, hy - 14f, hx + 15f, hy - 3f)
    p.quadraticTo(hx + 11f, hy + 6f, hx + 2f, hy + 11f)
    p.lineTo(hx - 2f, hy + 11f)
    p.quadraticTo(hx - 11f, hy + 6f, hx - 15f, hy - 3f)
    p.close()
    drawPath(p, Orange)
    // The white muzzle shares the face's lower outline and rises to a soft "m" under the eyes.
    p.reset()
    p.moveTo(hx - 15f, hy - 3f)
    p.quadraticTo(hx - 7f, hy + 3f, hx, hy + 2f)
    p.quadraticTo(hx + 7f, hy + 3f, hx + 15f, hy - 3f)
    p.quadraticTo(hx + 11f, hy + 6f, hx + 2f, hy + 11f)
    p.lineTo(hx - 2f, hy + 11f)
    p.quadraticTo(hx - 11f, hy + 6f, hx - 15f, hy - 3f)
    p.close()
    drawPath(p, Cream)
    for (side in 0..1) {
        val ex = hx + (if (side == 0) -5.5f else 5.5f)
        val ey = hy - 1.5f
        when {
            eyes == FoxEyes.SLEEP -> drawShutEye(ex, ey + 1f, 5f, EyeDark, happy = false, kit = kit)
            eyes == FoxEyes.HAPPY || blinking(t, 6f, 0.4f) -> drawShutEye(ex, ey, 5f, EyeDark, happy = true, kit = kit)
            else -> {
                drawOval(EyeDark, Offset(ex - 1.9f + lookX * 0.6f, ey - 2.5f + lookY * 0.6f), Size(3.8f, 5f))
                drawCircle(Color.White, 0.7f, Offset(ex + 0.6f + lookX * 0.6f, ey - 1.2f + lookY * 0.6f))
            }
        }
    }
    if (yawn > 0.05f) drawOval(MouthOpen, Offset(hx - 2f * yawn, hy + 8f), Size(4f * yawn, 5f * yawn))
    drawOval(Dark, Offset(hx - 2.2f, hy + 8.6f), Size(4.4f, 3f))
}

/** A small pink flower growing at [x], nodding in the breeze. */
private fun DrawScope.drawFlower(t: Float, x: Float) {
    val sway = 1.5f * wave(t, 5f)
    val cy = -21f
    drawLine(StemGreen, Offset(x, 0f), Offset(x + sway, cy), 1.6f, StrokeCap.Round)
    drawOval(StemGreen, Offset(x - 5.5f, -10f), Size(5f, 2.6f))
    for (i in 0 until 5) {
        rotate(i * 72f + 10f * wave(t, 5f), Offset(x + sway, cy)) {
            drawCircle(Petal, 3f, Offset(x + sway, cy - 3.4f))
        }
    }
    drawCircle(PetalHeart, 2.3f, Offset(x + sway, cy))
}

/** A blue butterfly whose wings fold and open as it flies. */
private fun DrawScope.drawButterfly(t: Float, x: Float, y: Float) {
    val flap = 0.25f + 0.75f * abs(wave(t, 0.6f))
    drawOval(Wing, Offset(x - 5.5f * flap, y - 5f), Size(5.5f * flap, 5.5f))
    drawOval(Wing, Offset(x, y - 5f), Size(5.5f * flap, 5.5f))
    drawOval(Wing, Offset(x - 4f * flap, y), Size(4f * flap, 4f))
    drawOval(Wing, Offset(x, y), Size(4f * flap, 4f))
    drawLine(Dark, Offset(x, y - 4f), Offset(x, y + 3.5f), 1.2f, StrokeCap.Round)
}

/** Fireflies wandering round the fox, each glowing and fading on its own beat. */
private fun DrawScope.drawFireflies(t: Float) {
    for (i in 0 until 6) {
        val x = -34f + 72f * hash01(i, 61) + 6f * wave(t, 5f + i % 3, hash01(i, 62))
        val y = -64f + 50f * hash01(i, 63) + 4f * wave(t, 4f, hash01(i, 64))
        val glow = 0.5f + 0.5f * wave(t, 2f + i % 2, hash01(i, 65))
        drawCircle(Firefly.copy(alpha = 0.25f * glow), 3.6f, Offset(x, y))
        drawCircle(Firefly.copy(alpha = 0.4f + 0.6f * glow), 1.1f, Offset(x, y))
    }
}
