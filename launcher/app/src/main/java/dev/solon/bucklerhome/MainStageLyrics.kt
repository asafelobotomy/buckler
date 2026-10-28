package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Lookup progress for the track the header's Now Playing shows. */
private sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object Offline : LyricsState
    /** [key] is the song the lyrics belong to (see [LyricsPrefs.songKey]). */
    data class Ready(val lyrics: Lyrics, val key: String) : LyricsState
}

/**
 * Lyrics on the Main Stage for whatever the header's Now Playing shows, from NetEase or LRCLIB. Synced lyrics
 * light the current line as the song plays; plain lyrics move through in step with the track.
 * OK opens the playing app; hold OK to change the stage.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsStage(
    model: NowPlayingModel,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val context = LocalContext.current
    val info = model.info
    val music = info?.takeIf { it.kind == MediaKind.MUSIC }
    val fetched by produceState<LyricsState>(LyricsState.Idle, music?.title, music?.artist, music?.album) {
        val track = music ?: return@produceState run { value = LyricsState.Idle }
        value = LyricsState.Loading
        while (true) {
            val found = withContext(Dispatchers.IO) {
                LyricsClient.lookup(track.title, track.artist, track.album, track.durationMs)
            }
            if (found != null) {
                value = LyricsState.Ready(found, LyricsPrefs.songKey(track.title, track.artist))
                return@produceState
            }
            value = LyricsState.Offline
            delay(RETRY_MS)
        }
    }
    // Player lag, route delay (measured) and the route's fine-tune, plus this song's nudge (◀ ▶).
    val route = rememberAudioRoute(active = music?.playing == true)
    val routeTune = remember(route.kind) { LyricsPrefs.offset(context, route.kind) }
    val songKey = music?.let { LyricsPrefs.songKey(it.title, it.artist) }
    // Right after a track change the previous song's lyrics are still held for a frame; never show
    // them under the new song.
    val state = fetched.let { if (it is LyricsState.Ready && it.key != songKey) LyricsState.Loading else it }
    var songTune by remember(songKey) { mutableLongStateOf(songKey?.let { LyricsPrefs.songOffset(context, it) } ?: 0L) }
    val deviceShift = routeTune - route.delayMs + LyricsPrefs.playerShift(music?.packageName.orEmpty())
    val shift = deviceShift + songTune
    // Updated every frame while playing, and read only in derivedStateOf and draw lambdas, so the
    // card recomposes when the line or word changes and the dot just redraws.
    val position = remember { mutableLongStateOf(0L) }
    // Only while Home is in front: a frame loop under a dialog or an app switch keeps Home from idling.
    val resumed = rememberLifecycleAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(music, shift, resumed) {
        val track = music ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        position.longValue = track.positionNow() + shift
        while (track.playing) {
            withFrameMillis { position.longValue = track.positionNow() + shift }
        }
    }

    val source = ((state as? LyricsState.Ready)?.lyrics as? Lyrics.Synced)?.source
        ?: if (state is LyricsState.Ready) "LRCLIB" else null
    val credit = music?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" — ") }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Lyrics")
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionLeft -> LyricsPrefs.STEP_MS
                        Key.DirectionRight -> -LyricsPrefs.STEP_MS
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown && songKey != null) {
                        songTune = LyricsPrefs.setSongOffset(context, songKey, songTune + step)
                    }
                    true
                }
                .stageTarget(stageFocus, onDown, credit?.let { "Lyrics, $it" } ?: "Lyrics", onLongPress = onHold) {
                    when {
                        !model.accessGranted -> launchFirstThatWorks(
                            context,
                            "notification access",
                            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
                        )
                        info != null && info.packageName.isNotBlank() ->
                            launchPackage(context, info.packageName, info.appLabel.ifBlank { info.title })
                    }
                }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .padding(horizontal = 22.dp, vertical = if (metrics.compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 2.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (credit != null) {
                val song = if (songTune == 0L) "◀ ▶ this song" else "this song ${LyricsPrefs.describe(songTune)}"
                val timing = "${route.kind.label} ${LyricsPrefs.signed(deviceShift)}  ·  $song"
                Text(
                    text = "$credit  ·  ${source ?: "lyrics"}  ·  $timing",
                    color = ColorSection,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val message = when {
                !model.accessGranted -> "Lyrics follow the header's Now Playing. Press OK to allow media access."
                info == null -> "Play a song and its lyrics show here."
                music == null -> "Lyrics show for music. ${info.appLabel.ifBlank { "This app" }} is playing video."
                state == LyricsState.Loading || state == LyricsState.Idle -> "Looking up lyrics…"
                state == LyricsState.Offline -> "Couldn't reach the lyrics services. Trying again shortly."
                else -> null
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (message != null) {
                    Text(text = message, color = ColorMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
                } else {
                    val lyrics = (state as LyricsState.Ready).lyrics
                    LyricLines(lyrics, { position.longValue }, music?.durationMs ?: 0, metrics.cardHeight)
                }
            }
        }
    }
}

/**
 * The previous line and the next ones dimmed around the current line, all centred. Synced lines
 * sing word by word with a bouncing dot. A line change fades and lifts the block a little (unless
 * Reduce motion is on); long lines shrink to fit.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricLines(lyrics: Lyrics, positionMs: () -> Long, durationMs: Long, cardHeight: Dp) {
    val lines = remember(lyrics) {
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map { it.text }
            is Lyrics.Plain -> lyrics.lines
            Lyrics.Instrumental -> listOf("♪ Instrumental")
            Lyrics.NotFound -> listOf("No lyrics found for this track.")
        }
    }
    val current by remember(lyrics, durationMs) {
        derivedStateOf {
            when (lyrics) {
                is Lyrics.Synced -> LyricsClient.lineAt(lyrics.lines, positionMs())
                is Lyrics.Plain -> plainLineAt(lyrics.lines.size, positionMs(), durationMs)
                else -> 0
            }
        }
    }
    val pace = remember(lyrics) { (lyrics as? Lyrics.Synced)?.let { songPaceMs(it.lines) } ?: 0L }
    val (before, after) = lyricWindow(cardHeight)
    val calm = AppearanceStore.config.reduceMotion
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (calm) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                // The new line is there the moment it starts (a delayed fade read as late timing);
                // the old one goes at once, so the two never overlap.
                (fadeIn(tween(LINE_FADE_MS)) + slideInVertically(tween(LINE_FADE_MS)) { it / 8 }) togetherWith
                    ExitTransition.None
            }
        },
        label = "lyric line",
    ) { index ->
        val sung = (lyrics as? Lyrics.Synced)?.let { remember(lyrics, index) { karaokeLine(it.lines, index, pace) } }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (i in (index - before)..(index + after)) {
                val raw = lines.getOrNull(i) ?: if (i == index) "" else continue
                val text = raw.ifBlank { "♪" }
                val now = i == index
                if (now && sung != null) {
                    KaraokeText(sung, positionMs, currentLineSize(text, cardHeight), calm)
                    continue
                }
                Text(
                    text = text,
                    color = if (now) ColorText else ColorMuted,
                    fontSize = if (now) currentLineSize(text, cardHeight) else 16.sp,
                    fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Dimmed lines before and after the current one that fit [cardHeight] under the credit line. */
internal fun lyricWindow(cardHeight: Dp): Pair<Int, Int> {
    // Under the credit line and the dot's bounce room above the current line.
    val dims = ((cardHeight - 88.dp) / 26.dp).toInt().coerceIn(1, 6)
    val before = if (dims >= 2) (dims + 1) / 3 else 0
    return before to dims - before
}

/** Large for short lines; long lines step down instead of being cut off. */
private fun currentLineSize(text: String, cardHeight: Dp) = when {
    cardHeight < 120.dp -> if (text.length <= 60) 20.sp else 17.sp
    text.length <= 56 -> 24.sp
    text.length <= 72 -> 20.sp
    else -> 18.sp
}

/** Untimed lyrics: spread the lines evenly over the track. */
internal fun plainLineAt(count: Int, positionMs: Long, durationMs: Long): Int {
    if (count <= 0 || durationMs <= 0) return 0
    return ((positionMs.coerceIn(0, durationMs) * count) / durationMs).toInt().coerceAtMost(count - 1)
}

private const val RETRY_MS = 15_000L
private const val LINE_FADE_MS = 120
