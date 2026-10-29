package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

const val OUR_PACKAGE = "dev.solon.bucklerhome"
const val PLAY_STORE_PACKAGE = "com.android.vending"

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: Drawable?,
    val isSystem: Boolean,
)

/** Default dock order when the user has not pinned anything yet. */
val DEFAULT_DOCK_PACKAGES: List<String> = listOf(
    "org.jellyfin.androidtv",
    "com.livesubs.tv",
    "app.sonicsound",
    "org.videolan.vlc",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.disney.disneyplus",
    "tv.twitch.android.app",
    "com.kick.mobile",
    "ar.tvplayer.tv",
    "com.spotify.tv.android",
    "com.google.android.videos",
)

/** System packages users still expect in the library when present. */
val USER_FACING_SYSTEM: Set<String> = setOf(
    PLAY_STORE_PACKAGE,
    "com.android.tv.settings",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom.nvidia",
    "com.google.android.apps.mediashell",
    "com.google.android.tv",
    "com.google.android.videos",
    "com.disney.disneyplus",
)

/**
 * Every app with a TV or phone launcher entry, with the chosen icon pack's icon where it has one.
 * [icons] false skips icons entirely, for screens that only list names (it is much cheaper).
 */
fun loadLaunchableApps(context: Context, icons: Boolean = true): List<LaunchableApp> {
    val pm = context.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    val resolved = LinkedHashMap<String, ResolveInfo>()
    for (info in pm.queryIntentActivities(leanback, 0)) {
        resolved[info.activityInfo.packageName] = info
    }
    for (info in pm.queryIntentActivities(launcher, 0)) {
        resolved.putIfAbsent(info.activityInfo.packageName, info)
    }
    // The pack's look is measured once, from its icons for these apps, before any app asks for it.
    if (icons && IconPacks.isActive(context)) IconPacks.style(context, resolved.keys)

    return resolved.values
        .mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == OUR_PACKAGE) return@mapNotNull null
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                ?: pm.getLaunchIntentForPackage(pkg)
                ?: return@mapNotNull null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val label = info.loadLabel(pm)?.toString() ?: pkg
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val isSystem = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                pkg !in USER_FACING_SYSTEM
            LaunchableApp(
                label = label,
                packageName = pkg,
                launchIntent = intent,
                icon = if (!icons) {
                    null
                } else {
                    appIcon(context, intent.component, label, resolved.keys) { runCatching { info.loadIcon(pm) }.getOrNull() }
                },
                isSystem = isSystem,
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun safeLaunch(context: Context, intent: Intent, label: String) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Could not open $label", Toast.LENGTH_SHORT).show()
    }
}

fun launchPackage(context: Context, packageName: String, label: String) {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
        ?: pm.getLaunchIntentForPackage(packageName)
        ?: run {
            Toast.makeText(context, "Could not open $label", Toast.LENGTH_SHORT).show()
            return
        }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    LaunchStats.recordLaunch(context, packageName)
    safeLaunch(context, intent, label)
}

/** Opens the installed weather app: an app named Weather, otherwise the first whose name says so. */
fun launchWeatherApp(context: Context) {
    // Names only: this runs on the OK press, where drawing every icon would stall Home.
    val match = loadLaunchableApps(context, icons = false)
        .filter { app ->
            val label = app.label.lowercase()
            "weather" in label || "weather" in app.packageName.lowercase()
        }
        .minWithOrNull(compareBy({ app -> weatherRank(app.label) }, { app -> app.label.lowercase() }))
    if (match == null) {
        Toast.makeText(context, "No weather app installed", Toast.LENGTH_SHORT).show()
        return
    }
    launchPackage(context, match.packageName, match.label)
}

private fun weatherRank(label: String): Int = when (label.lowercase()) {
    "weather" -> 0
    else -> if ("weather" in label.lowercase()) 1 else 2
}

fun playStoreLaunchIntent(pm: PackageManager): Intent? {
    return pm.getLeanbackLaunchIntentForPackage(PLAY_STORE_PACKAGE)
        ?: pm.getLaunchIntentForPackage(PLAY_STORE_PACKAGE)
}

/**
 * Goes up whenever an app is installed, updated or removed while Home is on screen, so Home
 * reloads its apps then (with their pack or drawn icons) rather than on its next return.
 */
@Composable
fun rememberPackageChanges(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) { tick++ }
            override fun onPackageRemoved(packageName: String, user: UserHandle) { tick++ }
            override fun onPackageChanged(packageName: String, user: UserHandle) { tick++ }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { tick++ }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { tick++ }
        }
        runCatching { launcher?.registerCallback(callback, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { launcher?.unregisterCallback(callback) } }
    }
    return tick
}
