package dev.solon.bucklerhome

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BucklerSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BucklerHomeTheme {
                BucklerSettingsScreen(onClose = { finish() })
            }
        }
    }
}

private enum class SettingsPane {
    HOME,
    UPDATES,
    COVERS,
    DEVICE,
    ABOUT,
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BucklerSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var pane by remember { mutableStateOf(SettingsPane.HOME) }
    var connectIgdb by remember { mutableStateOf(false) }
    var connectSgdb by remember { mutableStateOf(false) }
    var connectGithub by remember { mutableStateOf(false) }
    var accountTick by remember { mutableStateOf(0) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var availableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(48.dp),
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(220.dp)) {
                SettingsNav("Home", pane == SettingsPane.HOME) { pane = SettingsPane.HOME }
                SettingsNav("Updates", pane == SettingsPane.UPDATES) { pane = SettingsPane.UPDATES }
                SettingsNav("Covers", pane == SettingsPane.COVERS) { pane = SettingsPane.COVERS }
                SettingsNav("Device", pane == SettingsPane.DEVICE) { pane = SettingsPane.DEVICE }
                SettingsNav("About", pane == SettingsPane.ABOUT) { pane = SettingsPane.ABOUT }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsAction("Close", onClick = onClose)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    when (pane) {
                        SettingsPane.HOME -> {
                            SectionTitle("Home")
                            SettingsRow("Edit Home opens from the HOME footer.")
                            SettingsAction("Android TV Settings") {
                                safeLaunch(context, Intent(Settings.ACTION_SETTINGS), "Settings")
                            }
                        }
                        SettingsPane.UPDATES -> {
                            key(accountTick) {
                            SectionTitle("Updates")
                            SettingsRow("Current: ${BuildConfig.VERSION_NAME}")
                            SettingsRow(
                                "GitHub token: " +
                                    if (CoverAccounts.githubToken(context).isNullOrBlank()) {
                                        "not set (needed for private-repo OTA)"
                                    } else {
                                        "linked"
                                    },
                            )
                            updateResult?.let { result ->
                                val msg = when (result) {
                                    is UpdateCheckResult.Available -> "Update ${result.release.tag} available"
                                    UpdateCheckResult.UpToDate -> "Up to date"
                                    UpdateCheckResult.Snoozed -> "Snoozed"
                                    is UpdateCheckResult.Error -> "Error: ${result.message}"
                                }
                                SettingsRow(msg)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsAction("Check now") {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            UpdateChecker.check(context, force = true)
                                        }
                                        updateResult = result
                                        if (result is UpdateCheckResult.Available) {
                                            availableRelease = result.release
                                        }
                                    }
                                }
                                SettingsAction("Clear snooze") {
                                    UpdatePrefs.clearSnooze(context)
                                    Toast.makeText(context, "Snooze cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (CoverAccounts.githubToken(context).isNullOrBlank()) {
                                    SettingsAction("Link GitHub token") { connectGithub = true }
                                } else {
                                    SettingsAction("Disconnect GitHub") {
                                        CoverAccounts.setGithubToken(context, null)
                                        accountTick++
                                    }
                                }
                            }
                            }
                        }
                        SettingsPane.COVERS -> {
                            key(accountTick) {
                                SectionTitle("Covers")
                                SettingsRow(
                                    "SteamGridDB: ${CoverAccounts.statusLabel(context, CoverProvider.STEAMGRIDDB)}",
                                )
                                SettingsRow(
                                    "IGDB: ${CoverAccounts.statusLabel(context, CoverProvider.IGDB)}",
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (CoverAccounts.isLinked(context, CoverProvider.STEAMGRIDDB)) {
                                        SettingsAction("Disconnect SteamGridDB") {
                                            CoverAccounts.disconnect(context, CoverProvider.STEAMGRIDDB)
                                            accountTick++
                                        }
                                    } else {
                                        SettingsAction("Connect SteamGridDB") { connectSgdb = true }
                                    }
                                    if (CoverAccounts.isLinked(context, CoverProvider.IGDB)) {
                                        SettingsAction("Disconnect IGDB") {
                                            CoverAccounts.disconnect(context, CoverProvider.IGDB)
                                            accountTick++
                                        }
                                } else {
                                    SettingsAction(
                                        if (TwitchDeviceAuth.clientIdConfigured()) {
                                            "Connect IGDB"
                                        } else {
                                            "Connect IGDB (set TWITCH_CLIENT_ID)"
                                        },
                                    ) { connectIgdb = true }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsAction("Refresh curated pack") {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            CuratedCovers.refresh(context)
                                        }
                                        Toast.makeText(
                                            context,
                                            if (ok) {
                                                "Curated covers ready (network or bundled)"
                                            } else {
                                                "Refresh failed — no network or bundled pack"
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                    SettingsAction("Clear custom covers") {
                                        CoverStore.clearAll(context)
                                        Toast.makeText(context, "Custom covers cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        SettingsPane.DEVICE -> {
                            SectionTitle("Device")
                            SettingsAction("Network") {
                                openSettingsAction(context, Settings.ACTION_WIFI_SETTINGS)
                            }
                            SettingsAction("Apps") {
                                openSettingsAction(context, Settings.ACTION_APPLICATION_SETTINGS)
                            }
                            SettingsAction("Display") {
                                openSettingsAction(context, Settings.ACTION_DISPLAY_SETTINGS)
                            }
                            SettingsAction("Device / About phone") {
                                openSettingsAction(context, Settings.ACTION_DEVICE_INFO_SETTINGS)
                            }
                            SettingsAction("Android TV Settings") {
                                safeLaunch(context, Intent(Settings.ACTION_SETTINGS), "Settings")
                            }
                        }
                        SettingsPane.ABOUT -> {
                            SectionTitle("About")
                            SettingsRow("buckler-home ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            SettingsRow("GitHub: asafelobotomy/buckler")
                            SettingsAction("Open releases") {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/asafelobotomy/buckler/releases"),
                                )
                                safeLaunch(context, intent, "Releases")
                            }
                        }
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
private fun SettingsNav(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontSize = 16.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = ColorText,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRow(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = ColorMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private fun openSettingsAction(context: android.content.Context, action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    safeLaunch(context, intent, action)
}
