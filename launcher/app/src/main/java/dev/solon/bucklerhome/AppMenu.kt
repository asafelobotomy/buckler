package dev.solon.bucklerhome

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppMenuDialog(
    item: ShelfItem,
    pinned: Boolean,
    canMove: Boolean,
    canUninstall: Boolean,
    onOpen: () -> Unit,
    onChangeCover: () -> Unit,
    onRemove: () -> Unit,
    onUninstall: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    if (showInfo) {
        AppInfoDialog(item = item, onDismiss = { showInfo = false })
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        // Non-clickable panel so child actions keep D-pad focus + highlight.
        Box(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 640.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorFooter)
                .swallowOrphanSelect()
                .padding(28.dp),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = item.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = item.packageName ?: item.id,
                    fontSize = 13.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                // One wrapping flow: every action stays on screen and reachable by D-pad,
                // however many of Remove / Move / Uninstall apply.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsAction("Open", focusRequester = firstFocus, onClick = onOpen)
                    SettingsAction("Change cover", onClick = onChangeCover)
                    SettingsAction("Info") { showInfo = true }
                    if (pinned) {
                        SettingsAction("Remove", onClick = onRemove)
                        if (canMove) SettingsAction("Move", onClick = onMove)
                    } else if (item.packageName != null) {
                        SettingsAction("Pin to Apps", onClick = onPin)
                    }
                    if (canUninstall) {
                        SettingsAction("Uninstall", onClick = onUninstall)
                    }
                    SettingsAction("Close", onClick = onDismiss)
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppInfoDialog(item: ShelfItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pkg = item.packageName
    val infoText = remember(pkg) {
        if (pkg == null) {
            "No package"
        } else {
            runCatching {
                val pm = context.packageManager
                val pi = pm.getPackageInfo(pkg, 0)
                val ai = pm.getApplicationInfo(pkg, 0)
                val updated = DateFormat.getDateTimeInstance().format(Date(pi.lastUpdateTime))
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                buildString {
                    appendLine("Package: $pkg")
                    appendLine("Version: ${pi.versionName} (${pi.longVersionCode})")
                    appendLine("Enabled: ${ai.enabled}")
                    appendLine("System: $system")
                    append("Updated: $updated")
                }
            }.getOrElse { "Could not read package info" }
        }
    }

    val infoFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorFooter)
                .padding(28.dp),
        ) {
            Column {
                Text(
                    text = item.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = infoText,
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pkg != null) {
                        SettingsAction("App info", focusRequester = infoFocus) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", pkg, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            safeLaunch(context, intent, "App info")
                        }
                    }
                    SettingsAction(
                        "Close",
                        focusRequester = if (pkg == null) infoFocus else null,
                        onClick = onDismiss,
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            runCatching { infoFocus.requestFocus() }
        }
    }
}

fun canUninstallPackage(context: android.content.Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank() || packageName == OUR_PACKAGE) return false
    return runCatching {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }.getOrDefault(false)
}

fun uninstallPackage(context: android.content.Context, packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    safeLaunch(context, intent, "Uninstall")
}
