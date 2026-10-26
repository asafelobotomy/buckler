package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Settings → Screensaver: style, photo source, uploads and preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverPane() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val cfg = remember(tick) { ScreensaverPrefs.load(context) }
    var uploading by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val photoCount = remember(tick, uploading) { ScreensaverImages.uploaded(context).size }
    val flow = Arrangement.spacedBy(12.dp)
    val photoMode = cfg.mode.isPhotos
    var customTimeout by remember { mutableStateOf(false) }

    SectionTitle("Screensaver")
    SettingsRow(
        when (cfg.mode) {
            ScreensaverMode.CLOCK -> "A slowly drifting clock."
            ScreensaverMode.DVD -> "The Buckler mark and clock bounce around, changing colour on every edge."
            ScreensaverMode.PHOTOS ->
                "${cfg.category.label} photos from Wikimedia Commons featured pictures (free licences, credited on screen)."
            ScreensaverMode.UPLOADED -> "A slideshow of photos you uploaded ($photoCount on this TV)."
            ScreensaverMode.NOW_PLAYING ->
                "Whatever is playing: album art for music, artwork for films and TV, the thumbnail for " +
                    "YouTube, a LIVE card for Twitch and TiviMate. A clock when nothing plays (needs media access)."
            ScreensaverMode.MAZE -> "A walk through a brick maze, after the Windows 95 3D Maze."
            ScreensaverMode.PIPES -> "Pipes grow and twist through 3D space, after the Windows 3D Pipes."
            ScreensaverMode.MYSTIFY -> "Bouncing, colour-shifting outlines with trails, after Windows Mystify."
            ScreensaverMode.STARFIELD -> "Flying through a field of stars, after Windows Starfield."
        },
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        ScreensaverMode.entries.forEach { mode ->
            SettingsAction(mode.label, selected = mode == cfg.mode) {
                ScreensaverPrefs.setMode(context, mode)
                tick++
            }
        }
    }
    if (photoMode || cfg.mode.isRetro) {
        Spacer(modifier = Modifier.height(12.dp))
    }
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        if (cfg.mode == ScreensaverMode.PHOTOS) {
            SettingsAction("Category: ${cfg.category.label}") {
                ScreensaverPrefs.setCategory(context, cfg.category.next())
                tick++
            }
        }
        if (photoMode) {
            SettingsAction("Change every ${cfg.intervalSeconds}s") {
                ScreensaverPrefs.cycleInterval(context, cfg.intervalSeconds)
                tick++
            }
        }
        if (photoMode || cfg.mode.isRetro) {
            SettingsAction("Clock overlay: ${if (cfg.showClock) "On" else "Off"}") {
                ScreensaverPrefs.setShowClock(context, !cfg.showClock)
                tick++
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    StartAfterSection(tick = tick, onChanged = { tick++ }, onCustom = { customTimeout = true })

    Spacer(modifier = Modifier.height(12.dp))
    SettingsRow("My photos: $photoCount")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Upload from phone") { uploading = true }
        SettingsAction(if (confirmClear) "Press again to delete all" else "Clear my photos") {
            if (confirmClear) {
                val removed = ScreensaverImages.clearUploaded(context)
                confirmClear = false
                tick++
                Toast.makeText(context, "Removed $removed photo(s)", Toast.LENGTH_SHORT).show()
            } else {
                confirmClear = true
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Preview") {
            safeLaunch(context, Intent(context, ScreensaverPreviewActivity::class.java), "Preview")
        }
        SettingsAction("System screensaver settings") {
            launchFirstThatWorks(
                context,
                "screensaver settings",
                listOf(Intent(Settings.ACTION_DREAM_SETTINGS), Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            )
        }
    }

    if (uploading) {
        UploadPhotosDialog(onDismiss = { uploading = false })
    }
    if (customTimeout) {
        CustomTimeoutDialog(
            initialMs = SystemSettings.idleTimeoutMs(context),
            onDismiss = { customTimeout = false },
            onSave = { ms ->
                customTimeout = false
                applyIdleTimeout(context, ms)
                tick++
            },
        )
    }
}

private fun applyIdleTimeout(context: Context, ms: Int) {
    val set = SystemSettings.setIdleTimeoutMs(context, ms)
    // Choosing a delay also switches the screensaver back on if "Off" had disabled it.
    if (!SystemSettings.screensaverEnabled(context)) SystemSettings.setScreensaverEnabled(context, true)
    if (!set) needsPermission(context)
}

private fun needsPermission(context: Context) {
    Toast.makeText(context, "One-time setup needed: run ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
}

/** "Start after" choices: 60/90/120 s, 5/10/15 min, 1 hour, custom, or off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartAfterSection(tick: Int, onChanged: () -> Unit, onCustom: () -> Unit) {
    val context = LocalContext.current
    val timeoutMs = remember(tick) { SystemSettings.idleTimeoutMs(context) }
    val enabled = remember(tick) { SystemSettings.screensaverEnabled(context) }
    val canWrite = remember(tick) { SystemSettings.canWriteSystem(context) }
    val flow = Arrangement.spacedBy(12.dp)

    val current = if (enabled) ScreensaverTimeout.label(timeoutMs) else "Off"
    val note = if (canWrite) "" else "  (read-only until ${SystemSettings.GRANT_COMMAND} is run)"
    SettingsRow("Start screensaver after: $current$note")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        ScreensaverTimeout.PRESETS_MS.forEach { ms ->
            SettingsAction(ScreensaverTimeout.label(ms), selected = enabled && ms == timeoutMs) {
                applyIdleTimeout(context, ms)
                onChanged()
            }
        }
        SettingsAction("Custom…", selected = enabled && !ScreensaverTimeout.isPreset(timeoutMs)) { onCustom() }
        SettingsAction("Off", selected = !enabled) {
            if (SystemSettings.setScreensaverEnabled(context, false)) onChanged() else needsPermission(context)
        }
    }
}
