package dev.solon.bucklerhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate

// A small round black cat, drawn in design units with its feet at y = 0. Every furry shape is
// drawn twice: first all of them as a lighter stroke, then all of them filled, so the outline
// traces the whole silhouette (and the cat reads on a dark card) without lines across the body.

private val Fur = Color(0xFF26262C)
private val Rim = Color(0xFF6E6E7C)
private val EarInner = Color(0xFF4A3842)
private val Eye = Color(0xFFC6DE3F)
private val Pupil = Color(0xFF101012)
private val Nose = Color(0xFFE08FA0)
private val MouthOpen = Color(0xFF7A3346)
private val Whisker = Color(0xB36E6E7C)
private val Yarn = Color(0xFFE0657A)
private val YarnDark = Color(0xFFB84A60)
private val UmbrellaRed = Color(0xFFD9534F)
private val UmbrellaRib = Color(0xFFA33B38)

private enum class CatEyes { OPEN, SLEEP, HAPPY }

/** The cat's pose for [phase]; it shelters under an umbrella when [sky] is wet. */
internal fun DrawScope.drawCat(t: Float, phase: DayPhase, sky: Sky, kit: SceneKit) {
    if (sky.wet) drawUmbrella(t, UmbrellaRed, UmbrellaRib)
    when (phase) {
        DayPhase.NIGHT -> catCurled(t, kit)
        DayPhase.DAWN -> {
            // A long morning stretch: paws push forward, the body rises, then a yawn.
            val s = hump(cycle(t, 8f), 0.1f, 0.6f)
            catSitting(
                t, kit,
                headY = -38f - 3f * s,
                bodyGrow = 3f * s,
                pawSpread = 4f * s,
                eyes = if (s > 0.35f) CatEyes.HAPPY else CatEyes.OPEN,
                yawn = hump(cycle(t, 8f), 0.25f, 0.55f),
            )
        }
        DayPhase.MORNING -> {
            // Grooming: a raised paw bobs at the mouth, then a pause to look round.
            val grooming = cycle(t, 6f) < 0.6f
            catSitting(
                t, kit,
                tilt = if (grooming) -10f else 0f,
                eyes = if (grooming) CatEyes.HAPPY else CatEyes.OPEN,
                raisedPaw = if (grooming) Offset(-6f, -30f + 1.6f * wave(t, 0.75f)) else Offset.Unspecified,
            )
        }
        DayPhase.MIDDAY -> catSunbathing(t, kit)
        DayPhase.AFTERNOON -> {
            // Batting a ball of yarn that rolls away and back.
            val swipe = hump(cycle(t, 3f), 0f, 0.3f)
            val ballX = 22f + 9f * hump(cycle(t, 3f), 0.15f, 1f)
            drawYarn(ballX, kit)
            catSitting(
                t, kit,
                tilt = 8f,
                lookX = 1f,
                tailSpeed = 1.5f,
                batPaw = Offset(5f + 9f * swipe, -2f - 5f * swipe),
            )
        }
        DayPhase.EVENING -> {
            drawLantern(t, -32f, 0f)
            catSitting(t, kit, tilt = -6f, lookX = -1f, lookY = -0.6f, wide = true)
        }
    }
}

/** The sitting cat, front on, with the pose knobs each phase turns. */
@Suppress("LongParameterList")
private fun DrawScope.catSitting(
    t: Float,
    kit: SceneKit,
    headY: Float = -38f,
    tilt: Float = 0f,
    bodyGrow: Float = 0f,
    pawSpread: Float = 0f,
    eyes: CatEyes = CatEyes.OPEN,
    lookX: Float = 0f,
    lookY: Float = 0f,
    wide: Boolean = false,
    yawn: Float = 0f,
    tailSpeed: Float = 4f,
    raisedPaw: Offset = Offset.Unspecified,
    batPaw: Offset = Offset.Unspecified,
) {
    val swish = wave(t, tailSpeed)
    for (pass in 0..1) {
        val rim = pass == 0
        val p = kit.path
        p.reset()
        p.moveTo(9f, -4f)
        p.quadraticTo(30f, -3f, 27f + 3f * swish, -24f + 2f * wave(t, tailSpeed, 0.25f))
        drawPath(p, if (rim) Rim else Fur, style = if (rim) kit.tailRim else kit.tail)
        fur(rim, kit, 0f, -17f - bodyGrow / 2f, 14f, 17f + bodyGrow / 2f)
        fur(rim, kit, -9f, -6f, 8f, 6.5f)
        fur(rim, kit, 9f, -6f, 8f, 6.5f)
        if (raisedPaw.isSpecified) fur(rim, kit, raisedPaw.x, raisedPaw.y, 3.6f, 4.4f)
        else fur(rim, kit, -4.5f - pawSpread, -2f, 4f, 2.6f)
        if (batPaw.isSpecified) fur(rim, kit, batPaw.x, batPaw.y, 4f, 2.8f)
        else fur(rim, kit, 4.5f + pawSpread, -2f, 4f, 2.6f)
        rotate(tilt, Offset(0f, headY)) { catHead(rim, kit, 0f, headY, 12.5f, 1f) }
    }
    rotate(tilt, Offset(0f, headY)) {
        catFace(t, kit, 0f, headY, 12.5f, 1f, eyes, lookX, lookY, wide, yawn)
    }
}

