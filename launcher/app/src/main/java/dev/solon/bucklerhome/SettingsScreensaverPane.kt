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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Screensaver → Global: which screensaver is in use and the shared "Start after". Each
 * screensaver has its own page in Screensaver's list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { ScreensaverPrefs.load(context) }
    var customTimeout by remember { mutableStateOf(false) }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Screensaver")
    SettingsRow("Each screensaver has its own page with its own options, in the Screensaver list (Back). The highlighted card runs.")
    PickerTitle("In use")
    ScreensaverPicker(cfg.mode) {
        ScreensaverPrefs.setMode(context, it)
        tick++
    }
    Spacer(modifier = Modifier.height(18.dp))
    StartAfterSection(tick = tick, onChanged = { tick++ }, onCustom = { customTimeout = true })
    Spacer(modifier = Modifier.height(12.dp))
    SettingsAction("System screensaver settings") {
        launchFirstThatWorks(
            context,
            "screensaver settings",
            listOf(Intent(Settings.ACTION_DREAM_SETTINGS), Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        )
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

/**
 * Settings → Screensaver → one screensaver: what it shows, Use this / Preview, its own extras
 * (photo categories, uploads), then a row for each of its [ScreensaverMode.options].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverModePane(mode: ScreensaverMode) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { ScreensaverPrefs.load(context) }
    var uploading by remember { mutableStateOf(false) }
    var confirmClear by remember(mode) { mutableStateOf(false) }
    val photoCount = remember(tick, uploading) { ScreensaverImages.uploaded(context).size }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle(mode.label)
    // This screensaver with its options as set below: a still, running while highlighted. OK opens it full screen.
    var running by remember(mode) { mutableStateOf(false) }
    PreviewCard(
        label = "Preview full screen",
        selected = false,
        modifier = Modifier.onFocusChanged { running = it.hasFocus },
        width = 360.dp,
        // A TV's shape, so the screensaver shows as it will.
        previewHeight = 186.dp,
        onClick = {
            val intent = Intent(context, ScreensaverPreviewActivity::class.java)
                .putExtra(ScreensaverPreviewActivity.EXTRA_MODE, mode.name)
            safeLaunch(context, intent, "Preview")
        },
    ) { ScreensaverCard(mode, live = running, saved = cfg) }
    Spacer(modifier = Modifier.height(12.dp))
    SettingsRow(describe(mode, cfg, photoCount))
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction(if (mode == cfg.mode) "In use" else "Use this screensaver", selected = mode == cfg.mode) {
            ScreensaverPrefs.setMode(context, mode)
            tick++
        }
    }

    if (mode == ScreensaverMode.PHOTOS) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Categories")
        SettingsRow("Slides take turns between the ones that are on.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            PhotoCategory.entries.forEach { category ->
                SettingsAction(category.label, selected = category in cfg.categories) {
                    if (ScreensaverPrefs.toggleCategory(context, cfg.categories, category)) {
                        tick++
                    } else {
                        Toast.makeText(context, "Keep at least one category", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    if (mode == ScreensaverMode.UPLOADED) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Photos")
        SettingsRow("$photoCount on this TV. Send more from a phone on the same Wi-Fi.")
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
    }

    val values = cfg.options[mode].orEmpty()
    mode.options.forEach { option ->
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle(option.title)
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            val current = values[option] ?: option.default
            option.choices.forEach { (value, label) ->
                SettingsAction(label, selected = value == current) {
                    ScreensaverPrefs.setOption(context, mode, option, value)
                    tick++
                }
            }
        }
    }

    if (uploading) {
        UploadPhotosDialog(onDismiss = {
            uploading = false
            tick++
        })
    }
}

private fun describe(mode: ScreensaverMode, cfg: ScreensaverConfig, photoCount: Int): String = when (mode) {
    ScreensaverMode.CLOCK -> "A large clock that glides to a new spot now and then, so nothing burns in."
    ScreensaverMode.DVD -> "The Buckler mark and clock bounce around, changing colour on every edge."
    ScreensaverMode.PHOTOS -> {
        val picked = PhotoCategory.entries.filter { it in cfg.categories }.joinToString(", ") { it.label }
        "$picked photos from Wikimedia Commons featured pictures (free licences, credited on screen)."
    }
    ScreensaverMode.UPLOADED -> "A slideshow of photos you uploaded ($photoCount on this TV)."
    ScreensaverMode.NOW_PLAYING ->
        "Whatever is playing: album art for music, artwork for films and TV, the thumbnail for " +
            "YouTube, a LIVE card for Twitch and TiviMate. A clock when nothing plays (needs media access)."
    ScreensaverMode.MAZE -> "A walk through a brick maze, after the Windows 95 3D Maze."
    ScreensaverMode.PIPES -> "Pipes grow and twist through 3D space, after the Windows 3D Pipes."
    ScreensaverMode.MYSTIFY -> "Bouncing, colour-shifting outlines with trails, after Windows Mystify."
    ScreensaverMode.STARFIELD -> "Flying through a field of stars, after Windows Starfield."
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
