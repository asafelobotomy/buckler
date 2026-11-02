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

/** Settings → bucklerStore: widgets, colour schemes, fonts and icon packs to add to Buckler. */
@Composable
fun StorePane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = rememberCatalog()
    SectionTitle("bucklerStore")
    SettingsRow(
        "Add widgets, colour schemes, fonts and icon packs. Each download is checked against the store's " +
            "list before Buckler uses it. From a computer: buckler store install <name>.",
    )
    val widgets = catalog.widgets.count { w -> w.stage?.let { StoreLibrary.widgetAdded(it, catalog) } == true }
    SettingsPaneLink("Widgets", SettingsPane.STORE_WIDGETS, "$widgets of ${catalog.widgets.size} added")
    SettingsPaneLink("Colour schemes", SettingsPane.STORE_THEMES, installedOf(catalog.themes.count { StoreLibrary.hasTheme(it.id) }, catalog.themes.size))
    SettingsPaneLink("Fonts", SettingsPane.STORE_FONTS, installedOf(catalog.fonts.count { StoreLibrary.hasFont(it.id) }, catalog.fonts.size))
    SettingsPaneLink("Icon packs", SettingsPane.STORE_ICON_PACKS, "${catalog.iconPacks.size} open packs")
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
    }
}

/** Settings → bucklerStore → Colour schemes: each shown in its colours; OK opens its page. */
@Composable
fun StoreThemesPane() {
    val catalog = rememberCatalog()
    val nav = LocalPanelNav.current
    val inUse = AppearanceStore.config.storeTheme
    SectionTitle("Colour schemes")
    SettingsRow("Installed schemes join Appearance → Colour scheme. ● marks the one in use.")
    CardColumns { width ->
        catalog.themes.forEach { theme ->
            PreviewCard(
                label = theme.label + if (StoreLibrary.hasTheme(theme.id)) " ✓" else "",
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
    val installed = StoreLibrary.hasTheme(theme.id)
    val inUse = AppearanceStore.config.storeTheme == theme.id
    SettingsRow(theme.about)
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
    SectionTitle("Fonts")
    SettingsRow("Open-licence fonts (SIL OFL). Installed fonts join Appearance → Font.")
    catalog.fonts.forEach { font ->
        val value = when {
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
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val installed = StoreLibrary.hasFont(font.id)
    val inUse = AppearanceStore.config.storeFont == font.id
    SettingsRow(font.about)
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
    SectionTitle("Icon packs")
    SettingsRow(
        "Open launcher icon packs. Android asks you to confirm the install (and, the first time, to let " +
            "Buckler install apps). Choose one in Appearance → Icons & artwork.",
    )
    catalog.iconPacks.forEach { pack ->
        val have = pack.packageName in installed
        val size = pack.apk.size.takeIf { it > 0 }?.let { " · about ${it / 1_000_000} MB" }.orEmpty()
        when {
            have -> SettingsPaneLink(pack.label, SettingsPane.COVERS, "Installed")
            busy == pack.id -> SettingsAction(pack.label, value = "Downloading…") {}
            else -> SettingsAction(pack.label, value = "Install") {
                busy = pack.id
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { installIconPack(context, pack) }
                    busy = null
                    if (!ok) Toast.makeText(context, "${pack.label} didn't download, or didn't match the store's list", Toast.LENGTH_LONG).show()
                }
            }
        }
        SettingsRow("${pack.about} ${pack.license}$size.")
    }
}

/** Download, check against the index's SHA-256, and hand to Android; false when any step fails. */
private fun installIconPack(context: Context, pack: StoreIconPack): Boolean {
    // In the folder the FileProvider shares, should Android's installer need to open it.
    val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
    val apk = File(dir, "${pack.id}.apk")
    if (!StoreLibrary.download(pack.apk, apk)) return false
    return ApkInstaller.installPackage(context, apk, pack.packageName)
}