/** Lying on its side in the sun, facing left, eyes squeezed shut with pleasure. */
private fun DrawScope.catSunbathing(t: Float, kit: SceneKit) {
    val swish = wave(t, 6f)
    for (pass in 0..1) {
        val rim = pass == 0
        val p = kit.path
        p.reset()
        p.moveTo(20f, -6f)
        p.quadraticTo(34f, -2f, 38f + 2f * swish, -9f + 3f * swish)
        drawPath(p, if (rim) Rim else Fur, style = if (rim) kit.tailRim else kit.tail)
        fur(rim, kit, 2f, -8.5f, 22f, 8.5f)
        fur(rim, kit, -25f, -3f, 5f, 2.4f)
        fur(rim, kit, 19f, -2.6f, 5f, 2.5f)
        rotate(-20f, Offset(-19f, -11f)) { catHead(rim, kit, -19f, -11f, 11f, 1f) }
    }
    rotate(-20f, Offset(-19f, -11f)) { catFace(t, kit, -19f, -11f, 11f, 1f, CatEyes.HAPPY, 0f, 0f, false, 0f) }
}

/** Curled into a loaf for the night, tail round the front, "z"s drifting up. */
private fun DrawScope.catCurled(t: Float, kit: SceneKit) {
    for (pass in 0..1) {
        val rim = pass == 0
        fur(rim, kit, 3f, -9.5f, 20f, 9.5f)
        rotate(-12f, Offset(-12f, -11f)) { catHead(rim, kit, -12f, -11f, 10.5f, 0.75f) }
        val p = kit.path
        p.reset()
        p.moveTo(20f, -4f)
        p.quadraticTo(8f + wave(t, 10f), 3f, -15f, -1.5f)
        drawPath(p, if (rim) Rim else Fur, style = if (rim) kit.tailRim else kit.tail)
    }
    rotate(-12f, Offset(-12f, -11f)) { catFace(t, kit, -12f, -11f, 10.5f, 0.75f, CatEyes.SLEEP, 0f, 0f, false, 0f) }
    drawZs(t, -2f, -26f, kit.ink.muted)
}

/** One furry oval centred on ([cx], [cy]): the rim stroke on the first pass, the fill on the second. */
private fun DrawScope.fur(rim: Boolean, kit: SceneKit, cx: Float, cy: Float, rx: Float, ry: Float) {
    drawOval(
        color = if (rim) Rim else Fur,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        style = if (rim) kit.rim else Fill,
    )
}

/** The round head and pointed ears; [ear] shrinks the ears when they lie flat in sleep. */
private fun DrawScope.catHead(rim: Boolean, kit: SceneKit, hx: Float, hy: Float, r: Float, ear: Float) {
    val color = if (rim) Rim else Fur
    val style = if (rim) kit.rim else Fill
    drawCircle(color, r, Offset(hx, hy), style = style)
    val p = kit.path
    for (side in 0..1) {
        earPath(p, hx, hy, r, ear, if (side == 0) -1f else 1f, 1f)
        drawPath(p, color, style = style)
    }
}

