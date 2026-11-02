package dev.solon.bucklerhome

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The store's index, fetched again once per Settings visit (it is small); [refreshed] goes up when
 * a newer one arrived. What is installed is read again on each return to the front, for things
 * `buckler store install` put on the TV meanwhile.
 */
private object StoreSession {
    var fetched = false
    var refreshed by mutableIntStateOf(0)
}

@Composable
private fun rememberCatalog(): StoreCatalog {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    LaunchedEffect(resume) { withContext(Dispatchers.IO) { StoreLibrary.reload(context) } }
    LaunchedEffect(Unit) {
        if (StoreSession.fetched) return@LaunchedEffect
        StoreSession.fetched = true
        if (withContext(Dispatchers.IO) { StoreIndex.refresh(context) }) StoreSession.refreshed++
    }
    return remember(StoreSession.refreshed) { StoreIndex.cached(context) }
}

/** What is waiting for this TV ([StoreUpdates.pending]), worked out off the main thread, by item id. */
@Composable
private fun rememberUpdates(catalog: StoreCatalog): Map<String, StoreUpdate> {
    val context = LocalContext.current
    val updates by produceState(emptyMap<String, StoreUpdate>(), catalog, StoreLibrary.version) {
        value = withContext(Dispatchers.IO) { StoreUpdates.pending(context, catalog).associateBy { it.item.id } }
    }
    return updates
}

/** ", 1 updated" and the like for an overview count, from the updates among [ids]. */
private fun updatesNote(updates: Map<String, StoreUpdate>, ids: List<String>): String {
    val mine = ids.mapNotNull { updates[it] }
    val parts = listOfNotNull(
        mine.count { it.kind == StoreUpdate.Kind.UPDATED }.takeIf { it > 0 }?.let { "$it updated" },
        mine.count { it.kind == StoreUpdate.Kind.DOWNLOAD }.takeIf { it > 0 }?.let { "$it to update" },
        mine.count { it.kind == StoreUpdate.Kind.NEEDS_BUCKLER }.takeIf { it > 0 }?.let { "$it need a newer Buckler" },
    )
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(", ")
}

/** Under an item: what its update brought, or which Buckler it needs. Nothing when it has no news. */
@Composable
private fun UpdateLine(update: StoreUpdate?) {
    val u = update ?: return
    val what = u.item.changes.ifBlank { null }
    when (u.kind) {
        StoreUpdate.Kind.UPDATED -> SettingsRow("Updated" + (what?.let { ": $it" } ?: "."))
        StoreUpdate.Kind.DOWNLOAD -> SettingsRow("Update available" + (what?.let { ": $it" } ?: "."))
        StoreUpdate.Kind.NEEDS_BUCKLER -> {
            val builtIn = u.item as? StoreBuiltIn
            SettingsRow("Update Buckler to ${builtIn?.requires ?: "the newest version"} for the newest ${u.item.label}" + (what?.let { ": $it" } ?: "."))
            SettingsPaneLink("Updates", SettingsPane.UPDATES)
        }
    }
}

