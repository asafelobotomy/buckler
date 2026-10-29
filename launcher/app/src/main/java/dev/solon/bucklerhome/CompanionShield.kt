package dev.solon.bucklerhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Buckler's mascot: a rounded heater shield in the theme's brand colour, with a face, stubby arms
// and tiny feet. Design units, feet at y = 0.

private val Feet = Color(0xFF4A3B30)
private val Face = Color(0xFF2A2320)
private val Blush = Color(0x73FF8FA3)
private val MouthOpen = Color(0xFF7A3346)
private val Cover = Color(0xFF8C3B3B)
private val Page = Color(0xFFF7F1E3)
private val Ink = Color(0xFFB8AFA0)
private val Nightcap = Color(0xFF5B6BB5)
private val UmbrellaBlue = Color(0xFF4F8FD9)
private val UmbrellaRib = Color(0xFF3A6CA8)

private enum class ShieldEyes { OPEN, SLEEP, HAPPY }

/** The shield outline, flat-topped with rounded corners and a soft point, built once per kit. */
internal fun buildShieldPath(p: Path) {
    p.moveTo(-18f, -52f)
    p.quadraticTo(-18f, -58f, -12f, -58f)
    p.lineTo(12f, -58f)
    p.quadraticTo(18f, -58f, 18f, -52f)
    p.lineTo(18f, -36f)
    p.cubicTo(18f, -22f, 9f, -13f, 0f, -7f)
    p.cubicTo(-9f, -13f, -18f, -22f, -18f, -36f)
    p.close()
}

/** The mascot's activity for [phase]; it stands under an umbrella when [sky] is wet. */
internal fun DrawScope.drawShieldMascot(t: Float, phase: DayPhase, sky: Sky, kit: SceneKit) {
    if (sky.wet) drawUmbrella(t, UmbrellaBlue, UmbrellaRib)
    when (phase) {
        DayPhase.NIGHT -> {
            // A nap in a nightcap, leaning a little.
            rotate(-7f, Offset.Zero) {
                shieldBody(kit, ShieldEyes.SLEEP, smile = false, left = HAND_DOWN_L, right = HAND_DOWN_R)
                drawNightcap(t, kit)
            }
            drawZs(t, -34f, -62f, kit.ink.muted)
        }
        DayPhase.DAWN -> {
            // A big wake-up stretch, arms up and standing tall.
            val s = hump(cycle(t, 8f), 0.1f, 0.6f)
            scale(1f, 1f + 0.05f * s, Offset.Zero) {
                shieldBody(
                    kit,
                    if (s > 0.3f) ShieldEyes.HAPPY else blinkEyes(t),
                    left = Offset(-20f - 3f * s, -24f - 30f * s),
                    right = Offset(20f + 3f * s, -24f - 30f * s),
                )
            }
        }
        DayPhase.MORNING -> {
            shieldBody(kit, blinkEyes(t), lookY = 1f, left = Offset.Unspecified, right = Offset.Unspecified)
            drawBook(t)
            drawHand(kit, Offset(-17.5f, -34f), Offset(-12f, -24f))
            drawHand(kit, Offset(17.5f, -34f), Offset(12f, -24f))
        }
        DayPhase.MIDDAY -> {
            // A cheerful wave with a wide open smile.
            val a = (-60f + 25f * wave(t, 1f)) * RADIANS
            shieldBody(
                kit, blinkEyes(t), open = true,
                left = HAND_DOWN_L,
                right = Offset(17.5f + 13f * cos(a), -34f + 13f * sin(a)),
            )
        }
        DayPhase.AFTERNOON -> {
            // Humming a tune: a little bounce, a sway, and notes floating up.
            val bounce = abs(wave(t, 1.5f))
            translate(0f, -3f * bounce) {
                rotate(4f * wave(t, 3f), Offset.Zero) {
                    shieldBody(kit, ShieldEyes.HAPPY, left = HAND_DOWN_L, right = HAND_DOWN_R)
                }
            }
            drawNotes(t, kit.ink.section)
        }
        DayPhase.EVENING -> {
            drawLantern(t, 23f, -4f)
            shieldBody(kit, blinkEyes(t), lookX = 1f, left = HAND_DOWN_L, right = Offset(23f, -28f))
        }
    }
}

private fun blinkEyes(t: Float) = if (blinking(t, 4f, 0.2f)) ShieldEyes.HAPPY else ShieldEyes.OPEN

/**
 * Feet, shield, face and arms. [left] and [right] are where the hands are (Unspecified: no arm,
 * the caller draws it), [open] is a wide open smile and [smile] false leaves a calm sleeping mouth.
 */