/** One ear's triangle on side [s] (-1 left, 1 right), shrunk by [shrink] toward its middle. */
private fun earPath(p: Path, hx: Float, hy: Float, r: Float, ear: Float, s: Float, shrink: Float) {
    val ax = 0.96f
    val ay = -0.24f
    val bx = 0.8f
    val by = -0.24f - 0.9f * ear
    val cx = 0.16f
    val cy = -0.88f
    val gx = (ax + bx + cx) / 3f
    val gy = (ay + by + cy) / 3f
    p.reset()
    p.moveTo(hx + s * r * (gx + (ax - gx) * shrink), hy + r * (gy + (ay - gy) * shrink))
    p.lineTo(hx + s * r * (gx + (bx - gx) * shrink), hy + r * (gy + (by - gy) * shrink))
    p.lineTo(hx + s * r * (gx + (cx - gx) * shrink), hy + r * (gy + (cy - gy) * shrink))
    p.close()
}

/** Inner ears, eyes, nose, mouth and whiskers over a head drawn by [catHead]. */
@Suppress("LongParameterList")
private fun DrawScope.catFace(
    t: Float,
    kit: SceneKit,
    hx: Float,
    hy: Float,
    r: Float,
    ear: Float,
    eyes: CatEyes,
    lookX: Float,
    lookY: Float,
    wide: Boolean,
    yawn: Float,
) {
    val p = kit.path
    for (side in 0..1) {
        val s = if (side == 0) -1f else 1f
        earPath(p, hx, hy, r, ear, s, INNER_EAR)
        drawPath(p, EarInner)
        val ex = hx + s * 5f
        val ey = hy - 1f
        when {
            eyes == CatEyes.SLEEP -> drawShutEye(ex, ey + 1f, 5.5f, Rim, happy = false, kit = kit)
            eyes == CatEyes.HAPPY || blinking(t, 5f) -> drawShutEye(ex, ey, 5.5f, Rim, happy = true, kit = kit)
            else -> {
                drawOval(Eye, Offset(ex - 3f, ey - 3.4f), Size(6f, 6.8f))
                val pw = if (wide) 3.4f else 2.2f
                drawOval(Pupil, Offset(ex + lookX * 1f - pw / 2f, ey + lookY * 1.2f - 2.7f), Size(pw, 5.4f))
                drawCircle(Color.White, 0.8f, Offset(ex + 1f, ey - 1.6f))
            }
        }
        drawLine(Whisker, Offset(hx + s * 6.5f, hy + 3f), Offset(hx + s * 14f, hy + 1.8f), 0.8f)
        drawLine(Whisker, Offset(hx + s * 6.5f, hy + 4.6f), Offset(hx + s * 14f, hy + 5.6f), 0.8f)
    }
    if (yawn > 0.05f) {
        drawOval(MouthOpen, Offset(hx - 2.4f * yawn, hy + 4.4f), Size(4.8f * yawn, 5.6f * yawn))
    } else {
        drawArc(Rim, 0f, 180f, false, Offset(hx - 2.6f, hy + 3f), Size(2.6f, 2.4f), style = kit.line)
        drawArc(Rim, 0f, 180f, false, Offset(hx, hy + 3f), Size(2.6f, 2.4f), style = kit.line)
    }
    drawOval(Nose, Offset(hx - 1.5f, hy + 2.2f), Size(3f, 2f))
}

/** A ball of yarn that rolls as it moves, trailing a loose end. */
private fun DrawScope.drawYarn(x: Float, kit: SceneKit) {
    val r = 6f
    drawLine(YarnDark, Offset(x + r * 0.6f, -1f), Offset(x + r + 9f, -0.6f), 1.2f)
    drawCircle(Yarn, r, Offset(x, -r))
    // Rolling: the wraps turn by the distance travelled over the radius.
    rotate((x - 22f) / r * DEGREES_PER_RADIAN, Offset(x, -r)) {
        drawArc(YarnDark, 200f, 140f, false, Offset(x - r * 0.8f, -r * 1.7f), Size(r * 1.6f, r * 1.2f), style = kit.line)
        drawArc(YarnDark, 20f, 140f, false, Offset(x - r * 0.8f, -r * 1.4f), Size(r * 1.6f, r * 1.2f), style = kit.line)
        drawArc(YarnDark, 110f, 140f, false, Offset(x - r * 0.4f, -r * 1.6f), Size(r * 1.2f, r * 1.2f), style = kit.line)
    }
    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.25f, Offset(x - r * 0.35f, -r * 1.4f))
}

private const val DEGREES_PER_RADIAN = 57.29578f
private const val INNER_EAR = 0.55f
