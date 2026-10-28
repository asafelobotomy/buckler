package dev.solon.bucklerhome

import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

class BucklerSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppearanceStore.ensureLoaded(this)
        setContent {
            BucklerHomeTheme {
                BucklerSettingsScreen(onClose = { finish() })
            }
        }
    }
}

/**
 * Appearance first, with a submenu for each part of Home top to bottom (Header, Main Stage,
 * Shelves), then Screensaver with a submenu for each screensaver ([saver]), then the rest, then
 * upkeep. [parent] puts an entry under another in the menu.
 */
enum class SettingsPane(val label: String, val parent: SettingsPane? = null, val saver: ScreensaverMode? = null) {
    APPEARANCE("Appearance"),
    HEADER("Header", APPEARANCE),
    MAIN_STAGE("Main Stage", APPEARANCE),
    SHELVES("Shelves", APPEARANCE),
    SCREENSAVER("Screensaver"),
    SAVER_CLOCK(ScreensaverMode.CLOCK.label, SCREENSAVER, ScreensaverMode.CLOCK),
    SAVER_DVD(ScreensaverMode.DVD.label, SCREENSAVER, ScreensaverMode.DVD),
    SAVER_PHOTOS(ScreensaverMode.PHOTOS.label, SCREENSAVER, ScreensaverMode.PHOTOS),
    SAVER_UPLOADED(ScreensaverMode.UPLOADED.label, SCREENSAVER, ScreensaverMode.UPLOADED),
    SAVER_NOW_PLAYING(ScreensaverMode.NOW_PLAYING.label, SCREENSAVER, ScreensaverMode.NOW_PLAYING),
    SAVER_MAZE(ScreensaverMode.MAZE.label, SCREENSAVER, ScreensaverMode.MAZE),
    SAVER_PIPES(ScreensaverMode.PIPES.label, SCREENSAVER, ScreensaverMode.PIPES),
    SAVER_MYSTIFY(ScreensaverMode.MYSTIFY.label, SCREENSAVER, ScreensaverMode.MYSTIFY),
    SAVER_STARFIELD(ScreensaverMode.STARFIELD.label, SCREENSAVER, ScreensaverMode.STARFIELD),
    ACCESSIBILITY("Accessibility"),
    REMOTE("Remote"),
    APPS("Apps"),
    COVERS("Covers"),
    UPDATES("Updates"),
    ABOUT("About"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BucklerSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var pane by remember { mutableStateOf(SettingsPane.APPEARANCE) }
    var connectIgdb by remember { mutableStateOf(false) }
    var connectSgdb by remember { mutableStateOf(false) }
    var connectGithub by remember { mutableStateOf(false) }
    var accountTick by remember { mutableIntStateOf(0) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var availableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    // Pressing Left inside a pane returns to the nav entry for that pane, not whichever is nearest.
    val navFocus = remember { SettingsPane.entries.associateWith { FocusRequester() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(48.dp)
            // Re-focus after account changes rebuild the pane and drop the focused button.
            .autoFocus(accountTick),
    ) {
        Text(
            text = "Buckler Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = ColorText,
        )
        Text(
            text = "buckler-home ${BuildConfig.VERSION_NAME}",
            fontSize = 14.sp,
            color = ColorMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.weight(1f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(220.dp)
                    .verticalScroll(rememberScrollState())
                    .trapVerticalFocus(),
            ) {
                SettingsPane.entries.forEach { entry ->
                    SettingsNav(entry.label, pane == entry, navFocus.getValue(entry), nested = entry.parent != null) {
                        pane = entry
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                SettingsAction("Close", onClick = onClose)
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .trapVerticalFocus(onLeft = { navFocus.getValue(pane) }),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    when (pane) {
                        SettingsPane.APPEARANCE -> AppearancePane(onOpen = { pane = it })
                        SettingsPane.HEADER -> HeaderPane()
                        SettingsPane.MAIN_STAGE -> MainStagePane()
                        SettingsPane.SHELVES -> ShelvesPane()
                        SettingsPane.ACCESSIBILITY -> AccessibilityPane()
                        SettingsPane.SCREENSAVER -> ScreensaverPane(onOpen = { pane = it })
                        SettingsPane.SAVER_CLOCK, SettingsPane.SAVER_DVD, SettingsPane.SAVER_PHOTOS,
                        SettingsPane.SAVER_UPLOADED, SettingsPane.SAVER_NOW_PLAYING, SettingsPane.SAVER_MAZE,
                        SettingsPane.SAVER_PIPES, SettingsPane.SAVER_MYSTIFY, SettingsPane.SAVER_STARFIELD,
                        -> ScreensaverModePane(pane.saver ?: ScreensaverMode.CLOCK)
                        SettingsPane.UPDATES -> UpdatesPane(
                            accountTick = accountTick,
                            updateResult = updateResult,
                            onResult = { updateResult = it },
                            onAvailable = { availableRelease = it },
                            onConnectGithub = { connectGithub = true },
                            onAccountChanged = { accountTick++ },
                        )
                        SettingsPane.COVERS -> CoversPane(
                            accountTick = accountTick,
                            onConnectSgdb = { connectSgdb = true },
                            onConnectIgdb = { connectIgdb = true },
                            onAccountChanged = { accountTick++ },
                        )
                        SettingsPane.REMOTE -> RemotePane()
                        SettingsPane.APPS -> AppsPane()
                        SettingsPane.ABOUT -> AboutPane()
                    }
                }
            }
        }
    }

    if (connectIgdb) {
        ConnectIgdbDialog(
            onDismiss = { connectIgdb = false },
            onLinked = {
                connectIgdb = false
                accountTick++
                Toast.makeText(context, "IGDB linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (connectSgdb) {
        ConnectSgdbDialog(
            onDismiss = { connectSgdb = false },
            onLinked = {
                connectSgdb = false
                accountTick++
                Toast.makeText(context, "SteamGridDB linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (connectGithub) {
        ConnectGithubDialog(
            onDismiss = { connectGithub = false },
            onLinked = {
                connectGithub = false
                accountTick++
                Toast.makeText(context, "GitHub token linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    availableRelease?.let { release ->
        UpdateAvailableDialog(release = release, onDismiss = { availableRelease = null })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsNav(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    nested: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        // Submenus sit indented under their parent.
        modifier = Modifier.padding(start = if (nested) 22.dp else 0.dp).fillMaxWidth().focusRequester(focusRequester),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ColorBrand else ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = if (selected) ColorOnBrand else ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (nested) 6.dp else 8.dp),
            fontSize = if (nested) 15.sp else 16.sp,
        )
    }
}
