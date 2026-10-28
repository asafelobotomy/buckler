package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
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
    launchFirstThatWorks(
        context,
        "app info",
        listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        ),
    )
}

/**
 * Settings → Clean up (Global): how full the TV is, where the space goes, and Buckler's own cache.
 * The pages beside it go app by app: Apps (unused), Cache, Largest apps.
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
    val photos = remember(resume) { ScreensaverImages.uploaded(context).sumOf { it.length() } }
    val flow = Arrangement.spacedBy(12.dp)

    SectionTitle("Clean up")
    SettingsRow("${StorageUse.size(context, free)} free of ${StorageUse.size(context, total)}.")
    val list = apps
    when {
        !UnusedApps.hasAccess(context) ->
            SettingsRow("Seeing what each app takes needs a one-time permission: ${SystemSettings.GRANT_COMMAND}")
        list == null -> SettingsRow("Adding up…")
        else -> SettingsRow(
            "Apps take ${StorageUse.size(context, list.sumOf { it.totalBytes })}, of which " +
                "${StorageUse.size(context, list.sumOf { it.cacheBytes })} is cache they can rebuild. Apps, Cache " +
                "and Largest apps beside this page go through them one by one.",
        )
    }
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Android's storage settings") {
            launchFirstThatWorks(
                context,
                "storage settings",
                listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
            )
        }
    }

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

/** Settings → Clean up → Cache: apps by cache size, each opening its page where Clear cache is. */
@Composable
fun CachePane() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val apps by produceState<List<AppStorage>?>(null, resume) {
        value = withContext(Dispatchers.IO) {
            StorageUse.scan(context).filter { it.cacheBytes >= CACHE_WORTH_BYTES }.sortedByDescending { it.cacheBytes }
        }
    }

    SectionTitle("Cache")
    SettingsRow(
        "Files apps keep to start faster. Clearing a cache is safe: nothing you made or signed in to is lost. " +
            "Android lets only its own app page clear another app's cache, or `buckler clean` from a " +
            "computer clears every app's at once.",
    )
    AppStorageList(apps, empty = "No app holds more than ${StorageUse.size(context, CACHE_WORTH_BYTES)} of cache.") { app ->
        "Cache ${StorageUse.size(context, app.cacheBytes)} · ${StorageUse.size(context, app.totalBytes)} in all"
    }
}

/** Settings → Clean up → Largest apps: the apps taking the most space, to uninstall or clear. */
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
    AppStorageList(apps, empty = "No apps to show.") { app ->
        "${StorageUse.size(context, app.totalBytes)}: app ${StorageUse.size(context, app.appBytes)}, " +
            "saved data ${StorageUse.size(context, app.dataBytes)}"
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun AppStorageList(apps: List<AppStorage>?, empty: String, detail: (AppStorage) -> String) {
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
                Column(modifier = Modifier.padding(bottom = 14.dp)) {
                    Text(text = app.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                    SettingsRow(detail(app))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsAction("App info (Clear cache)") { openAppInfo(context, app.packageName) }
                        if (canUninstallPackage(context, app.packageName)) {
                            SettingsAction("Uninstall") { uninstallPackage(context, app.packageName) }
                        }
                    }
                }
            }
        }
    }
}
