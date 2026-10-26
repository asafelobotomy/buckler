package dev.solon.bucklerhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat

/**
 * Android only lets an app read other apps' media sessions when it holds notification-listener
 * access, so this service is the handle the user grants in Settings. The system keeps it bound,
 * which also lets it remember the last thing played for the Now Playing screensaver: video apps
 * such as YouTube drop their session as soon as the screensaver covers them.
 */
class NowPlayingListener : NotificationListenerService() {
    private var model: NowPlayingModel? = null

    override fun onListenerConnected() {
        model = NowPlayingModel(this).also { m ->
            m.onChange = { RecentMedia.remember(m.info) }
            m.start()
        }
    }

    override fun onListenerDisconnected() {
        model?.stop()
        model = null
    }
}

/** The most recent media seen by [NowPlayingListener], kept in memory only. */
object RecentMedia {
    private const val MAX_AGE_MS = 30 * 60_000L

    @Volatile
    private var last: Pair<NowPlayingInfo, Long>? = null

    fun remember(info: NowPlayingInfo?) {
        if (info != null) last = info to SystemClock.elapsedRealtime()
    }

    /** Shown as paused: if it were still playing, its session would still be active. */
    fun recent(): NowPlayingInfo? {
        val (info, at) = last ?: return null
        return info.copy(playing = false).takeIf { SystemClock.elapsedRealtime() - at <= MAX_AGE_MS }
    }
}

data class NowPlayingInfo(
    val title: String,
    val subtitle: String,
    val appLabel: String,
    val playing: Boolean,
    val art: Bitmap?,
    val packageName: String = "",
    val kind: MediaKind = MediaKind.VIDEO,
    /** Remote or content artwork when the session sends a link instead of a bitmap. */
    val artUri: String? = null,
)

fun nowPlayingAccessGranted(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Tracks the most relevant active media session and forwards transport commands to it. */
class NowPlayingModel(private val context: Context) {
    var info by mutableStateOf<NowPlayingInfo?>(null)
        private set
    var accessGranted by mutableStateOf(false)
        private set

    /** Called on the main thread after every change, for plain-View consumers like the screensaver. */
    var onChange: (() -> Unit)? = null

    private val listenerComponent = ComponentName(context, NowPlayingListener::class.java)
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var selected: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = reload()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { reload() }

    fun start() {
        accessGranted = nowPlayingAccessGranted(context)
        if (!accessGranted || manager == null) {
            info = null
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        } catch (_: SecurityException) {
            accessGranted = false
            return
        }
        reload()
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
        selected = null
    }

    private fun reload() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = try {
            manager?.getActiveSessions(listenerComponent).orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        controllers.forEach { it.registerCallback(controllerCallback) }
        refresh()
    }

    private fun refresh() {
        val best = controllers.firstOrNull { it.isPlaying() }
            ?: controllers.firstOrNull { it.metadata?.description?.title != null }
        selected = best
        info = best?.let { toInfo(it) }
        onChange?.invoke()
    }

    private fun toInfo(controller: MediaController): NowPlayingInfo? {
        val meta = controller.metadata ?: return null
        val title = meta.description?.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val kind = MediaKinds.classify(controller.packageName, hasArtist = !artist.isNullOrBlank(), hasAlbum = !album.isNullOrBlank())
        val subtitle = if (kind == MediaKind.MUSIC) {
            listOfNotNull(artist, album).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        } else {
            (meta.description?.subtitle?.toString()?.takeIf { it.isNotBlank() } ?: artist).orEmpty()
        }
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.isNotBlank() } }
            ?: meta.description?.iconUri?.toString()
        return NowPlayingInfo(
            title = title,
            subtitle = subtitle,
            appLabel = appLabel(controller.packageName),
            playing = controller.isPlaying(),
            art = art,
            packageName = controller.packageName,
            kind = kind,
            artUri = artUri,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun togglePlayPause() {
        val c = selected ?: return
        if (c.isPlaying()) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { selected?.transportControls?.skipToNext() }

    fun previous() { selected?.transportControls?.skipToPrevious() }
}

private fun MediaController.isPlaying(): Boolean {
    val s = playbackState?.state
    return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
}

@Composable
fun rememberNowPlaying(enabled: Boolean, resumeTick: Int): NowPlayingModel {
    val context = LocalContext.current
    val model = remember { NowPlayingModel(context) }
    // resumeTick: re-read access after the user returns from the notification-access screen.
    DisposableEffect(enabled, resumeTick) {
        if (enabled) model.start()
        onDispose { model.stop() }
    }
    return model
}
