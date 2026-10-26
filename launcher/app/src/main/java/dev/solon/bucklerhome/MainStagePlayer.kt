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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import coil.compose.AsyncImage

/** Now Playing on the Main Stage. The header copy stays hidden while this mode is selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingStage(
    model: NowPlayingModel,
    stageFocus: FocusRequester,
    below: FocusRequester,
) {
    val info = model.info ?: RecentMedia.recent()
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Now Playing")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .then(
                    if (info == null && model.accessGranted) {
                        Modifier.focusRequester(stageFocus).focusProperties { down = below }.focusable()
                    } else {
                        Modifier
                    },
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageArt(info)
            Spacer(modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    info != null -> {
                        Text(
                            text = info.title,
                            color = ColorText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = info.subtitle.ifBlank { info.appLabel },
                            color = ColorMuted,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (info.subtitle.isNotBlank()) {
                            Text(text = info.appLabel, color = ColorHint, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    !model.accessGranted -> {
                        Text(text = "Allow media access to show what's playing.", color = ColorMuted, fontSize = 16.sp)
                    }
                    else -> Text(text = "Nothing playing.", color = ColorMuted, fontSize = 16.sp)
                }
            }
            when {
                info != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeaderIconButton(R.drawable.ic_prev, "Previous", size = 52.dp, down = below) { model.previous() }
                    HeaderIconButton(
                        icon = if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        description = if (info.playing) "Pause" else "Play",
                        size = 52.dp,
                        down = below,
                        focusRequester = stageFocus,
                    ) { model.togglePlayPause() }
                    HeaderIconButton(R.drawable.ic_next, "Next", size = 52.dp, down = below) { model.next() }
                }
                !model.accessGranted -> GrantMediaAccess(stageFocus, below)
            }
        }
    }
}

@Composable
private fun StageArt(info: NowPlayingInfo?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(144.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorFooter),
    ) {
        val art = info?.art
        val artUri = info?.artUri
        when {
            art != null && info != null -> Image(
                bitmap = art.asImageBitmap(),
                contentDescription = info.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(144.dp),
            )
            !artUri.isNullOrBlank() && info != null -> AsyncImage(
                model = artUri,
                contentDescription = info.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(144.dp),
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
private fun GrantMediaAccess(stageFocus: FocusRequester, below: FocusRequester) {
    val context = LocalContext.current
    HeaderIconButton(
        icon = R.drawable.ic_music,
        description = "Allow media access to show Now Playing",
        size = 52.dp,
        down = below,
        focusRequester = stageFocus,
    ) {
        launchFirstThatWorks(
            context,
            "notification access",
            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
        )
    }
}
