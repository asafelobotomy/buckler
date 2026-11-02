package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateAvailableDialog(
    release: GitHubRelease,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        DialogPanel(minWidth = 420.dp, maxWidth = 640.dp, modifier = Modifier.autoFocus()) {
            Column {
                Text(
                    text = "Update available",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = "${release.name} (${release.tag}) · you have ${BuildConfig.VERSION_NAME}",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                // Store items people use whose newest version needs this update, so the two say one thing.
                val brings = remember(release.tag) {
                    StoreLibrary.ensureLoaded(context)
                    val catalog = StoreIndex.cached(context)
                    StoreUpdates.broughtBy(StoreLibrary.addedBuiltIns(catalog), BuildConfig.VERSION_NAME, release.tag)
                }
                if (brings.isNotEmpty()) {
                    Text(
                        text = "Also brings the newest ${brings.joinToString(", ") { it.label }}" +
                            (brings.singleOrNull()?.changes?.ifBlank { null }?.let { ": $it" } ?: "."),
                        fontSize = 14.sp,
                        color = ColorText,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                status?.let {
                    Text(text = it, fontSize = 14.sp, color = ColorBrand, modifier = Modifier.padding(bottom = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsAction("Update now") {
                        if (busy) return@SettingsAction
                        val preferApi = !CoverAccounts.githubToken(context).isNullOrBlank()
                        val url = release.downloadUrl(preferApi = preferApi)
                        if (url.isNullOrBlank()) {
                            Toast.makeText(
                                context,
                                "No APK on this release — attach buckler-home*.apk",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@SettingsAction
                        }
                        // Installing restarts Buckler: let a store download or install under way finish first.
                        InstallGate.holder?.let { other ->
                            status = "Waiting for $other to finish first — try again in a moment"
                            return@SettingsAction
                        }
                        busy = true
                        status = "Downloading…"
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                InstallGate.withGate("the Buckler update") {
                                    val file = ApkInstaller.downloadApk(context, url, release.apkName ?: "buckler-home-update.apk")
                                        ?: return@withGate "Download failed"
                                    withContext(Dispatchers.Main) { status = "Installing…" }
                                    if (ApkInstaller.install(context, file)) "Install started — confirm if prompted" else "Install failed"
                                }
                            }
                            busy = false
                            status = outcome?.getOrNull() ?: "Waiting for ${InstallGate.holder ?: "another download"} to finish first"
                        }
                    }
                    SettingsAction("Snooze 7 days") {
                        UpdatePrefs.setSnoozeDays(context, 7)
                        onDismiss()
                    }
                    SettingsAction("Skip this version") {
                        UpdatePrefs.setIgnoredTag(context, release.tag)
                        onDismiss()
                    }
                }
            }
        }
    }
}
