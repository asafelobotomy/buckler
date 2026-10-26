package dev.solon.bucklerhome

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTile(
    app: LaunchableApp,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val bmp = remember(app.packageName) {
        app.icon?.toBitmap(128, 128)?.asImageBitmap()
    }
    val menu = rememberTvMenuInteraction(onOpen = onLaunch, onMenu = onLongPress)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var tileModifier = modifier
        .width(132.dp)
        .scale(if (focused) 1.1f else 1f)
        .clip(RoundedCornerShape(8.dp))
        .background(if (focused) ColorBrand else ColorTile)
    if (focusRequester != null) {
        tileModifier = tileModifier.focusRequester(focusRequester)
    }
    tileModifier = tileModifier
        .focusable(interactionSource = interactionSource)
        .tvMenuKeys(menu)
        .pointerInput(app.packageName) {
            detectTapGestures(
                onLongPress = { menu.openMenu() },
                onTap = { menu.open() },
            )
        }

    Box(modifier = tileModifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = app.label,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Box(modifier = Modifier.size(64.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = app.label,
                fontSize = 13.sp,
                color = if (focused) ColorOnBrand else ColorText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppMenuDialog(
    item: ShelfItem,
    pinned: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canUninstall: Boolean,
    onOpen: () -> Unit,
    onChangeCover: () -> Unit,
    onRemove: () -> Unit,
    onUninstall: () -> Unit,
    onPin: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsAction("Open", focusRequester = firstFocus, onClick = onOpen)
                        SettingsAction("Change cover", onClick = onChangeCover)
                        SettingsAction("Info") { showInfo = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (pinned) {
                            SettingsAction("Remove", onClick = onRemove)
                            if (canMoveLeft) SettingsAction("Move left", onClick = onMoveLeft)
                            if (canMoveRight) SettingsAction("Move right", onClick = onMoveRight)
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
