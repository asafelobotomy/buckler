package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    data class Ready(val lyrics: Lyrics) : LyricsState
}

/**
 * Lyrics on the Main Stage for whatever the header's Now Playing shows, from LRCLIB. Synced lyrics
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
    val state by produceState<LyricsState>(LyricsState.Idle, music?.title, music?.artist, music?.album) {
        val track = music ?: return@produceState run { value = LyricsState.Idle }
        value = LyricsState.Loading
        while (true) {
            val found = withContext(Dispatchers.IO) {
                LyricsClient.lookup(track.title, track.artist, track.album, track.durationMs)
            }
            if (found != null) {
                value = LyricsState.Ready(found)
                return@produceState
            }
            value = LyricsState.Offline
            delay(RETRY_MS)
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(music) {
        while (music != null) {
            position = music.positionNow()
            delay(TICK_MS)
        }
    }

    val credit = music?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" — ") }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Lyrics")
        Column(
            modifier = Modifier
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
        ) {
            if (credit != null) {
                Text(
                    text = "$credit  ·  LRCLIB",
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
                state == LyricsState.Offline -> "Lyrics unavailable offline. Trying again shortly."
                else -> null
            }
            if (message != null) {
                Text(text = message, color = ColorMuted, fontSize = 16.sp)
            } else {
                val lyrics = (state as LyricsState.Ready).lyrics
                LyricLines(lyrics, position, music?.durationMs ?: 0, metrics.cardHeight)
            }
        }
    }
}

/** The current line large and bright, with the one before and the ones after dimmed. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricLines(lyrics: Lyrics, positionMs: Long, durationMs: Long, cardHeight: Dp) {
    val (lines, current) = when (lyrics) {
        is Lyrics.Synced -> lyrics.lines.map { it.text } to LyricsClient.lineAt(lyrics.lines, positionMs)
        is Lyrics.Plain -> lyrics.lines to plainLineAt(lyrics.lines.size, positionMs, durationMs)
        Lyrics.Instrumental -> listOf("♪ Instrumental") to 0
        Lyrics.NotFound -> listOf("No lyrics found for this track.") to 0
    }
    // As many dimmed lines as the card fits: one before when there is room, the rest after.
    val compact = cardHeight < 120.dp
    val spare = ((cardHeight - 84.dp) / 26.dp).toInt().coerceIn(1, 4)
    val before = if (spare >= 3) 1 else 0
    val after = spare - before
    for (i in (current - before)..(current + after)) {
        val text = lines.getOrNull(i)?.ifBlank { "♪" } ?: if (i == current) "♪" else continue
        val now = i == current
        Text(
            text = text,
            color = if (now) ColorText else ColorMuted,
            fontSize = if (now) (if (compact) 20.sp else 24.sp) else 16.sp,
            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Untimed lyrics: spread the lines evenly over the track. */
internal fun plainLineAt(count: Int, positionMs: Long, durationMs: Long): Int {
    if (count <= 0 || durationMs <= 0) return 0
    return ((positionMs.coerceIn(0, durationMs) * count) / durationMs).toInt().coerceAtMost(count - 1)
}

private const val TICK_MS = 250L
private const val RETRY_MS = 60_000L
