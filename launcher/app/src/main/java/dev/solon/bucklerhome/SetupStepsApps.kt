package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Apps and icons: icon packs drawn in their own style, swaps as "stock app → open app" with their
 * icons, and suggestions with theirs. Each download is checked before it is installed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    val data = remember { ShieldData.load(context) }
    val installed = remember { data.apps.associate { it.packageName to SetupApps.installed(context, it.packageName) } }
    val packs = data.apps.filter { it.group == CatalogApp.ICON_PACK }
    val swaps = data.apps.filter { it.group == CatalogApp.SWAP }
    val suggestions = data.apps.filter { it.group == CatalogApp.SUGGESTION }
    fun chosen(app: CatalogApp) = app.id in choices.apps || (app.installable && installed[app.packageName] == true)
    val packLabel = packs.firstOrNull { it.packageName == choices.iconPack }?.label
        ?: if (choices.iconPack.isBlank()) "Own" else appName(context, choices.iconPack)
    var focus by remember { mutableStateOf("icons") }
    val items = listOf(
        SplitItem("icons", "App icons", packLabel),
        SplitItem("swaps", "Swaps", "${swaps.count(::chosen)} of ${swaps.size}"),
        SplitItem("suggestions", "Suggested apps", "${suggestions.count(::chosen)} of ${suggestions.size}"),
    )
    // Installed apps stay chosen: the wizard installs, it never uninstalls.
    fun toggle(app: CatalogApp) {
        if (app.installable && installed[app.packageName] == true) return
        onChange(choices.copy(apps = if (app.id in choices.apps) choices.apps - app.id else choices.apps + app.id))
    }
    SetupSplit(items, focus, { focus = it }) { key ->
        when (key) {
            "icons" -> {
                SetupNote(
                    "An icon pack redraws every app's icon in one style; apps it lacks are drawn to match. " +
                        "↓ downloads it. " + packs.joinToString(" ") { "${it.label}: ${it.note}" },
                )
                FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                    PreviewCard("Apps' own", choices.iconPack.isBlank(), onClick = { onChange(choices.copy(iconPack = "")) }) {
                        IconStyleMiniature(IconStyle.OWN)
                    }
                    packs.forEach { pack ->
                        val style = if (pack.id == "arcticons") IconStyle.LINES else IconStyle.OUTLINE
                        val label = if (installed[pack.packageName] == true) pack.label else "${pack.label} ↓"
                        PreviewCard(label, choices.iconPack == pack.packageName, onClick = { onChange(choices.copy(iconPack = pack.packageName)) }) {
                            IconStyleMiniature(style)
                        }
                    }
                    if (choices.iconPack.isNotBlank() && packs.none { it.packageName == choices.iconPack }) {
                        PreviewCard(appName(context, choices.iconPack), true, onClick = {}) { IconStyleMiniature(IconStyle.OTHER) }
                    }
                }
            }
            "swaps" -> {
                SetupNote("Nothing stock is removed: a swap takes its app's place in the Apps row. " + swaps.joinToString(" ") { it.note })
                FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                    swaps.forEach { app ->
                        val title = if (app.installable) app.label else "Unpin ${app.stockLabel}"
                        PreviewCard(title, chosen(app), width = 250.dp, onClick = { toggle(app) }) { SwapPicture(app) }
                    }
                }
            }
            "suggestions" -> {
                SetupNote("Optional. Each is downloaded from its maker and checked against a pinned checksum before installing.")
                FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                    suggestions.forEach { app ->
                        PreviewCard(app.label, chosen(app), width = 250.dp, onClick = { toggle(app) }) { AppPicture(app) }
                    }
                }
            }
        }
    }
}

/** "YouTube → SmartTube" with both icons; for Google TV, its icon crossed out. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SwapPicture(app: CatalogApp) {
    Row(
        Modifier.fillMaxSize().padding(10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.stockPackage, app.stockLabel, 44.dp, faded = !app.installable)
        Text(if (app.installable) "  →  " else "  ✕", color = ColorText, fontSize = 22.sp)
        if (app.installable) AppIcon(app.packageName, app.label, 44.dp)
    }
}

/** The app's icon and why it is suggested. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppPicture(app: CatalogApp) {
    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName, app.label, 44.dp)
        Text(
            app.note,
            color = ColorMuted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * Clean up the SHIELD: each group on the left with how many apps it has (OK switches the group),
 * its apps on the right with their icons. Advanced can also switch apps one by one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    val data = remember { ShieldData.load(context) }
    val present = remember {
        val kept = SetupState.keptOn(context)
        CleanGroup.entries.associateWith { SetupState.presentApps(context, it.packages(data), kept) }
    }
    val names = remember { present.values.flatten().associateWith { appName(context, it) } }
    val groups = CleanGroup.entries.filter { present.getValue(it).isNotEmpty() }
    var focus by remember { mutableStateOf(groups.firstOrNull()?.name ?: "") }
    val items = groups.map { g ->
        val apps = present.getValue(g)
        SplitItem(g.name, g.label, "${apps.count(choices.offApps::contains)}/${apps.size} off", on = apps.all(choices.offApps::contains))
    }
    SetupSplit(
        items = items,
        selected = focus,
        onSelect = { focus = it },
        onToggle = { key ->
            val g = CleanGroup.valueOf(key)
            onChange(choices.withGroup(g, !present.getValue(g).all(choices.offApps::contains), data))
        },
    ) { key ->
        val group = CleanGroup.entries.firstOrNull { it.name == key } ?: return@SetupSplit
        val apps = present.getValue(group)
        SetupNote(
            group.detail + if (choices.mode == SetupMode.ADVANCED) {
                " ● and faded: will be off. OK on an app switches it; on the group, all of them."
            } else {
                " Faded: will be off. OK on the group switches all of them."
            },
        )
        if (choices.mode == SetupMode.ADVANCED) {
            FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                apps.forEach { pkg ->
                    val off = pkg in choices.offApps
                    PreviewCard(names.getValue(pkg), off, width = 150.dp, onClick = {
                        val offApps = if (off) choices.offApps - pkg else choices.offApps + pkg
                        val on = CleanGroup.entries.filter { g -> present.getValue(g).let { a -> a.isNotEmpty() && a.all(offApps::contains) } }
                        onChange(choices.copy(offApps = offApps, groups = on.toSet()))
                    }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppIcon(pkg, names.getValue(pkg), 48.dp, faded = off) }
                    }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                apps.forEach { pkg -> AppChip(names.getValue(pkg), pkg, pkg in choices.offApps) }
            }
        }
    }
}

/** An app's icon over its name, faded when it will be turned off. Not focusable: Simple only shows them. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppChip(name: String, pkg: String, off: Boolean) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(pkg, name, 44.dp, faded = off)
        Text(
            text = name,
            color = if (off) ColorMuted else ColorText,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
