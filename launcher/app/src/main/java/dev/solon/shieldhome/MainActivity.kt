package dev.solon.shieldhome

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: Drawable?,
    val isSystem: Boolean,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShieldHomeTheme {
                HomeScreen(
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShieldHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7EB6FF),
            onPrimary = Color(0xFF001B3D),
            surface = Color(0xFF12151C),
            onSurface = Color(0xFFE8ECF2),
            border = Color(0xFF3A4558),
        ),
        content = content,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { loadLaunchableApps(context.packageManager) }
    val dock = remember(apps) { preferredDock(apps) }
    val library = remember(apps) { apps.filterNot { it.isSystem } }
    var clock by remember { mutableStateOf(currentClock()) }

    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            delay(15_000)
        }
    }

    val bg = Brush.verticalGradient(
        colors = listOf(Color(0xFF0B0E14), Color(0xFF1A2230), Color(0xFF0B0E14)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 48.dp, vertical = 36.dp),
    ) {
        Text(
            text = "shieldhome",
            color = Color(0xFF7EB6FF),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = clock,
            color = Color(0xFFE8ECF2),
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "No ads · no mic · Chromecast kept",
            color = Color(0xFF9AA6B8),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        Text(
            text = "Dock",
            color = Color(0xFFB7C2D3),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        AppRow(apps = dock)

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Library",
            color = Color(0xFFB7C2D3),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        AppRow(apps = library)

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            onClick = onOpenSettings,
            modifier = Modifier.width(220.dp),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF243044),
                focusedContainerColor = Color(0xFF7EB6FF),
                contentColor = Color(0xFFE8ECF2),
                focusedContentColor = Color(0xFF001B3D),
            ),
        ) {
            Text(
                text = "Settings",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                fontSize = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppRow(apps: List<LaunchableApp>) {
    val context = LocalContext.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(end = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(apps, key = { it.packageName }) { app ->
            Surface(
                onClick = { context.startActivity(app.launchIntent) },
                modifier = Modifier.width(168.dp),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1C2433),
                    focusedContainerColor = Color(0xFF7EB6FF),
                    contentColor = Color(0xFFE8ECF2),
                    focusedContentColor = Color(0xFF001B3D),
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                ) {
                    val bmp = remember(app.packageName) {
                        app.icon?.toBitmap(96, 96)?.asImageBitmap()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = app.label,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = app.label,
                        fontSize = 15.sp,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun currentClock(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private val DOCK_PACKAGES = listOf(
    "org.jellyfin.androidtv",
    "com.livesubs.tv",
    "app.sonicsound",
    "org.videolan.vlc",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "ar.tvplayer.tv",
    "com.spotify.tv.android",
)

private fun preferredDock(apps: List<LaunchableApp>): List<LaunchableApp> {
    val byPkg = apps.associateBy { it.packageName }
    return DOCK_PACKAGES.mapNotNull { byPkg[it] }
}

private fun loadLaunchableApps(pm: PackageManager): List<LaunchableApp> {
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    val resolved = LinkedHashMap<String, ResolveInfo>()
    for (info in pm.queryIntentActivities(leanback, 0)) {
        resolved[info.activityInfo.packageName] = info
    }
    for (info in pm.queryIntentActivities(launcher, 0)) {
        resolved.putIfAbsent(info.activityInfo.packageName, info)
    }

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
                icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                isSystem = isSystem,
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** System packages users still expect in the library when present. */
private val USER_FACING_SYSTEM = setOf(
    "com.android.vending",
    "com.android.tv.settings",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom.nvidia",
    "com.google.android.apps.mediashell",
)

private const val OUR_PACKAGE = "dev.solon.shieldhome"
