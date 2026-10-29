package dev.solon.bucklerhome

import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pictures for the setup wizard's choices, drawn on the current theme like [Miniatures.kt]'s. */

private fun Modifier.block(color: Color, radius: Dp = 3.dp) = clip(RoundedCornerShape(radius)).background(color)

/**
 * A small Home: header (with the Side Stage's Now Playing when on), the Main Stage, then each row
 * that is on, in Home's order. [focus] outlines the row being chosen.
 */
@Composable
fun HomeShelvesMiniature(shelves: Map<ShelfType, Boolean>, nowPlaying: Boolean, focus: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.block(p.bgTop, 10.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 46.dp, height = 10.dp).block(p.text.copy(alpha = 0.85f)))
            Spacer(Modifier.weight(1f))
            if (nowPlaying) {
                Row(
                    Modifier.highlight(focus == "now_playing", p).block(p.footer, 4.dp).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).block(p.brand.copy(alpha = 0.7f), 2.dp))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(width = 40.dp, height = 5.dp).block(p.muted))
                }
                Spacer(Modifier.weight(1f))
            }
            repeat(3) { Box(Modifier.padding(start = 4.dp).size(9.dp).clip(CircleShape).background(p.muted)) }
        }
        Box(Modifier.fillMaxWidth().height(28.dp).block(p.brand.copy(alpha = 0.35f), 5.dp))
        val order = listOf(ShelfType.WATCH_NEXT, ShelfType.APPS, ShelfType.SUGGESTED, ShelfType.RECENTS, ShelfType.LIBRARY)
        order.filter { it == ShelfType.APPS || shelves[it] == true }.forEach { type ->
            Column(Modifier.highlight(focus == type.name, p).padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(SetupApply.shelfName(type), color = p.section, fontSize = 9.sp, lineHeight = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val (w, h, n) = when (type) {
                        ShelfType.WATCH_NEXT -> Triple(52.dp, 22.dp, 6)
                        ShelfType.LIBRARY -> Triple(22.dp, 13.dp, 14)
                        else -> Triple(30.dp, 18.dp, 10)
                    }
                    repeat(n) { Box(Modifier.size(width = w, height = h).block(p.tile, 3.dp)) }
                }
            }
        }
    }
}

private fun Modifier.highlight(on: Boolean, p: Palette): Modifier =
    if (on) border(2.dp, p.brand, RoundedCornerShape(5.dp)) else this

/** The screensaver itself, drawn small: a clock, the bouncing logo, a photo, the maze and so on. */
@Composable
fun BoxScope.ScreensaverMiniature(mode: ScreensaverMode) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(Color(0xFF07080C)), contentAlignment = Alignment.Center) {
        when (mode) {
            ScreensaverMode.CLOCK -> Text("21:30", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.Light)
            ScreensaverMode.NOW_PLAYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).block(p.brand, 4.dp))
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 50.dp, height = 6.dp).block(Color.White.copy(alpha = 0.8f)))
                    Box(Modifier.size(width = 34.dp, height = 5.dp).block(Color.White.copy(alpha = 0.4f)))
                }
            }
            else -> Canvas(Modifier.fillMaxSize()) { drawSaver(mode, p) }
        }
    }
}

