package dev.solon.bucklerhome

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** One line that scrolls sideways when it does not fit (cut short instead under reduced motion). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ScrollingLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val still = AppearanceStore.config.reduceMotion
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = if (still) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = if (still) modifier else modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000, initialDelayMillis = 2_000),
    )
}

/** "1:23 / 3:54", ticking while it plays; just the time played when the length is unknown. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayTime(info: NowPlayingInfo, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(info.playing, info.positionAt) {
        while (info.playing) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L - (info.positionNow(now) % 1_000L).coerceIn(0, 999))
        }
        now = SystemClock.elapsedRealtime()
    }
    val position = info.positionNow(now).let { if (info.durationMs > 0) it.coerceAtMost(info.durationMs) else it }
    val text = if (info.durationMs > 0) "${clockOf(position)} / ${clockOf(info.durationMs)}" else clockOf(position)
    Text(text = text, color = color, fontSize = fontSize, maxLines = 1, modifier = modifier)
}

/** m:ss, or h:mm:ss past an hour. */
fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Like or unlike the track in the playing app; dimmed, and says why, when the app takes no likes. */
@Composable
fun LoveButton(
    model: NowPlayingModel,
    info: NowPlayingInfo,
    size: Dp,
    modifier: Modifier = Modifier,
    down: FocusRequester? = null,
    onDown: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    HeaderIconButton(
        icon = if (info.loved) R.drawable.ic_heart_filled else R.drawable.ic_heart,
        description = if (info.loved) "Unlike" else "Like",
        modifier = modifier,
        size = size,
        dim = !info.canLove,
        down = down,
        onDown = onDown,
        onLongPress = onLongPress,
    ) {
        if (!model.toggleLove()) {
            Toast.makeText(context, "${info.appLabel} does not take likes from other apps", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, if (model.info?.loved == true) "Liked" else "Unliked", Toast.LENGTH_SHORT).show()
        }
    }
}
