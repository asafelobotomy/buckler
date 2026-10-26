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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val ControlSize = 38.dp

/**
 * Compact mini player: artwork, title / artist and prev / play-pause / next for whichever app is
 * playing. Nothing is drawn while nothing plays, so an idle header stays quiet; if media access
 * has not been granted yet a single small prompt takes its place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingPanel(model: NowPlayingModel, below: FocusRequester?, modifier: Modifier = Modifier) {
    val info = model.info
    when {
        info != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ColorFooter.copy(alpha = 0.55f))
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Artwork(info)
            Spacer(modifier = Modifier.size(12.dp))
            // The text yields first when space is tight, so the transport buttons always show.
            Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 200.dp)) {
                Text(
                    text = info.title,
                    color = ColorText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = info.subtitle.ifEmpty { info.appLabel },
                    color = ColorMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeaderIconButton(R.drawable.ic_prev, "Previous", size = ControlSize, down = below) { model.previous() }
                HeaderIconButton(
                    icon = if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    description = if (info.playing) "Pause" else "Play",
                    size = ControlSize,
                    down = below,
                ) { model.togglePlayPause() }
                HeaderIconButton(R.drawable.ic_next, "Next", size = ControlSize, down = below) { model.next() }
            }
        }
        !model.accessGranted -> AccessPrompt(below, modifier)
        else -> Unit
    }
}

@Composable
private fun Artwork(info: NowPlayingInfo) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ColorTile),
    ) {
        val art = info.art
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = info.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(46.dp),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorHint),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun AccessPrompt(below: FocusRequester?, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier = modifier) {
        HeaderIconButton(
            icon = R.drawable.ic_music,
            description = "Allow media access to show Now Playing",
            size = ControlSize + 6.dp,
            dim = true,
            down = below,
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
