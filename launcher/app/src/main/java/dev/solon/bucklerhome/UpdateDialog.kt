package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
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
        Surface(
            onClick = {},
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ColorFooter,
                focusedContainerColor = ColorFooter,
                contentColor = ColorText,
                focusedContentColor = ColorText,
            ),
            modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text(
                    text = "Update available",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${release.name} (${release.tag}) · you have ${BuildConfig.VERSION_NAME}",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
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
                        busy = true
                        status = "Downloading…"
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ApkInstaller.downloadApk(
                                    context,
                                    url,
                                    release.apkName ?: "buckler-home-update.apk",
                                )
                            }
                            if (file == null) {
                                busy = false
                                status = "Download failed"
                                return@launch
                            }
                            status = "Installing…"
                            val ok = withContext(Dispatchers.IO) {
                                ApkInstaller.install(context, file)
                            }
                            busy = false
                            if (ok) {
                                status = "Install started — confirm if prompted"
                            } else {
                                status = "Install failed"
                            }
                        }
                    }
                    SettingsAction("Snooze 7 days") {
                        UpdatePrefs.setSnoozeDays(context, 7)
                        onDismiss()
                    }
                    SettingsAction("Dismiss") {
                        UpdatePrefs.setIgnoredTag(context, release.tag)
                        onDismiss()
                    }
                }
            }
        }
    }
}