private fun DrawScope.drawSaver(mode: ScreensaverMode, p: Palette) {
    val w = size.width
    val h = size.height
    val thin = Stroke(width = 2.dp.toPx())
    when (mode) {
        ScreensaverMode.DVD -> {
            drawRoundRect(Color(0xFF8A5CF6), Offset(w * 0.18f, h * 0.22f), Size(w * 0.3f, h * 0.24f), androidx.compose.ui.geometry.CornerRadius(6f))
            drawLine(Color.White.copy(alpha = 0.25f), Offset(w * 0.33f, h * 0.46f), Offset(w * 0.7f, h * 0.9f), 2f)
        }
        ScreensaverMode.PHOTOS, ScreensaverMode.UPLOADED -> {
            drawRect(Color(0xFF3B6EA8), size = Size(w, h * 0.62f))
            drawCircle(Color(0xFFFFD166), radius = h * 0.1f, center = Offset(w * 0.75f, h * 0.25f))
            val hills = Path().apply {
                moveTo(0f, h * 0.7f); lineTo(w * 0.3f, h * 0.38f); lineTo(w * 0.55f, h * 0.62f)
                lineTo(w * 0.75f, h * 0.45f); lineTo(w, h * 0.68f); lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(hills, Color(0xFF2F7D4F))
            if (mode == ScreensaverMode.UPLOADED) {
                drawRoundRect(Color.White, Offset(w * 0.06f, h * 0.5f), Size(w * 0.14f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(4f), style = thin)
            }
        }
        ScreensaverMode.MAZE -> {
            val step = h / 5
            for (i in 0..8) drawLine(p.brand.copy(alpha = 0.7f), Offset(i * step, 0f), Offset(i * step, if (i % 2 == 0) h * 0.7f else h), 3f)
            for (j in 1..4) drawLine(Color.White.copy(alpha = 0.35f), Offset(j * step * 0.6f, j * step), Offset(w, j * step), 3f)
        }
        ScreensaverMode.PIPES -> {
            listOf(Color(0xFFE5534B) to 0.3f, Color(0xFF4F8EF7) to 0.55f, Color(0xFF46C27A) to 0.8f).forEach { (c, y) ->
                val path = Path().apply { moveTo(0f, h * y); lineTo(w * 0.4f, h * y); lineTo(w * 0.4f, h * (y - 0.25f)); lineTo(w, h * (y - 0.25f)) }
                drawPath(path, c, style = Stroke(width = 6.dp.toPx()))
            }
        }
        ScreensaverMode.MYSTIFY -> {
            listOf(Color(0xFF7FDBFF), Color(0xFFFF7EB6)).forEachIndexed { k, c ->
                val path = Path().apply {
                    moveTo(w * (0.1f + k * 0.1f), h * 0.2f); lineTo(w * 0.8f, h * (0.15f + k * 0.2f))
                    lineTo(w * (0.6f - k * 0.1f), h * 0.85f); lineTo(w * 0.2f, h * (0.7f - k * 0.1f)); close()
                }
                drawPath(path, c, style = thin)
            }
        }
        ScreensaverMode.STARFIELD -> {
            for (i in 0 until 40) {
                val x = (i * 73 % 100) / 100f * w
                val y = (i * 37 % 100) / 100f * h
                drawCircle(Color.White.copy(alpha = 0.3f + (i % 7) / 10f), radius = 1f + (i % 3), center = Offset(x, y))
            }
        }
        else -> Unit
    }
}

/** Google's Backdrop as it looks: a landscape photo with a small clock in its corner. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.BackdropMiniature() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3D2C6E), Color(0xFFE07A5F), Color(0xFFF2CC8F))),
        )
        val sea = Path().apply { moveTo(0f, size.height * 0.72f); lineTo(size.width, size.height * 0.66f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(sea, Color(0xFF26324F))
    }
    Text(
        "21:30",
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
    )
}

/** How a choice of app icons looks: the apps' own colours, or a pack's outlines or white lines. */
enum class IconStyle { OWN, OUTLINE, LINES, OTHER }

@Composable
fun BoxScope.IconStyleMiniature(style: IconStyle) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxSize().background(p.bgTop).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val own = listOf(Color(0xFFE53935), Color(0xFF1DB954), Color(0xFF7B5CF5), Color(0xFFFF9800))
        own.forEachIndexed { i, colour ->
            Canvas(Modifier.size(30.dp)) {
                val ink = when (style) {
                    IconStyle.OWN -> Color.White
                    IconStyle.LINES -> Color.White
                    else -> p.text
                }
                if (style == IconStyle.OWN) drawRoundRect(colour, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
                if (style == IconStyle.OUTLINE || style == IconStyle.OTHER) {
                    drawCircle(p.tile, radius = size.minDimension / 2)
                }
                glyph(i, ink, if (style == IconStyle.LINES) 1.2.dp.toPx() else 2.2.dp.toPx(), fill = style == IconStyle.OWN)
            }
        }
    }
}

/** Four simple marks (play, note, screen, star), so the icon styles have something to draw. */
private fun DrawScope.glyph(i: Int, ink: Color, width: Float, fill: Boolean) {
    val w = size.width
    val s = Stroke(width = width)
    when (i) {
        0 -> {
            val play = Path().apply { moveTo(w * 0.38f, w * 0.28f); lineTo(w * 0.72f, w * 0.5f); lineTo(w * 0.38f, w * 0.72f); close() }
            if (fill) drawPath(play, ink) else drawPath(play, ink, style = s)
        }
        1 -> {
            drawCircle(ink, radius = w * 0.12f, center = Offset(w * 0.4f, w * 0.66f), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else s)
            drawLine(ink, Offset(w * 0.52f, w * 0.66f), Offset(w * 0.52f, w * 0.26f), width)
            drawLine(ink, Offset(w * 0.52f, w * 0.26f), Offset(w * 0.68f, w * 0.34f), width)
        }
        2 -> {
            drawRect(ink, Offset(w * 0.24f, w * 0.3f), Size(w * 0.52f, w * 0.34f), style = s)
            drawLine(ink, Offset(w * 0.4f, w * 0.74f), Offset(w * 0.6f, w * 0.74f), width)
        }
        else -> {
            val star = Path()
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) w * 0.28f else w * 0.12f
                val a = Math.PI / 5 * k - Math.PI / 2
                val x = (w / 2 + r * kotlin.math.cos(a)).toFloat()
                val y = (w / 2 + r * kotlin.math.sin(a)).toFloat()
                if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            if (fill) drawPath(star, ink) else drawPath(star, ink, style = s)
        }
    }
}

