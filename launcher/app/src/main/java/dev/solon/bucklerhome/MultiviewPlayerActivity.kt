package dev.solon.bucklerhome

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** One Multiview tile full screen, with sound and the player's own controls; Back returns to Home. */
class MultiviewPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val start = intent.getLongExtra(EXTRA_START, 0)
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        val exo = ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (start > 0) seekTo(start)
            prepare()
            playWhenReady = true
        }
        player = exo
        setContentView(
            PlayerView(this).apply {
                setShutterBackgroundColor(Color.BLACK)
                setBackgroundColor(Color.BLACK)
                this.player = exo
                keepScreenOn = true
                requestFocus()
            },
        )
    }

    override fun onStop() {
        super.onStop()
        // Leaving (Back, Home, another app) ends it: the tile on Home plays on its own.
        finish()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dev.solon.bucklerhome.MULTIVIEW_URL"
        const val EXTRA_START = "dev.solon.bucklerhome.MULTIVIEW_START"
    }
}
