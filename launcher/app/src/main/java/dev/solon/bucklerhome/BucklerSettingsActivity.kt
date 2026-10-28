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

/** Home first, then what is on screen top to bottom, then upkeep. */
private enum class SettingsPane(val label: String) {
    HOME("Home"),
    MAIN_STAGE("Main Stage"),
    DISPLAY("Header"),
    NEWS("News"),
    SCREENSAVER("Screensaver"),
    APPEARANCE("Appearance"),
    ACCESSIBILITY("Accessibility"),
    APPS("Apps"),
    COVERS("Covers"),
    UPDATES("Updates"),
    ABOUT("About"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BucklerSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var pane by remember { mutableStateOf(SettingsPane.HOME) }
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
                    SettingsNav(entry.label, pane == entry, navFocus.getValue(entry)) { pane = entry }
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
                        SettingsPane.HOME -> HomePane()
                        SettingsPane.MAIN_STAGE -> MainStagePane()
                        SettingsPane.DISPLAY -> DisplayPane()
                        SettingsPane.NEWS -> NewsPane()
                        SettingsPane.APPEARANCE -> AppearancePane()
                        SettingsPane.ACCESSIBILITY -> AccessibilityPane()
                        SettingsPane.SCREENSAVER -> ScreensaverPane()
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
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 16.sp,
        )
    }
}
