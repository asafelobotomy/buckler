package dev.solon.bucklerhome

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Multiview stage: up to three videos side by side, all playing, muted but for the one that is
 * highlighted (none while focus is elsewhere). OK opens a tile full screen with sound; hold OK for
 * the stage menu. Players run only while Home is on screen, so an app opened over Home gets the
 * video decoders back.
 */
@Composable
fun MultiviewStage(stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit, metrics: StageMetrics) {
    val context = LocalContext.current
    remember { MultiviewStore.load(context) }
    val tiles = MultiviewStore.tiles
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    var heard by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(MultiviewStore.TILES) { if (it == 0) stageFocus else FocusRequester() } }
    // Where each player has got to, so full screen, and the tile after Home was hidden, carry on from there.
    val positions = remember { LongArray(MultiviewStore.TILES) }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Multiview", "The highlighted video has sound. OK: full screen. Hold OK: options.")
        Row(Modifier.fillMaxWidth().height(metrics.cardHeight), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.forEachIndexed { i, tile ->
                // Looked up off the main thread: Stremio asks its add-ons each time.
                val source by produceState<TileSource?>(null, tile) {
                    value = tile?.let { withContext(Dispatchers.IO) { MultiviewStore.resolve(context, it) } }
                }
                val url = source?.url
                // A film or episode carries on where it was; IPTV and cameras are live, so they rejoin live.
                val onDemand = tile?.kind == MultiviewKind.JELLYFIN || tile?.kind == MultiviewKind.STREMIO
                val resumeAt = if (onDemand) positions[i].takeIf { it > 0 } ?: tile.startMs else 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .onFocusChanged { if (it.hasFocus) heard = i else if (heard == i) heard = null }
                        .stageTarget(requesters[i], onDown, tile?.title ?: "Empty tile ${i + 1}", onLongPress = onHold) {
                            if (tile == null || url == null) {
                                BucklerSettingsActivity.open(context, SettingsPane.MAIN_STAGE)
                            } else {
                                context.startActivity(
                                    Intent(context, MultiviewPlayerActivity::class.java)
                                        .putExtra(MultiviewPlayerActivity.EXTRA_URL, url)
                                        .putExtra(MultiviewPlayerActivity.EXTRA_START, resumeAt),
                                )
                            }
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        tile == null -> TileNote("Tile ${i + 1} is empty. OK to choose a video.")
                        source == null -> TileNote("${tile.title}…")
                        url == null -> TileNote("${tile.title}: ${source?.problem}")
                        visible -> TilePlayer(url, resumeAt, audible = heard == i) { positions[i] = it }
                        else -> TileNote(tile.title)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TileNote(text: String) {
    Text(text, color = ColorMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
}

/** One stream, muted unless [audible]; only an audible tile takes audio focus (pausing other audio). */
@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun TilePlayer(url: String, startMs: Long, audible: Boolean, onPosition: (Long) -> Unit) {
    val context = LocalContext.current
    var error by remember(url) { mutableStateOf<String?>(null) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startMs > 0) seekTo(startMs)
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
            }
        }
        player.addListener(listener)
        onDispose {
            onPosition(player.currentPosition)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, audible) {
        player.volume = if (audible) 1f else 0f
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        player.setAudioAttributes(attrs, audible)
        // Muted tiles only watch: keep their position for full screen.
        while (true) {
            onPosition(player.currentPosition)
            kotlinx.coroutines.delay(2_000)
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    error?.let { TileNote("Can't play: $it") }
}
