package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Settings → Screensaver: a list of screensavers plus the shared "Start after". Each screensaver
 * opens its own page with Use this, Preview, and only the options that apply to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { ScreensaverPrefs.load(context) }
    var editing by remember { mutableStateOf<ScreensaverMode?>(null) }
    var lastEdited by remember { mutableStateOf<ScreensaverMode?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var customTimeout by remember { mutableStateOf(false) }
    val pageFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val flow = Arrangement.spacedBy(12.dp)

    BackHandler(enabled = editing != null) { editing = null }
    // The pressed button leaves composition when the view switches; put focus on the new view.
    LaunchedEffect(editing) {
        if (editing == null && lastEdited == null) return@LaunchedEffect
        delay(60)
        runCatching { (if (editing != null) pageFocus else listFocus).requestFocus() }
    }

    val mode = editing
    if (mode != null) {
        ScreensaverPage(
            mode = mode,
            cfg = cfg,
            tick = tick,
            uploading = uploading,
            firstFocus = pageFocus,
            onChanged = { tick++ },
            onUpload = { uploading = true },
            onBack = { editing = null },
        )
    } else {
        SectionTitle("Screensaver")
        SettingsRow("In use: ${cfg.mode.label}. Choose a screensaver for its settings and preview.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            ScreensaverMode.entries.forEach { entry ->
                SettingsAction(
                    entry.label,
                    selected = entry == cfg.mode,
                    focusRequester = if (entry == (lastEdited ?: cfg.mode)) listFocus else null,
                ) {
                    lastEdited = entry
                    editing = entry
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        StartAfterSection(tick = tick, onChanged = { tick++ }, onCustom = { customTimeout = true })
        Spacer(modifier = Modifier.height(12.dp))
        SettingsAction("System screensaver settings") {
            launchFirstThatWorks(
                context,
                "screensaver settings",
                listOf(Intent(Settings.ACTION_DREAM_SETTINGS), Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            )
        }
    }

    if (uploading) {
        UploadPhotosDialog(onDismiss = {
            uploading = false
            tick++
        })
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

/** One screensaver's page: what it shows, Use this / Preview / Back, then its own options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreensaverPage(
    mode: ScreensaverMode,
    cfg: ScreensaverConfig,
    tick: Int,
    uploading: Boolean,
    firstFocus: FocusRequester,
    onChanged: () -> Unit,
    onUpload: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val flow = Arrangement.spacedBy(12.dp)
    var confirmClear by remember(mode) { mutableStateOf(false) }
    val photoCount = remember(tick, uploading) { ScreensaverImages.uploaded(context).size }

    SectionTitle(mode.label)
    SettingsRow(describe(mode, cfg, photoCount))
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction(
            if (mode == cfg.mode) "In use" else "Use this screensaver",
            selected = mode == cfg.mode,
            focusRequester = firstFocus,
        ) {
            ScreensaverPrefs.setMode(context, mode)
            onChanged()
        }
        SettingsAction("Preview") {
            val intent = Intent(context, ScreensaverPreviewActivity::class.java)
                .putExtra(ScreensaverPreviewActivity.EXTRA_MODE, mode.name)
            safeLaunch(context, intent, "Preview")
        }
        SettingsAction("Back") { onBack() }
    }

    if (mode == ScreensaverMode.PHOTOS) {
        Spacer(modifier = Modifier.height(12.dp))
        SettingsRow("Categories (● = on). Slides take turns between them.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            PhotoCategory.entries.forEach { category ->
                SettingsAction(category.label, selected = category in cfg.categories) {
                    if (ScreensaverPrefs.toggleCategory(context, cfg.categories, category)) {
                        onChanged()
                    } else {
                        Toast.makeText(context, "Keep at least one category", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    if (mode == ScreensaverMode.UPLOADED) {
        Spacer(modifier = Modifier.height(12.dp))
        SettingsRow("My photos: $photoCount")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction("Upload from phone") { onUpload() }
            SettingsAction(if (confirmClear) "Press again to delete all" else "Clear my photos") {
                if (confirmClear) {
                    val removed = ScreensaverImages.clearUploaded(context)
                    confirmClear = false
                    onChanged()
                    Toast.makeText(context, "Removed $removed photo(s)", Toast.LENGTH_SHORT).show()
                } else {
                    confirmClear = true
                }
            }
        }
    }
    if (mode.isPhotos || mode.isRetro) {
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            if (mode.isPhotos) {
                SettingsAction("Change every ${cfg.intervalSeconds}s") {
                    ScreensaverPrefs.cycleInterval(context, cfg.intervalSeconds)
                    onChanged()
                }
            }
            SettingsAction("Clock overlay: ${if (cfg.showClock) "On" else "Off"}") {
                ScreensaverPrefs.setShowClock(context, !cfg.showClock)
                onChanged()
            }
        }
    }
}

private fun describe(mode: ScreensaverMode, cfg: ScreensaverConfig, photoCount: Int): String = when (mode) {
    ScreensaverMode.CLOCK -> "A slowly drifting clock. Nothing else to set."
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