/** An app's own icon (installed or turned off), else its initial on a brand-coloured disc. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppIcon(packageName: String, label: String, size: Dp, faded: Boolean = false) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS).loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    val image = icon
    val modifier = Modifier.size(size).alpha(if (faded) 0.45f else 1f)
    if (image != null) {
        Image(image, contentDescription = label, modifier = modifier.clip(RoundedCornerShape(size / 5)))
    } else {
        Box(modifier.clip(CircleShape).background(ColorBrand), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), color = ColorOnBrand, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** How quickly things move: a dot with a trail as long as the animation takes. */
@Composable
fun BoxScope.SpeedMiniature(scale: Float) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxSize().background(p.bgTop).padding(14.dp)) {
        val y = size.height / 2
        val end = size.width * 0.85f
        val trail = when {
            scale <= 0f -> 0f
            scale < 1f -> size.width * 0.3f
            else -> size.width * 0.6f
        }
        for (k in 0 until 8) {
            val x = end - trail * k / 8
            if (trail > 0f) drawCircle(p.brand.copy(alpha = 0.6f - k * 0.07f), radius = 7f, center = Offset(x, y))
        }
        drawCircle(p.brand, radius = 11f, center = Offset(end, y))
        if (scale <= 0f) drawCircle(p.muted, radius = 11f, center = Offset(size.width * 0.15f, y), style = Stroke(2f))
    }
}

/** A lookup leaving the TV: plain, through the network's encrypted DNS, or a named host. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.DnsMiniature(dns: DnsChoice, label: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxSize().background(p.bgTop).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 26.dp, height = 18.dp).block(p.tile, 3.dp))
        Text(if (dns.mode == "off") "→" else "🔒→", color = p.muted, fontSize = 14.sp)
        Text(
            text = when (dns.mode) {
                "off" -> "Plain DNS"
                "hostname" -> label.substringBefore(" (")
                else -> "Network's DNS"
            },
            color = p.text,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}
