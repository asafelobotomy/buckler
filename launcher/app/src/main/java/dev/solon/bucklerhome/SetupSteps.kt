package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The wizard's choice pages. Each shows [choices] and reports a changed copy through [onChange]. */

private val Gap = Arrangement.spacedBy(12.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    PickerTitle("Colour scheme")
    CardColumns(columns = 5) { width ->
        AppTheme.entries.forEach { theme ->
            PreviewCard(label = theme.label, selected = theme == choices.theme, width = width, onClick = {
                // Shown at once, so the scheme can be judged on the wizard itself.
                AppearanceStore.update(context) { it.copy(theme = theme) }
                onChange(choices.copy(theme = theme))
            }) { ThemeMiniature(theme, AppearanceStore.config.highContrast) }
        }
    }
    if (choices.mode == SetupMode.ADVANCED) {
        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Font")
        CardColumns(columns = 5) { width ->
            FontChoice.entries.forEach { font ->
                PreviewCard(label = font.label, selected = font == choices.font, width = width, onClick = {
                    AppearanceStore.update(context) { it.copy(font = font) }
                    onChange(choices.copy(font = font))
                }) { FontMiniature(font) }
            }
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    LayoutPicker(choices.layout, choices.tileSize) { onChange(choices.copy(layout = it)) }
    Spacer(modifier = Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Gap, verticalArrangement = Gap) {
        TileSize.entries.forEach { size ->
            SettingsAction("${size.label} apps", selected = size == choices.tileSize) { onChange(choices.copy(tileSize = size)) }
        }
    }
}

@Composable
fun ShelvesStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    SetupChoices.OPTIONAL_SHELVES.forEach { type ->
        val on = choices.shelves[type] == true
        ToggleRow(SetupApply.shelfName(type), on, shelfDetail(type)) { onChange(choices.copy(shelves = choices.shelves + (type to it))) }
    }
    if (SetupState.isShield()) {
        Spacer(modifier = Modifier.height(12.dp))
        ToggleRow(
            "Now Playing",
            choices.mediaAccess,
            "Show the song or video playing in any app, with its controls, in the header and on the Main Stage.",
        ) { onChange(choices.copy(mediaAccess = it)) }
    }
}

private fun shelfDetail(type: ShelfType): String = when (type) {
    ShelfType.WATCH_NEXT -> "What to carry on watching, from the apps that share it."
    ShelfType.SUGGESTED -> "Apps' own picks, as their channels offer them."
    ShelfType.RECENTS -> "The apps opened last."
    ShelfType.LIBRARY -> "Every app, in a grid below the shelves."
    ShelfType.APPS -> "Your pinned apps."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    FlowRow(horizontalArrangement = Gap, verticalArrangement = Gap) {
        ScreensaverMode.entries.forEach { mode ->
            SettingsAction(mode.label, selected = mode == choices.saver) { onChange(choices.copy(saver = mode)) }
        }
    }
    if (SetupState.isShield()) {
        Spacer(modifier = Modifier.height(16.dp))
        ToggleRow(
            "Use it as the TV's screensaver",
            choices.systemSaver,
            "Instead of Google's Backdrop, which is turned off (and back on if you switch this off).",
        ) { onChange(choices.copy(systemSaver = it)) }
    }
}

/** Icon packs and the open apps from the catalog: each download is checked before it is installed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    val data = remember { ShieldData.load(context) }
    val packs = data.apps.filter { it.group == CatalogApp.ICON_PACK }
    PickerTitle("App icons")
    SettingsRow("An icon pack redraws every app's icon in one style; apps it lacks are drawn to match.")
    FlowRow(horizontalArrangement = Gap, verticalArrangement = Gap) {
        SettingsAction("Apps' own icons", selected = choices.iconPack.isBlank()) { onChange(choices.copy(iconPack = "")) }
        packs.forEach { pack ->
            val label = if (SetupApps.installed(context, pack.packageName)) pack.label else "${pack.label} (download)"
            SettingsAction(label, selected = choices.iconPack == pack.packageName) { onChange(choices.copy(iconPack = pack.packageName)) }
        }
        if (choices.iconPack.isNotBlank() && packs.none { it.packageName == choices.iconPack }) {
            SettingsAction("Now: ${appName(context, choices.iconPack)}", selected = true) {}
        }
    }
    packs.forEach { SettingsRow("${it.label}: ${it.note}") }
    Spacer(modifier = Modifier.height(20.dp))
    PickerTitle("Open apps")
    SettingsRow("Optional. Nothing stock is removed: a swap only takes its app's place in the Apps row.")
    data.apps.filter { it.group != CatalogApp.ICON_PACK }.forEach { app ->
        val installed = app.installable && SetupApps.installed(context, app.packageName)
        val title = when {
            !app.installable -> "Unpin ${app.stockLabel} from the Apps row"
            app.group == CatalogApp.SWAP -> "${app.label} in place of ${app.stockLabel}" + if (installed) " (installed)" else ""
            else -> app.label + if (installed) " (installed)" else ""
        }
        // Installed apps stay chosen: the wizard installs, it never uninstalls.
        ToggleRow(title, app.id in choices.apps || installed, app.note) {
            if (!installed) onChange(choices.copy(apps = if (it) choices.apps + app.id else choices.apps - app.id))
        }
    }
}

/** Simple: three switches. Advanced: the same switches, each followed by its apps one by one. */
@Composable
fun CleanStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    val data = remember { ShieldData.load(context) }
    val present = remember {
        val kept = SetupState.keptOn(context)
        CleanGroup.entries.associateWith { SetupState.presentApps(context, it.packages(data), kept) }
    }
    CleanGroup.entries.forEach { group ->
        val apps = present.getValue(group)
        if (apps.isEmpty()) return@forEach
        val on = apps.all(choices.offApps::contains)
        ToggleRow("${group.label}: turn off ${apps.size} apps", on, group.detail) { onChange(choices.withGroup(group, it, data)) }
        if (choices.mode == SetupMode.ADVANCED) {
            apps.forEach { pkg ->
                val off = pkg in choices.offApps
                ToggleRow(appName(context, pkg), off, pkg, indent = true) {
                    val offApps = if (it) choices.offApps + pkg else choices.offApps - pkg
                    val groups = CleanGroup.entries.filter { g -> present.getValue(g).let { a -> a.isNotEmpty() && a.all(offApps::contains) } }
                    onChange(choices.copy(offApps = offApps, groups = groups.toSet()))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun appName(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString()
}.getOrDefault(pkg)

private val ANIMATIONS = listOf(1f to "Normal", 0.5f to "Fast", 0f to "Off")
private val DNS = listOf(
    DnsChoice.AUTOMATIC to "Automatic",
    DnsChoice.OFF to "Off",
    DnsChoice("hostname", "dns.adguard-dns.com") to "AdGuard (blocks ads)",
    DnsChoice("hostname", "one.one.one.one") to "Cloudflare",
    DnsChoice("hostname", "dns.quad9.net") to "Quad9 (blocks malware)",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PickerTitle("Animations")
    FlowRow(horizontalArrangement = Gap, verticalArrangement = Gap) {
        ANIMATIONS.forEach { (scale, label) ->
            SettingsAction(label, selected = choices.animation == scale) { onChange(choices.copy(animation = scale)) }
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    PickerTitle("Private DNS")
    SettingsRow("Looks up websites over an encrypted connection. A blocking host also stops ads or malware on every app.")
    FlowRow(horizontalArrangement = Gap, verticalArrangement = Gap) {
        DNS.forEach { (dns, label) ->
            SettingsAction(label, selected = choices.dns == dns) { onChange(choices.copy(dns = dns)) }
        }
        if (DNS.none { it.first == choices.dns }) SettingsAction("Now: ${SetupApply.dnsLabel(choices.dns)}", selected = true) {}
    }
}
