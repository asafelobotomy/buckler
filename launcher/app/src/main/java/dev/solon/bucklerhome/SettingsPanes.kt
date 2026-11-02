package dev.solon.bucklerhome

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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

fun openEditHome(context: android.content.Context) {
    val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT_HOME, true)
    safeLaunch(context, intent, "Edit Home")
}

/** Settings → Updates: Buckler's own from GitHub, and SHIELD's system update. */
@Composable
fun UpdatesPane(
    accountTick: Int,
    updateResult: UpdateCheckResult?,
    onResult: (UpdateCheckResult) -> Unit,
    onAvailable: (GitHubRelease) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    key(accountTick) {
        // Read once per account change: the token lives in encrypted preferences.
        val linked = remember { !CoverAccounts.githubToken(context).isNullOrBlank() }
        SectionTitle("Updates")
        SettingsRow("Buckler ${BuildConfig.VERSION_NAME}, from GitHub.")
        if (!linked) SettingsRow("A GitHub token (optional, under Accounts) raises GitHub's hourly limit.")
        updateResult?.let { result ->
            val msg = when (result) {
                is UpdateCheckResult.Available -> "Update ${result.release.tag} available"
                UpdateCheckResult.UpToDate -> "Up to date"
                UpdateCheckResult.Snoozed -> "Snoozed"
                is UpdateCheckResult.Error -> "Error: ${result.message}"
            }
            SettingsRow(msg)
        }
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
        // Only while an update prompt is snoozed; otherwise there is nothing to clear.
        var snoozed by remember { mutableStateOf(UpdatePrefs.snoozeUntil(context) > System.currentTimeMillis()) }
        if (snoozed) {
            SettingsAction("Clear snooze") {
                UpdatePrefs.clearSnooze(context)
                snoozed = false
                Toast.makeText(context, "Snooze cleared", Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("System")
        SystemScreenLink("System update", SystemScreen.SYSTEM_UPDATE)
    }
}

/** Settings → Appearance → Icons & artwork: icon packs, the curated pack, custom thumbnails. */
@Composable
fun CoversPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClearCovers by remember { mutableStateOf(false) }
    SectionTitle("Icons & artwork")
    IconPackSection()
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Artwork")
    SettingsRow(
        "SteamGridDB: ${CoverAccounts.statusLabel(context, CoverProvider.STEAMGRIDDB)}. IGDB: " +
            if (CoverAccounts.isLinked(context, CoverProvider.IGDB)) "linked." else "not linked.",
    )
    SettingsPaneLink("Link artwork accounts", SettingsPane.ACCOUNTS)
    SettingsAction("Refresh curated pack") {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CuratedCovers.refresh(context)
            }
            Toast.makeText(
                context,
                if (ok) {
                    "Curated thumbnails updated"
                } else {
                    "Refresh failed — keeping current thumbnails"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // Only when there are custom covers to clear.
    var customCount by remember { mutableIntStateOf(CoverStore.all(context).size) }
    if (customCount > 0) {
        SettingsAction(
            if (confirmClearCovers) "Press again to clear $customCount" else "Clear custom thumbnails",
        ) {
            if (confirmClearCovers) {
                CoverStore.clearAll(context)
                confirmClearCovers = false
                customCount = 0
                Toast.makeText(context, "Custom thumbnails cleared", Toast.LENGTH_SHORT).show()
            } else {
                confirmClearCovers = true
            }
        }
    }
}

/**
 * Settings → Accounts: every service Buckler signs in to, in one place, and Android's own screen
 * for the TV's Google account (Buckler cannot see or change other apps' accounts).
 */
@Composable
fun AccountsPane(
    accountTick: Int,
    onConnectGithub: () -> Unit,
    onConnectSgdb: () -> Unit,
    onConnectIgdb: () -> Unit,
    onAccountChanged: () -> Unit,
) {
    val context = LocalContext.current
    SectionTitle("Accounts")
    SettingsRow("Sign-ins are kept encrypted on this TV and used only for the service named.")
    PickerTitle("Google & other apps")
    SettingsRow("The Google account (Play Store, calendar) and other apps' accounts are Android's.")
    SystemScreenLink("Add or sync an account", SystemScreen.ADD_ACCOUNT)
    key(accountTick) {
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("GitHub")
        val github = remember { !CoverAccounts.githubToken(context).isNullOrBlank() }
        SettingsRow(if (github) "Token linked: updates check with it." else "Optional token: raises GitHub's hourly limit for updates.")
        if (github) {
            SettingsAction("Disconnect GitHub") {
                CoverAccounts.setGithubToken(context, null)
                onAccountChanged()
            }
        } else {
            SettingsAction("Link GitHub token") { onConnectGithub() }
        }

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Artwork")
        SettingsRow("SteamGridDB: ${CoverAccounts.statusLabel(context, CoverProvider.STEAMGRIDDB)}")
        if (CoverAccounts.isLinked(context, CoverProvider.STEAMGRIDDB)) {
            SettingsAction("Disconnect SteamGridDB") {
                CoverAccounts.disconnect(context, CoverProvider.STEAMGRIDDB)
                onAccountChanged()
            }
        } else {
            SettingsAction("Connect SteamGridDB") { onConnectSgdb() }
        }
        val igdbLinked = CoverAccounts.isLinked(context, CoverProvider.IGDB)
        val igdbAvailable = igdbLinked || TwitchDeviceAuth.clientIdConfigured()
        SettingsRow(
            "IGDB: " + if (igdbAvailable) {
                CoverAccounts.statusLabel(context, CoverProvider.IGDB)
            } else {
                "not available in this build (no Twitch client ID)"
            },
        )
        if (igdbLinked) {
            SettingsAction("Disconnect IGDB") {
                CoverAccounts.disconnect(context, CoverProvider.IGDB)
                onAccountChanged()
            }
        } else if (igdbAvailable) {
            SettingsAction("Connect IGDB") { onConnectIgdb() }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Multiview")
    JellyfinSection {}
    Spacer(modifier = Modifier.height(16.dp))
    StremioSection {}
}

@Composable
fun AboutPane() {
    val context = LocalContext.current
    SectionTitle("About")
    SettingsRow("buckler-home ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    SettingsRow("GitHub: asafelobotomy/buckler")
    SettingsRow("Font \"Hyperlegible\": Atkinson Hyperlegible, Braille Institute (SIL Open Font License)")
    SettingsRow(
        "Fonts Inter, Montserrat, Poppins, Lato, Open Sans, Nunito, Raleway, Oswald, Playfair Display and " +
            "Merriweather: their authors, via Google Fonts (SIL Open Font License)",
    )
    Spacer(modifier = Modifier.height(18.dp))
    SystemScreenLink("About this TV", SystemScreen.ABOUT)
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
