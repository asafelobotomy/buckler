package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/** Now Playing on the Main Stage. The header copy stays hidden while this mode is selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingStage(
    model: NowPlayingModel,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val context = LocalContext.current
    val info = model.info ?: RecentMedia.recent()
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Now Playing")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info != null) {
                PlayingSummary(info, stageFocus, onDown, onHold, metrics.cardHeight - 32.dp) {
                    if (info.packageName.isNotBlank()) {
                        launchPackage(context, info.packageName, info.appLabel.ifBlank { info.title })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeaderIconButton(R.drawable.ic_prev, "Previous", size = 52.dp, onDown = onDown) { model.previous() }
                    HeaderIconButton(
                        icon = if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        description = if (info.playing) "Pause" else "Play",
                        size = 52.dp,
                        onDown = onDown,
                    ) { model.togglePlayPause() }
                    HeaderIconButton(R.drawable.ic_next, "Next", size = 52.dp, onDown = onDown) { model.next() }
                }
            } else if (!model.accessGranted) {
                Text(
                    text = "Allow media access to show what's playing.",
                    color = ColorMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                GrantMediaAccess(stageFocus, onDown, onHold)
            } else {
                Text(
                    text = "Nothing playing.",
                    color = ColorMuted,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .weight(1f)
                        .stageTarget(stageFocus, onDown, "Now Playing", onLongPress = onHold) {},
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowScope.PlayingSummary(
    info: NowPlayingInfo,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    artSize: Dp,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .stageTarget(stageFocus, onDown, "Open ${info.appLabel.ifBlank { info.title }}", onHold, onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageArt(info, artSize)
        Spacer(modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // One line each, scrolling sideways when long.
            ScrollingLine(info.title, ColorText, if (artSize < 100.dp) 18.sp else 22.sp, fontWeight = FontWeight.Medium)
            ScrollingLine(info.subtitle.ifBlank { info.appLabel }, ColorMuted, 15.sp)
            PlayTime(info, ColorHint, 13.sp)
        }
    }
}

@Composable
private fun StageArt(info: NowPlayingInfo?, artSize: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(artSize)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorFooter),
    ) {
        val art = info?.art
        val artUri = info?.artUri
        when {
            art != null -> Image(
                bitmap = art.asImageBitmap(),
                contentDescription = info?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            !artUri.isNullOrBlank() -> AsyncImage(
                model = artUri,
                contentDescription = info?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Image(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorHint),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun GrantMediaAccess(stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit) {
    val context = LocalContext.current
    // The stage's only target until access is granted, so it carries the stage's hold too.
    HeaderIconButton(
        icon = R.drawable.ic_music,
        description = "Allow media access to show Now Playing",
        size = 52.dp,
        onDown = onDown,
        focusRequester = stageFocus,
        onLongPress = onHold,
    ) {
        launchFirstThatWorks(
            context,
            "notification access",
            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
        )
    }
}
