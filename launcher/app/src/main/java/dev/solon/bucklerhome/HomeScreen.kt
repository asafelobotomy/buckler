package dev.solon.bucklerhome

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeTick: Int,
    layoutVersion: Int,
    onOpenEditHome: () -> Unit,
) {
    val context = LocalContext.current
    // Layout + app list load off the main thread; keep showing the previous values while reloading.
    var homeLayout by remember { mutableStateOf<HomeLayout?>(null) }
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var shelves by remember { mutableStateOf<List<ResolvedShelf>>(emptyList()) }
    var clock by remember { mutableStateOf(currentClock()) }
    var date by remember { mutableStateOf(currentDate()) }
    var editTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var changeCoverTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var coverVersion by remember { mutableStateOf(0) }
    var updateRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val brandMark: Painter = painterResource(R.drawable.ic_buckler_mark)

    LaunchedEffect(resumeTick, layoutVersion) {
        homeLayout = withContext(Dispatchers.IO) { HomeLayout.load(context) }
    }
    LaunchedEffect(resumeTick) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context.packageManager) }
    }

    val layout = homeLayout
    val appList = apps
    LaunchedEffect(layout, appList, coverVersion) {
        if (layout == null || appList == null) return@LaunchedEffect
        shelves = withContext(Dispatchers.IO) {
            CuratedCovers.ensureCached(context)
            Shelves.resolve(context, layout, appList).map { shelf ->
                shelf.copy(items = CoverResolver.applyAll(context, shelf.items, layout.layout))
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            date = currentDate()
            delay(15_000)
        }
    }

    LaunchedEffect(resumeTick) {
        if (!UpdateChecker.shouldAutoCheck(context)) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            UpdateChecker.check(context, force = false)
        }
        if (result is UpdateCheckResult.Available) {
            updateRelease = result.release
        }
    }

    // Focus the first tile once; later reloads (cover/pin edits) must not steal focus.
    LaunchedEffect(shelves) {
        if (!initialFocusDone && shelves.any { it.items.isNotEmpty() }) {
            initialFocusDone = true
            runCatching { firstFocus.requestFocus() }
        }
    }

    if (layout == null) {
        Box(modifier = Modifier.fillMaxSize().background(HomeBgBrush))
        return
    }

    fun launch(item: ShelfItem) {
        val intent = item.launchIntent
        if (intent != null) {
            item.packageName?.let { LaunchStats.recordLaunch(context, it) }
            safeLaunch(context, intent, item.title)
        }
    }

    val hero = remember(shelves, layout) { heroItem(shelves, layout) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = brandMark,
                    contentDescription = "Buckler",
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 12.dp),
                )
                Column {
                    Text(
                        text = "buckler-home",
                        color = ColorBrand,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = clock,
                        color = ColorText,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(text = date, color = ColorMuted, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (hero != null && layout.layout == CoverLayout.BANNER) {
                item(key = "hero") {
                    HeroBanner(item = hero, onLaunch = { launch(hero) })
                }
            }
            items(shelves, key = { it.config.id }) { shelf ->
                ShelfSection(
                    shelf = shelf,
                    coverLayout = layout.layout,
                    firstFocus = if (shelf == shelves.firstOrNull { it.items.isNotEmpty() }) {
                        firstFocus
                    } else {
                        null
                    },
                    onLaunch = { launch(it) },
                    onLongPress = { editTarget = it },
                )
            }
            item(key = "footer") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                ) {
                    FooterButton("Settings") {
                        safeLaunch(
                            context,
                            Intent(context, BucklerSettingsActivity::class.java),
                            "Buckler Settings",
                        )
                    }
                    playStoreLaunchIntent(context.packageManager)?.let { play ->
                        FooterButton("Play Store") {
                            safeLaunch(context, play, "Play Store")
                        }
                    }
                    FooterButton("Edit Home", onOpenEditHome)
                }
            }
        }
    }

    editTarget?.let { item ->
        HomeMenuHost(
            item = item,
            layout = layout,
            onLayoutChange = { homeLayout = it },
            onOpen = { launch(it) },
            onChangeCover = { changeCoverTarget = it },
            onDismiss = { editTarget = null },
        )
    }

    changeCoverTarget?.let { item ->
        val pkg = item.packageName
        if (pkg == null) {
            changeCoverTarget = null
        } else {
            ChangeCoverDialog(
                packageName = pkg,
                title = item.title,
                icon = item.icon,
                layout = layout.layout,
                onDismiss = { changeCoverTarget = null },
                onPicked = {
                    changeCoverTarget = null
                    coverVersion++
                },
            )
        }
    }

    updateRelease?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDismiss = { updateRelease = null },
        )
    }
}

private fun currentClock(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private fun currentDate(): String =
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM"))
