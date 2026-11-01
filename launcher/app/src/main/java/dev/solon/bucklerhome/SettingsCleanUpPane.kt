package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Smaller caches than this are not worth a trip to an app's page. */
private const val CACHE_WORTH_BYTES = 5L * 1024 * 1024
private const val LARGEST_SHOWN = 15

/** A counter that goes up each time Settings comes back to the front (after an uninstall or an app page). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return tick
}

/** Android's page for [packageName], where Clear cache, Clear storage and Disable are. */
fun openAppInfo(context: Context, packageName: String) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    if (runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
        SystemScreen.APPS.open(context)
    }
}

/**
 * Settings → Apps & storage (Overview): how full the TV is, where the space goes, and Buckler's own
 * cache. The pages beside it go app by app: Unused apps, Cache, Largest apps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanUpPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = rememberResumeTick()
    var cleared by remember { mutableIntStateOf(0) }
    val (free, total) = remember(resume, cleared) { StorageUse.device() }
    val apps by produceState<List<AppStorage>?>(null, resume) {
        value = withContext(Dispatchers.IO) { StorageUse.scan(context) }
    }
    val ownCache by produceState(0L, resume, cleared) {
        value = withContext(Dispatchers.IO) { StorageUse.ownCacheBytes(context) }
    }
    val photos by produceState(0L, resume) {
        value = withContext(Dispatchers.IO) { ScreensaverImages.uploaded(context).sumOf { it.length() } }
    }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Apps & storage")
    SettingsRow("${StorageUse.size(context, free)} free of ${StorageUse.size(context, total)}.")
    val list = apps
    when {
        !UnusedApps.hasAccess(context) ->
            SettingsRow("Seeing what each app takes needs a one-time permission: ${SystemSettings.GRANT_COMMAND}")
        list == null -> SettingsRow("Adding up…")
        else -> SettingsRow(
            "Apps take ${StorageUse.size(context, list.sumOf { it.totalBytes })}, of which " +
                "${StorageUse.size(context, list.sumOf { it.cacheBytes })} is cache they can rebuild. Unused apps, " +
                "Cache and Largest apps go through them one by one.",
        )
    }
    SystemScreenLink("Android's storage settings", SystemScreen.STORAGE)
    SystemScreenLink("Android's apps", SystemScreen.APPS)

    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle("Buckler")
    SettingsRow(
        "Cache: ${StorageUse.size(context, ownCache)} of saved artwork, screensaver photos, headlines and " +
            "web replies. Clearing it is safe; Home fetches them again as it needs them.",
    )
    if (photos > 0) SettingsRow("My photos: ${StorageUse.size(context, photos)} (Screensaver → My photos clears them).")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Clear Buckler's cache") {
            scope.launch {
                withContext(Dispatchers.IO) { StorageUse.clearOwnCache(context) }
                cleared++
            }
        }
    }
}

/** Settings → Apps & storage → Cache: apps by cache size, each opening its page where Clear cache is. */
@Composable
fun CachePane() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var cleared by remember { mutableIntStateOf(0) }
    val apps by produceState<List<AppStorage>?>(null, resume, cleared) {
        value = withContext(Dispatchers.IO) {
            StorageUse.scan(context).filter { it.cacheBytes >= CACHE_WORTH_BYTES }.sortedByDescending { it.cacheBytes }
        }
    }

    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    SectionTitle("Cache")
    SettingsRow(
        "Files apps keep to start faster. Clearing a cache is safe: nothing you made or signed in to is lost. " +
            "Free cache space clears, over Network debugging, every cache Android lets go of: the part " +
            "over each app's share. Each app's page below links to Android's, which clears the rest of its own.",
    )
    SettingsAction(status ?: "Free cache space") {
        if (status?.endsWith("…") == true) return@SettingsAction
        status = "Clearing…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val free = { android.os.StatFs(context.filesDir.path).availableBytes }
                    val before = free()
                    AdbSelf.connect(context) { status = "Allow debugging on the TV's prompt…" }.use { it.shell("pm trim-caches 999G") }
                    cleared++
                    "Freed ${StorageUse.size(context, (free() - before).coerceAtLeast(0))}. Apps rebuild what they need."
                }.getOrElse { AdbSelf.explain(it, "Not cleared") }
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    AppStorageList(
        apps,
        empty = "No app holds more than ${StorageUse.size(context, CACHE_WORTH_BYTES)} of cache.",
        value = { StorageUse.size(context, it.cacheBytes) },
    ) { app ->
        "Cache ${StorageUse.size(context, app.cacheBytes)} · ${StorageUse.size(context, app.totalBytes)} in all"
    }
}

/** Settings → Apps & storage → Largest apps: the apps taking the most space, to uninstall or clear. */
@Composable
fun LargestAppsPane() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val apps by produceState<List<AppStorage>?>(null, resume) {
        value = withContext(Dispatchers.IO) {
            StorageUse.scan(context).filter { it.packageName != context.packageName }.take(LARGEST_SHOWN)
        }
    }

    SectionTitle("Largest apps")
    SettingsRow("The apps taking the most space: the app itself plus what it has saved, such as downloads and offline videos.")
    AppStorageList(apps, empty = "No apps to show.", value = { StorageUse.size(context, it.totalBytes) }) { app ->
        "${StorageUse.size(context, app.totalBytes)}: app ${StorageUse.size(context, app.appBytes)}, " +
            "saved data ${StorageUse.size(context, app.dataBytes)}"
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun AppStorageList(apps: List<AppStorage>?, empty: String, value: (AppStorage) -> String, detail: (AppStorage) -> String) {
    val context = LocalContext.current
    when {
        !UnusedApps.hasAccess(context) -> {
            SettingsRow("Reading app sizes needs a one-time permission: ${SystemSettings.GRANT_COMMAND}")
            return
        }
        apps == null -> SettingsRow("Adding up…")
        apps.isEmpty() -> SettingsRow(empty)
        else -> {
            Spacer(modifier = Modifier.height(10.dp))
            apps.forEach { app ->
                SettingsLink(app.label, value = value(app)) { AppPage(app.packageName, detail(app)) }
            }
        }
    }
}
