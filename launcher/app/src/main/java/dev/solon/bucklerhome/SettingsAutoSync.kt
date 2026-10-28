package dev.solon.bucklerhome

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Main Stage → Lyrics → Auto-sync. Opt-in: microphone-type permission, the voice model
 * (downloaded and checked), then Android's capture consent, which it asks for again after a restart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AutoSync.status.collectAsState()
    var enabled by remember { mutableStateOf(LyricsPrefs.autoSyncEnabled(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    val running = status == AutoSync.Status.LISTENING || status == AutoSync.Status.SILENT ||
        status == AutoSync.Status.STARTING

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        busy = null
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            LyricsPrefs.setAutoSyncEnabled(context, true)
            enabled = true
            LyricsSyncService.start(context, result.resultCode, data)
        } else {
            Toast.makeText(context, "Auto-sync needs that permission to hear the song", Toast.LENGTH_LONG).show()
        }
    }

    fun askConsent() {
        busy = "Getting the voice model…"
        scope.launch {
            val ready = withContext(Dispatchers.IO) { SileroVad.ensureModel(context) }
            if (!ready) {
                busy = null
                Toast.makeText(context, "Couldn't download the voice model", Toast.LENGTH_LONG).show()
                return@launch
            }
            busy = "Waiting for permission…"
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            consent.launch(mpm.createScreenCaptureIntent())
        }
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askConsent() else Toast.makeText(context, "Auto-sync needs audio permission", Toast.LENGTH_LONG).show()
    }

    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) askConsent() else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    PickerTitle("Auto-sync")
    SettingsRow(
        "Listens to what is playing and times the lyrics to the singing, song by song. Audio is analysed " +
            "on this TV and never stored or sent. Android shows a recording notice while it listens.",
    )
    SettingsRow(
        busy ?: when {
            !enabled -> "Auto-sync: off."
            status == AutoSync.Status.LISTENING -> "Auto-sync: listening. A song is timed after about 15 s of singing."
            status == AutoSync.Status.SILENT ->
                "Auto-sync: hearing nothing. The app may block capture, or run: " +
                    "adb shell settings put system volume_music_speaker 10, then restart."
            status == AutoSync.Status.STARTING -> "Auto-sync: starting…"
            status == AutoSync.Status.FAILED -> "Auto-sync: could not start. Try again."
            else -> "Auto-sync: paused. Android asks again after a restart."
        },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            !enabled -> SettingsAction("Turn on auto-sync") { start() }
            running -> SettingsAction("Turn off auto-sync") {
                LyricsSyncService.stop(context)
                LyricsPrefs.setAutoSyncEnabled(context, false)
                enabled = false
            }
            else -> {
                SettingsAction("Start listening") { start() }
                SettingsAction("Turn off auto-sync") {
                    LyricsPrefs.setAutoSyncEnabled(context, false)
                    enabled = false
                }
            }
        }
    }
}
