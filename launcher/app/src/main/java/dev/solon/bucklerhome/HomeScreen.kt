package dev.solon.bucklerhome

import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
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
    var homeLayout by remember(resumeTick, layoutVersion) {
        mutableStateOf(HomeLayout.load(context))
    }
    val apps = remember(resumeTick) { loadLaunchableApps(context.packageManager) }
    var shelves by remember { mutableStateOf<List<ResolvedShelf>>(emptyList()) }
    var clock by remember { mutableStateOf(currentClock()) }
    var date by remember { mutableStateOf(currentDate()) }
    var editTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var changeCoverTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var coverVersion by remember { mutableStateOf(0) }
    var updateRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    val firstFocus = remember { FocusRequester() }
    val brandMark: Painter = painterResource(R.drawable.ic_buckler_mark)

    LaunchedEffect(homeLayout, apps, resumeTick, layoutVersion, coverVersion) {
        shelves = withContext(Dispatchers.IO) {
            CuratedCovers.ensureCached(context)
            Shelves.resolve(context, homeLayout, apps).map { shelf ->
                shelf.copy(
                    items = shelf.items.map { CoverResolver.applyToItem(context, it, homeLayout.layout) },
                )
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

    LaunchedEffect(shelves) {
        if (shelves.any { it.items.isNotEmpty() }) {
            runCatching { firstFocus.requestFocus() }
        }
    }

    fun launch(item: ShelfItem) {
        val intent = item.launchIntent
        if (intent != null) {
            item.packageName?.let { LaunchStats.recordLaunch(context, it) }
            safeLaunch(context, intent, item.title)
        }
    }

    val hero = remember(shelves, homeLayout) { heroItem(shelves, homeLayout) }

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
            if (hero != null && homeLayout.layout == CoverLayout.BANNER) {
                item(key = "hero") {
                    HeroBanner(item = hero, onLaunch = { launch(hero) })
                }
            }
            items(shelves, key = { it.config.id }) { shelf ->
                ShelfSection(
                    shelf = shelf,
                    coverLayout = homeLayout.layout,
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
        val pkg = item.packageName
        val pinned = pkg != null && pkg in homeLayout.appsPackages()
        AppMenuDialog(
            item = item,
            pinned = pinned,
            canMoveLeft = pinned && pkg != null && homeLayout.appsPackages().indexOf(pkg) > 0,
            canMoveRight = pinned && pkg != null &&
                homeLayout.appsPackages().indexOf(pkg) < homeLayout.appsPackages().lastIndex,
            canUninstall = canUninstallPackage(context, pkg),
            onOpen = {
                launch(item)
                editTarget = null
            },
            onChangeCover = {
                changeCoverTarget = item
                editTarget = null
            },
            onRemove = {
                if (pkg != null) {
                    homeLayout = homeLayout.withAppsPackages(
                        homeLayout.appsPackages().filterNot { it == pkg },
                    )
                    HomeLayout.save(context, homeLayout)
                }
                editTarget = null
            },
            onUninstall = {
                if (pkg != null) uninstallPackage(context, pkg)
                editTarget = null
            },
            onPin = {
                if (pkg != null) {
                    val pins = homeLayout.appsPackages().toMutableList()
                    if (pkg !in pins) pins.add(pkg)
                    homeLayout = homeLayout.withAppsPackages(pins)
                    HomeLayout.save(context, homeLayout)
                }
                editTarget = null
            },
            onMoveLeft = {
                if (pkg != null) {
                    val pins = DockPrefs.moveLeft(context, pkg)
                    homeLayout = homeLayout.withAppsPackages(pins)
                    HomeLayout.save(context, homeLayout)
                }
                editTarget = null
            },
            onMoveRight = {
                if (pkg != null) {
                    val pins = DockPrefs.moveRight(context, pkg)
                    homeLayout = homeLayout.withAppsPackages(pins)
                    HomeLayout.save(context, homeLayout)
                }
                editTarget = null
            },
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
                layout = homeLayout.layout,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShelfSection(
    shelf: ResolvedShelf,
    coverLayout: CoverLayout,
    firstFocus: FocusRequester?,
    onLaunch: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    Column {
        Text(
            text = shelf.config.title,
            color = ColorSection,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (shelf.items.isEmpty()) {
            Text(
                text = when (shelf.config.type) {
                    ShelfType.WATCH_NEXT -> "Nothing in Up Next yet — apps publish here when you watch."
                    ShelfType.SUGGESTED -> "Launch a few apps to build suggestions."
                    else -> "No items"
                },
                color = ColorHint,
                fontSize = 14.sp,
            )
            return
        }
        if (shelf.config.type == ShelfType.LIBRARY) {
            val columns = 5
            val rows = shelf.items.chunked(columns)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { item ->
                            CoverTile(
                                item = item,
                                layout = coverLayout,
                                onLaunch = { onLaunch(item) },
                                onLongPress = { onLongPress(item) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                itemsIndexed(shelf.items, key = { _, item -> item.id }) { index, item ->
                    CoverTile(
                        item = item,
                        layout = coverLayout,
                        onLaunch = { onLaunch(item) },
                        onLongPress = { onLongPress(item) },
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FooterButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorFooter,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
        )
    }
}

private fun currentClock(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private fun currentDate(): String =
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM"))