/** Settings → bucklerStore: widgets, screensavers, colour schemes, fonts and icon packs to add to Buckler. */
@Composable
fun StorePane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = rememberCatalog()
    val updates = rememberUpdates(catalog)
    SectionTitle("bucklerStore")
    SettingsRow(
        "Add widgets, screensavers, colour schemes, fonts and icon packs. Each download is checked against the store's " +
            "list before Buckler uses it. From a computer: buckler store install <name>.",
    )
    val widgets = catalog.widgets.count { w -> w.stage?.let { StoreLibrary.widgetAdded(it, catalog) } == true }
    SettingsPaneLink("Widgets", SettingsPane.STORE_WIDGETS, "$widgets of ${catalog.widgets.size} added" + updatesNote(updates, catalog.widgets.map { it.id }))
    val savers = catalog.screensavers.count { s -> s.mode?.let { StoreLibrary.saverAdded(it, catalog) } == true }
    SettingsPaneLink(
        "Screensavers",
        SettingsPane.STORE_SCREENSAVERS,
        "$savers of ${catalog.screensavers.size} added" + updatesNote(updates, catalog.screensavers.map { it.id }),
    )
    val schemes = catalog.schemes.count { s -> s.theme?.let { StoreLibrary.schemeAdded(it, catalog) } == true } +
        catalog.themes.count { StoreLibrary.hasTheme(it.id) }
    SettingsPaneLink(
        "Colour schemes",
        SettingsPane.STORE_THEMES,
        "$schemes of ${catalog.schemes.size + catalog.themes.size} added" +
            updatesNote(updates, catalog.schemes.map { it.id } + catalog.themes.map { it.id }),
    )
    SettingsPaneLink(
        "Fonts",
        SettingsPane.STORE_FONTS,
        installedOf(catalog.fonts.count { StoreLibrary.hasFont(it.id) }, catalog.fonts.size) + updatesNote(updates, catalog.fonts.map { it.id }),
    )
    SettingsPaneLink("Icon packs", SettingsPane.STORE_ICON_PACKS, "${catalog.iconPacks.size} open packs" + updatesNote(updates, catalog.iconPacks.map { it.id }))
    Spacer(modifier = Modifier.height(12.dp))
    SettingsAction("Check for new items") {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { StoreIndex.refresh(context) }
            if (ok) StoreSession.refreshed++
            Toast.makeText(context, if (ok) "bucklerStore is up to date" else "Couldn't reach bucklerStore", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun installedOf(installed: Int, total: Int) = if (installed == 0) "$total available" else "$installed of $total installed"

/** Settings → bucklerStore → Widgets: Buckler's own Main Stage widgets, each added to Home's choices or not. */
@Composable
fun StoreWidgetsPane() {
    val context = LocalContext.current
    val catalog = rememberCatalog()
    val inUse = remember(StoreLibrary.version) { StoreLibrary.inUse(HomeLayout.stored(context).mainStage, StagePrefs.sideIdle(context)) }
    val updates = rememberUpdates(catalog)
    SectionTitle("Widgets")
    SettingsRow(
        "Added widgets are offered for the Main Stage (hold OK on it, or Appearance → Main Stage); At a Glance and " +
            "Companion for the header's Side Stage too. News, Now Playing, Weather and Suggested come added.",
    )
    catalog.widgets.forEach { widget ->
        val stage = widget.stage
        if (stage == null) {
            SettingsRow("${widget.label}: needs Buckler ${widget.since} or newer. ${widget.about}")
            return@forEach
        }
        val on = StoreLibrary.widgetAdded(stage, catalog)
        SettingsCheck(widget.label, on) {
            if (on && stage.wire() in inUse) {
                Toast.makeText(context, "${widget.label} is on Home now: choose another widget there first", Toast.LENGTH_LONG).show()
            } else {
                StoreLibrary.setWidgetAdded(context, stage, !on)
            }
        }
        SettingsRow(widget.about)
        UpdateLine(updates[widget.id])
    }
}

/** Settings → bucklerStore → Screensavers: Buckler's own screensavers, each offered in Settings → Screensaver or not. */
@Composable
fun StoreScreensaversPane() {
    val context = LocalContext.current
    val catalog = rememberCatalog()
    val inUse = remember(StoreLibrary.version) { ScreensaverPrefs.load(context).mode }
    val updates = rememberUpdates(catalog)
    SectionTitle("Screensavers")
    SettingsRow(
        "Added screensavers are offered in Settings → Screensaver and the setup wizard, each with its own page. " +
            "The drifting clock, online photos, My photos and Now Playing come added.",
    )
    catalog.screensavers.forEach { saver ->
        val mode = saver.mode
        if (mode == null) {
            SettingsRow("${saver.label}: needs Buckler ${saver.since} or newer. ${saver.about}")
            return@forEach
        }
        val on = StoreLibrary.saverAdded(mode, catalog)
        SettingsCheck(saver.label, on) {
            if (on && mode == inUse) {
                Toast.makeText(context, "${saver.label} is the screensaver now: choose another first", Toast.LENGTH_LONG).show()
            } else {
                StoreLibrary.setSaverAdded(context, mode, !on)
            }
        }
        SettingsRow(saver.about)
        UpdateLine(updates[saver.id])
    }
}

/**
 * Settings → bucklerStore → Colour schemes: Buckler's own, added or removed with OK, then those to
 * download, each shown in its colours; OK opens a download's page.
 */
@Composable
fun StoreThemesPane() {
    val context = LocalContext.current
    val catalog = rememberCatalog()
    val nav = LocalPanelNav.current
    val inUse = AppearanceStore.config.storeTheme
    val builtInUse = AppearanceStore.config.theme
    val updates = rememberUpdates(catalog)
    SectionTitle("Colour schemes")
    SettingsRow("Added and installed schemes join Appearance → Colour scheme and the setup wizard. ✓ marks those added.")
    PickerTitle("Built in")
    SettingsRow("Buckler, Light, Dark and SHIELD come added; OK adds or removes one.")
    CardColumns { width ->
        catalog.schemes.forEach { scheme ->
            val theme = scheme.theme ?: return@forEach
            val on = StoreLibrary.schemeAdded(theme, catalog)
            PreviewCard(
                label = scheme.label + if (on) " ✓" else "",
                selected = theme == builtInUse && inUse == null,
                width = width,
                onClick = {
                    if (on && theme == builtInUse) {
                        Toast.makeText(context, "${scheme.label} is in use: choose another scheme first", Toast.LENGTH_LONG).show()
                    } else {
                        StoreLibrary.setSchemeAdded(context, theme, !on)
                    }
                },
            ) { ThemeMiniature(theme, AppearanceStore.config.highContrast) }
        }
    }
    catalog.schemes.filter { it.theme == null }.forEach { SettingsRow("${it.label}: needs Buckler ${it.since} or newer.") }
    // Built-in schemes with news, under the cards (a card has no room for a line).
    catalog.schemes.filter { it.id in updates }.forEach { scheme ->
        PickerTitle(scheme.label)
        UpdateLine(updates[scheme.id])
    }
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("To download")
    CardColumns { width ->
        catalog.themes.forEach { theme ->
            PreviewCard(
                label = theme.label + if (StoreLibrary.hasTheme(theme.id)) (if (theme.id in updates) " ↑" else " ✓") else "",
                selected = theme.id == inUse,
                width = width,
                onClick = { nav?.open(theme.label, theme.label) { StoreThemePage(theme) } },
            ) { PaletteMiniature(theme.palette) }
        }
    }
}

@Composable
private fun StoreThemePage(theme: StoreTheme) {
    val context = LocalContext.current
    // Looked up here, not handed in, so the page drops the update as soon as it is installed.
    val update = rememberUpdates(remember { StoreIndex.cached(context) })[theme.id]
    val installed = StoreLibrary.hasTheme(theme.id)
    val inUse = AppearanceStore.config.storeTheme == theme.id
    SettingsRow(theme.about)
    UpdateLine(update)
    // Saving the index's copy again is the whole update: a scheme is only its colours.
    if (installed && update != null) SettingsAction("Update") { StoreLibrary.installTheme(context, theme) }
    if (!installed) {
        SettingsAction("Install and use") {
            StoreLibrary.installTheme(context, theme)
            AppearanceStore.update(context) { it.copy(storeTheme = theme.id) }
        }
        SettingsAction("Install") { StoreLibrary.installTheme(context, theme) }
    } else {
        if (!inUse) SettingsAction("Use") { AppearanceStore.update(context) { it.copy(storeTheme = theme.id) } }
        if (inUse) SettingsRow("In use. Choosing a built-in scheme in Appearance puts this one aside.")
        SettingsAction("Remove") { StoreLibrary.removeTheme(context, theme.id) }
    }
}

/** Settings → bucklerStore → Fonts: Regular and Bold, about 120 KB a family. */
@Composable
fun StoreFontsPane() {
    val catalog = rememberCatalog()
    val nav = LocalPanelNav.current
    val inUse = AppearanceStore.config.storeFont
    val updates = rememberUpdates(catalog)
    SectionTitle("Fonts")
    SettingsRow("Open-licence fonts (SIL OFL). Installed fonts join Appearance → Font.")
    catalog.fonts.forEach { font ->
        val value = when {
            font.id in updates -> "Update"
            font.id == inUse -> "In use"
            StoreLibrary.hasFont(font.id) -> "Installed"
            else -> "${(font.regular.size + font.bold.size) / 1024} KB"
        }
        SettingsAction(font.label, value = "$value  ›") { nav?.open(font.label, font.label) { StoreFontPage(font) } }
    }
}

@Composable
private fun StoreFontPage(font: StoreFont) {
    val context = LocalContext.current
    val update = rememberUpdates(remember { StoreIndex.cached(context) })[font.id]
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val installed = StoreLibrary.hasFont(font.id)
    val inUse = AppearanceStore.config.storeFont == font.id
    SettingsRow(font.about)
    UpdateLine(update)
    if (installed && !busy && update != null) {
        // Downloaded again and checked against the index; installFont writes the new revision.
        SettingsAction("Update") {
            if (busyWithAnother(context)) return@SettingsAction
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { StoreLibrary.installFont(context, font) }
                busy = false
                if (!ok) Toast.makeText(context, "${font.label} didn't download, or didn't match the store's list", Toast.LENGTH_LONG).show()
            }
        }
    }
    if (installed && busy) {
        SettingsRow("Downloading…")
        return
    }
    if (installed) {
        StoreLibrary.fontFamily(font.id)?.let { family ->
            PreviewCard(label = font.label, selected = inUse, onClick = {}) { FontMiniature(family) }
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!inUse) SettingsAction("Use") { AppearanceStore.update(context) { it.copy(storeFont = font.id) } }
        SettingsAction("Remove") { StoreLibrary.removeFont(context, font.id) }
        return
    }
    if (busy) {
        SettingsRow("Downloading…")
        return
    }
    fun install(use: Boolean) {
        if (busyWithAnother(context)) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { StoreLibrary.installFont(context, font) }
            busy = false
            if (ok && use) AppearanceStore.update(context) { it.copy(storeFont = font.id) }
            if (!ok) Toast.makeText(context, "${font.label} didn't download, or didn't match the store's list", Toast.LENGTH_LONG).show()
        }
    }
    SettingsAction("Install and use") { install(use = true) }
    SettingsAction("Install") { install(use = false) }
}

