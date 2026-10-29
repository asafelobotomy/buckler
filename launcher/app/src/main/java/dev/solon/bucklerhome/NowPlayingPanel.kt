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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Compact mini player: artwork, title / artist and prev / play-pause / next for whichever app is
 * playing. Nothing is drawn while nothing plays, so an idle header stays quiet; if media access
 * has not been granted yet a single small prompt takes its place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingPanel(
    model: NowPlayingModel,
    below: FocusRequester?,
    metrics: HeaderMetrics,
    modifier: Modifier = Modifier,
    /** Hold OK on any of its buttons: the Side Stage's menu. */
    onHold: (() -> Unit)? = null,
) {
    val info = model.info
    when {
        info != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ColorFooter.copy(alpha = 0.55f))
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Artwork(info, metrics)
            Spacer(modifier = Modifier.size(metrics.clockGap))
            // The text yields first when space is tight, so the transport buttons always show.
            Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = metrics.titleMax)) {
                Text(
                    text = info.title,
                    color = ColorText,
                    fontSize = metrics.playingTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = info.subtitle.ifEmpty { info.appLabel },
                    color = ColorMuted,
                    fontSize = metrics.playingSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeaderIconButton(R.drawable.ic_prev, "Previous", size = metrics.control, down = below, onLongPress = onHold) { model.previous() }
                HeaderIconButton(
                    icon = if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    description = if (info.playing) "Pause" else "Play",
                    size = metrics.control,
                    down = below,
                    onLongPress = onHold,
                ) { model.togglePlayPause() }
                HeaderIconButton(R.drawable.ic_next, "Next", size = metrics.control, down = below, onLongPress = onHold) { model.next() }
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