@Suppress("LongParameterList")
private fun DrawScope.shieldBody(
    kit: SceneKit,
    eyes: ShieldEyes,
    lookX: Float = 0f,
    lookY: Float = 0f,
    smile: Boolean = true,
    open: Boolean = false,
    left: Offset,
    right: Offset,
) {
    for (side in 0..1) {
        val s = if (side == 0) -1f else 1f
        drawLine(Feet, Offset(s * 3f, -9f), Offset(s * 5.5f, -3f), 2.2f, StrokeCap.Round)
        drawOval(Feet, Offset(s * 6.5f - 5f, -5.4f), Size(10f, 5.4f))
    }
    drawPath(kit.shield, kit.ink.brand)
    scale(0.8f, Offset(0f, -34f)) { drawPath(kit.shield, kit.brandLight, style = kit.line) }
    drawPath(kit.shield, kit.brandDark, style = kit.rim)
    for (side in 0..1) {
        val s = if (side == 0) -1f else 1f
        val ex = s * 6.5f
        val ey = -41f
        when (eyes) {
            ShieldEyes.SLEEP -> drawShutEye(ex, ey + 1f, 5f, Face, happy = false, kit = kit)
            ShieldEyes.HAPPY -> drawShutEye(ex, ey, 5f, Face, happy = true, kit = kit)
            ShieldEyes.OPEN -> {
                drawCircle(Face, 2.5f, Offset(ex + lookX * 0.7f, ey + lookY * 0.7f))
                drawCircle(Color.White, 0.8f, Offset(ex + 0.8f + lookX * 0.7f, ey - 0.8f + lookY * 0.7f))
            }
        }
        drawOval(Blush, Offset(s * 11.5f - 3f, -36.5f), Size(6f, 3.4f))
    }
    when {
        open -> drawArc(MouthOpen, 0f, 180f, true, Offset(-3.5f, -39f), Size(7f, 7f))
        smile -> drawArc(Face, 20f, 140f, false, Offset(-3.5f, -39f), Size(7f, 5f), style = kit.line)
        else -> drawLine(Face, Offset(-1.5f, -35.5f), Offset(1.5f, -35.5f), 1.4f, StrokeCap.Round)
    }
    if (left.isSpecified) drawHand(kit, Offset(-17.5f, -34f), left)
    if (right.isSpecified) drawHand(kit, Offset(17.5f, -34f), right)
}

/** A stubby arm from [shoulder] to a round hand at [hand]. */
private fun DrawScope.drawHand(kit: SceneKit, shoulder: Offset, hand: Offset) {
    drawLine(kit.brandDark, shoulder, hand, 2.4f, StrokeCap.Round)
    drawCircle(kit.brandDark, 2.6f, hand)
}

/** A floppy nightcap on the shield's top right corner, its pompom swaying as it breathes. */
private fun DrawScope.drawNightcap(t: Float, kit: SceneKit) {
    val p = kit.path
    val tipX = 25f + 1.5f * wave(t, 4f)
    p.reset()
    p.moveTo(2f, -57f)
    p.quadraticTo(10f, -74f, tipX, -66f)
    p.quadraticTo(18f, -64f, 19f, -53f)
    p.close()
    drawPath(p, Nightcap)
    drawCircle(Color.White, 2.6f, Offset(tipX, -66f))
}

/** A tiny open book held up to read, turning a page every few seconds. */
private fun DrawScope.drawBook(t: Float) {
    drawRect(Cover, Offset(-12.5f, -31f), Size(25f, 13.5f))
    drawRect(Page, Offset(-11.5f, -30f), Size(11f, 11.5f))
    drawRect(Page, Offset(0.5f, -30f), Size(11f, 11.5f))
    for (i in 0 until 3) {
        val y = -27f + i * 3f
        drawLine(Ink, Offset(-9.5f, y), Offset(-2.5f, y), 0.9f)
        drawLine(Ink, Offset(2.5f, y), Offset(9.5f, y), 0.9f)
    }
    val f = cycle(t, 6f)
    if (f > 0.85f) {
        // The turning page, seen edge-on as it swings from right to left over the spine.
        val w = 11f * cos((f - 0.85f) / 0.15f * PI.toFloat())
        drawRect(Page, Offset(0.5f + minOf(0f, w), -30.5f), Size(abs(w), 12f))
    }
    drawLine(Cover, Offset(0f, -30f), Offset(0f, -18.5f), 1f)
}

/** Two music notes drifting up and fading while it hums. */
private fun DrawScope.drawNotes(t: Float, color: Color) {
    for (i in 0 until 2) {
        val p = cycle(t, 3f, i * 0.5f)
        val x = 20f + 8f * p + 2f * wave(t, 1.5f, i * 0.5f)
        val y = -46f - 22f * p
        val c = color.copy(alpha = sin(p * PI.toFloat()))
        drawCircle(c, 2.1f, Offset(x, y))
        drawLine(c, Offset(x + 1.9f, y), Offset(x + 1.9f, y - 8f), 1.1f)
        drawLine(c, Offset(x + 1.9f, y - 8f), Offset(x + 5f, y - 6f), 1.1f, StrokeCap.Round)
    }
}

private val HAND_DOWN_L = Offset(-20f, -24f)
private val HAND_DOWN_R = Offset(20f, -24f)
private const val RADIANS = (PI / 180.0).toFloat()
