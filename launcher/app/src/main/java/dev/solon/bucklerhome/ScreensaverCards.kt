package dev.solon.bucklerhome

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The screen the screensaver is laid out for, so a card is a true miniature of it. */
private val SCREEN_W = 960.dp
private val SCREEN_H = 540.dp

/** How long a screensaver runs before its still is taken: long enough to have drawn something. */
private const val STILL_AFTER_MS = 2_500L

/** Each screensaver's still, taken once per run of Buckler; one is made at a time. */
private object SaverStills {
    val taken = HashMap<ScreensaverMode, ImageBitmap>()
    val turn = Mutex()
}

/**
 * The real screensaver in a card ([ScreensaverView], as the TV shows it, scaled down): a still of
 * it normally, running while [live] (the card is highlighted). The still comes from running it
 * once briefly; until then the drawn [ScreensaverMiniature] stands in.
 */
@Composable
fun BoxScope.ScreensaverCard(mode: ScreensaverMode, live: Boolean) {
    var still by remember(mode) { mutableStateOf(SaverStills.taken[mode]) }
    var taking by remember(mode) { mutableStateOf(false) }
    var view by remember(mode) { mutableStateOf<ScreensaverView?>(null) }
    LaunchedEffect(mode) {
        if (still != null) return@LaunchedEffect
        SaverStills.turn.withLock {
            SaverStills.taken[mode]?.let {
                still = it
                return@withLock
            }
            taking = true
            delay(STILL_AFTER_MS)
            view?.let { v -> snapshot(v)?.let { SaverStills.taken[mode] = it; still = it } }
            taking = false
        }
    }
    val image = still
    when {
        live || taking -> RunningScreensaver(mode) { view = it }
        image != null -> Image(image, contentDescription = mode.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else -> ScreensaverMiniature(mode)
    }
}

/** A [ScreensaverView] laid out at full screen size and scaled to fill the card, cropped to it. */
@Composable
private fun RunningScreensaver(mode: ScreensaverMode, onView: (ScreensaverView) -> Unit) {
    val context = LocalContext.current
    val config = remember { ScreensaverPrefs.load(context) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val scale = maxOf(maxWidth / SCREEN_W, maxHeight / SCREEN_H)
        AndroidView(
            factory = { ctx -> ScreensaverView(ctx, config.copy(mode = mode)).also { it.start(); onView(it) } },
            onRelease = { it.stop() },
            modifier = Modifier
                .requiredSize(SCREEN_W, SCREEN_H)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** A quarter-size picture of [view] as it is now (small, so nine of them cost little memory). */
private fun snapshot(view: ScreensaverView): ImageBitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(view.width / 4, view.height / 4, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { scale(0.25f, 0.25f) }.let(view::draw)
        bitmap.asImageBitmap()
    }.getOrNull()
}
