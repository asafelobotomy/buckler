package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compact mini player: artwork, title / artist (scrolling when long), time played, and prev /
 * play-pause / next / like for whichever app is playing. On the D-pad it is one stop, framed: OK
 * goes into its controls, Back comes out, and holding OK opens the Side Stage's menu. Nothing is
 * drawn while nothing plays, so an idle header stays quiet; if media access has not been granted
 * yet a single small prompt takes its place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingPanel(
    model: NowPlayingModel,
    below: FocusRequester?,
    metrics: HeaderMetrics,
    modifier: Modifier = Modifier,
    /** Hold OK on it (or on any of its controls): the Side Stage's menu. */
    onHold: (() -> Unit)? = null,
) {
    val info = model.info
    var inside by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    val panel = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(inside) {
        if (!inside) return@LaunchedEffect
        delay(30)
        runCatching { firstControl.requestFocus() }
    }
    // Out of the controls (Up, Down, past either end): one stop again. Debounced, as moving between
    // the buttons, or going in, briefly reports the row unfocused.
    LaunchedEffect(inside, rowFocused) {
        if (!inside || rowFocused) return@LaunchedEffect
        delay(250)
        inside = false
    }
    fun leaveControls() {
        inside = false
        scope.launch {
            delay(30)
            runCatching { panel.requestFocus() }
        }
    }
    val inControls = Modifier.focusProperties { canFocus = inside }
    when {
        info != null -> Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ColorFooter.copy(alpha = 0.55f)),
        ) {
            // The panel's own stop, behind its content, so OK and hold on the controls stay theirs.
            Box(
                Modifier
                    .matchParentSize()
                    .focusProperties { canFocus = !inside }
                    .stageTarget(panel, onDown = { runCatching { below?.requestFocus() } }, "Now Playing: ${info.title}", onLongPress = { onHold?.invoke() }) {
                        inside = true
                    },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Artwork(info, metrics)
                Spacer(modifier = Modifier.size(metrics.clockGap))
                // The text yields first when space is tight, so the controls always show.
                Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = metrics.titleMax)) {
                    ScrollingLine(info.title, ColorText, metrics.playingTitle, fontWeight = FontWeight.SemiBold)
                    ScrollingLine(info.subtitle.ifEmpty { info.appLabel }, ColorMuted, metrics.playingSubtitle)
                    PlayTime(info, ColorHint, metrics.playingSubtitle * 0.85f)
                }
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.Back) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyUp) leaveControls()
                            true
                        },
                ) {
                    HeaderIconButton(R.drawable.ic_prev, "Previous", inControls, size = metrics.control, down = below, onLongPress = onHold) { model.previous() }
                    HeaderIconButton(
                        icon = if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        description = if (info.playing) "Pause" else "Play",
                        modifier = inControls,
                        size = metrics.control,
                        down = below,
                        focusRequester = firstControl,
                        onLongPress = onHold,
                    ) { model.togglePlayPause() }
                    HeaderIconButton(R.drawable.ic_next, "Next", inControls, size = metrics.control, down = below, onLongPress = onHold) { model.next() }
                    LoveButton(model, info, metrics.control, inControls, down = below, onLongPress = onHold)
                }
            }
        }
        !model.accessGranted -> AccessPrompt(below, metrics, modifier, onHold)
        else -> Unit
    }
}

@Composable
private fun Artwork(info: NowPlayingInfo, metrics: HeaderMetrics) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(metrics.art)
            .clip(RoundedCornerShape(8.dp))
            .background(ColorTile),
    ) {
        val art = info.art
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = info.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(metrics.art),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorHint),
                modifier = Modifier.size(metrics.artGlyph),
            )
        }
    }
}

@Composable
private fun AccessPrompt(below: FocusRequester?, metrics: HeaderMetrics, modifier: Modifier, onHold: (() -> Unit)?) {
    val context = LocalContext.current
    Box(modifier = modifier) {
        HeaderIconButton(
            icon = R.drawable.ic_music,
            description = "Allow media access to show Now Playing",
            size = metrics.icon,
            dim = true,
            down = below,
            onLongPress = onHold,
        ) {
            launchFirstThatWorks(
                context,
                "notification access",
                listOf(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                ),
            )
        }
    }
}