/** Settings → bucklerStore → Icon packs: apps, so Android installs them (and asks first). */
@Composable
fun StoreIconPacksPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = rememberCatalog()
    val tick = rememberResumeTick()
    var busy by remember { mutableStateOf<String?>(null) }
    val installed = remember(tick, busy) { IconPacks.installedPackages(context) }
    val updates = rememberUpdates(catalog)
    SectionTitle("Icon packs")
    SettingsRow(
        "Open launcher icon packs. Android asks you to confirm the install (and, the first time, to let " +
            "Buckler install apps). Choose one in Appearance → Icons & artwork.",
    )
    catalog.iconPacks.forEach { pack ->
        val have = pack.packageName in installed
        val size = pack.apk.size.takeIf { it > 0 }?.let { " · about ${it / 1_000_000} MB" }.orEmpty()
        when {
            busy == pack.id -> SettingsAction(pack.label, value = "Downloading…") {}
            have && pack.id !in updates -> SettingsPaneLink(pack.label, SettingsPane.COVERS, "Installed")
            else -> SettingsAction(pack.label, value = if (have) "Update" else "Install") {
                if (busyWithAnother(context)) return@SettingsAction
                busy = pack.id
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { installIconPack(context, pack) }
                    busy = null
                    if (!ok) Toast.makeText(context, "${pack.label} didn't download, or didn't match the store's list", Toast.LENGTH_LONG).show()
                }
            }
        }
        SettingsRow("${pack.about} ${pack.license}$size.")
        UpdateLine(updates[pack.id])
    }
}

/** Download, check against the index's SHA-256, and hand to Android; false when any step fails. */
private fun installIconPack(context: Context, pack: StoreIconPack): Boolean = InstallGate.withGate(pack.label) {
    // In the folder the FileProvider shares, should Android's installer need to open it.
    val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
    val apk = File(dir, "${pack.id}.apk")
    StoreLibrary.download(pack.apk, apk) && ApkInstaller.installPackage(context, apk, pack.packageName)
}?.getOrDefault(false) ?: false

/** True (after saying so) when a Buckler update or another download is under way: start nothing now. */
private fun busyWithAnother(context: Context): Boolean {
    val other = InstallGate.holder ?: return false
    Toast.makeText(context, "Waiting for $other to finish first", Toast.LENGTH_LONG).show()
    return true
}
