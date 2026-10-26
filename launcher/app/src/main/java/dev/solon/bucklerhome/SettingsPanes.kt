package dev.solon.bucklerhome

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomePane() {
    val context = LocalContext.current
    SectionTitle("Home")
    SettingsRow("Edit Home is the pencil icon in the Home header; Settings is the gear.")
    SettingsAction("Android TV Settings") {
        safeLaunch(context, Intent(Settings.ACTION_SETTINGS), "Settings")
    }
}

@Composable
fun UpdatesPane(
    accountTick: Int,
    updateResult: UpdateCheckResult?,
    onResult: (UpdateCheckResult) -> Unit,
    onAvailable: (GitHubRelease) -> Unit,
    onConnectGithub: () -> Unit,
    onAccountChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                onResult(result)
                if (result is UpdateCheckResult.Available) {
                    onAvailable(result.release)
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
            SettingsAction("Link GitHub token") { onConnectGithub() }
        } else {
            SettingsAction("Disconnect GitHub") {
                CoverAccounts.setGithubToken(context, null)
                onAccountChanged()
            }
        }
    }
    }
}

@Composable
fun CoversPane(
    accountTick: Int,
    onConnectSgdb: () -> Unit,
    onConnectIgdb: () -> Unit,
    onAccountChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClearCovers by remember { mutableStateOf(false) }
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
                    onAccountChanged()
                }
            } else {
                SettingsAction("Connect SteamGridDB") { onConnectSgdb() }
            }
            if (CoverAccounts.isLinked(context, CoverProvider.IGDB)) {
                SettingsAction("Disconnect IGDB") {
                    CoverAccounts.disconnect(context, CoverProvider.IGDB)
                    onAccountChanged()
                }
            } else {
                SettingsAction(
                    if (TwitchDeviceAuth.clientIdConfigured()) {
                        "Connect IGDB"
                    } else {
                        "Connect IGDB (set TWITCH_CLIENT_ID)"
                    },
                ) { onConnectIgdb() }
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
                            "Curated covers updated"
                        } else {
                            "Refresh failed — keeping current covers"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            SettingsAction(
                if (confirmClearCovers) "Press again to confirm" else "Clear custom covers",
            ) {
                if (confirmClearCovers) {
                    CoverStore.clearAll(context)
                    confirmClearCovers = false
                    Toast.makeText(context, "Custom covers cleared", Toast.LENGTH_SHORT).show()
                } else {
                    confirmClearCovers = true
                }
            }
        }
    }
}

@Composable
fun DevicePane() {
    val context = LocalContext.current
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

@Composable
fun AboutPane() {
    val context = LocalContext.current
    SectionTitle("About")
    SettingsRow("buckler-home ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    SettingsRow("GitHub: asafelobotomy/buckler")
    SettingsRow("Font \"Hyperlegible\": Atkinson Hyperlegible, Braille Institute (SIL Open Font License)")
    SettingsAction("Open releases") {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/asafelobotomy/buckler/releases"),
        )
        safeLaunch(context, intent, "Releases")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionTitle(text: String) {
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
fun SettingsRow(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = ColorMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

fun openSettingsAction(context: android.content.Context, action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    safeLaunch(context, intent, action)
}
