package dev.solon.bucklerhome

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var config by remember { mutableStateOf(DisplayPrefs.load(context)) }
    var editTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var changeCoverTarget by remember { mutableStateOf<ShelfItem?>(null) }
    var coverVersion by remember { mutableIntStateOf(0) }
    var updateDismissed by remember { mutableStateOf<GitHubRelease?>(null) }
    val availableUpdate by rememberAvailableUpdate(resumeTick)
    var shelvesLoaded by remember { mutableStateOf(false) }
    var initialFocusDone by remember { mutableStateOf(false) }
    var homeFocused by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val stageFocus = remember { FocusRequester() }
    val stageDown = remember { FocusRequester() }
    val move = remember { MoveMode() }
    val moveFocus = remember { FocusRequester() }
    var refocusTick by remember { mutableIntStateOf(0) }
    var focusPkg by remember { mutableStateOf<String?>(null) }
    var settleTick by remember { mutableIntStateOf(0) }
    val shelfMove = remember { MoveMode(vertical = true) }
    val shelfRequester = remember { FocusRequester() }
    var focusShelfId by remember { mutableStateOf<String?>(null) }
    var shelfSettleTick by remember { mutableIntStateOf(0) }
    var shelfMenu by remember { mutableStateOf<ShelfConfig?>(null) }
    var sourceShelf by remember { mutableStateOf<ShelfConfig?>(null) }
    var reading by remember { mutableStateOf<Headline?>(null) }
    var pickingStage by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val stage = homeLayout?.mainStage ?: MainStage.NEWS
    val trackPlayback = stage == MainStage.NOW_PLAYING || stage == MainStage.SUGGESTED || stage == MainStage.LYRICS
    val weather by rememberWeather(config.weatherPlace)
    val (headlines, stageHeadlines) = rememberHomeHeadlines(config, stage)
    val forecast by rememberForecast(if (stage == MainStage.WEATHER) config.stageWeatherPlace else null)
    val net by rememberNetState()
    val clockText by rememberClockText(config)
    val (clock, date) = clockText
    val nowPlaying = rememberNowPlaying(
        config.showsNowPlaying || trackPlayback,
        resumeTick,
    )

    LaunchedEffect(resumeTick, layoutVersion) {
        homeLayout = withContext(Dispatchers.IO) { HomeLayout.load(context) }
    }
    UnusedAppsNotice(resumeTick)
    RemoteButtonsNotice(resumeTick)
    val packageChanges = rememberPackageChanges()
    LaunchedEffect(resumeTick, packageChanges) {
        config = DisplayPrefs.load(context)
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
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
        shelvesLoaded = true
    }

    // Nothing on Home focused: the first tile, else the stage. Retried because the first tile
    // is laid out a frame or more after the shelves load, and a single early request silently
    // failed, leaving a cold start with no focus. Only acts while Home holds no focus at all.
    suspend fun focusHome(preferStage: Boolean = false) {
        repeat(FOCUS_TRIES) { attempt ->
            if (homeFocused) return
            val tiles = shelves.any { it.items.isNotEmpty() } && !preferStage && attempt < FOCUS_TRIES / 2
            runCatching { (if (tiles) firstFocus else stageFocus).requestFocus() }
            delay(FOCUS_RETRY_MS)
        }
    }

    // First tile once on start; later reloads (cover/pin edits) must not steal focus.
    LaunchedEffect(shelves, shelvesLoaded) {
        if (initialFocusDone || !shelvesLoaded) return@LaunchedEffect
        initialFocusDone = true
        focusHome()
    }
    // Back to Home from an app or Settings, or a stage swap that disposed the focused card.
    LaunchedEffect(resumeTick, homeLayout?.mainStage) {
        if (!initialFocusDone) return@LaunchedEffect
        delay(FOCUS_RETRY_MS * 3)
        focusHome(preferStage = true)
    }

    fun stepMove(delta: Int) {
        val pkg = move.target ?: return
        val current = homeLayout ?: return
        val next = current.withMovedPinned(pkg, delta)
        if (next == current) return
        homeLayout = next
        shelves = shelves.withAppsOrder(next.appsPackages())
    }

    fun placeMove() {
        homeLayout?.let { HomeLayout.save(context, it) }
        move.end(placedWithSelect = true)
        settleTick++
    }

    fun cancelMove() {
        move.original?.let { original ->
            homeLayout = original
            shelves = shelves.withAppsOrder(original.appsPackages())
        }
        move.end(placedWithSelect = false)
        settleTick++
    }

    fun applyShelfOrder(next: HomeLayout) {
        homeLayout = next
        val order = next.shelves.map { it.id }
        shelves = shelves.sortedBy { order.indexOf(it.config.id) }
    }

    fun stepShelf(delta: Int) {
        val id = shelfMove.target ?: return
        val current = homeLayout ?: return
        val next = current.withMovedShelf(id, delta, visibleIds = shelves.map { it.config.id }.toSet())
        if (next != current) applyShelfOrder(next)
    }

    fun placeShelf() {
        homeLayout?.let { HomeLayout.save(context, it) }
        shelfMove.end(placedWithSelect = true)
        shelfSettleTick++
    }

    fun cancelShelfMove() {
        shelfMove.original?.let { applyShelfOrder(it) }
        shelfMove.end(placedWithSelect = false)
        shelfSettleTick++
    }

    fun hideShelf(shelf: ShelfConfig) {
        val current = homeLayout ?: return
        val next = current.copy(shelves = current.shelves.map { if (it.id == shelf.id) it.copy(enabled = false) else it })
        HomeLayout.save(context, next)
        homeLayout = next
        refocusTick++
    }

    // Removing/pinning from the menu can delete the focused tile; hand focus back to the first one.
    LaunchedEffect(refocusTick) {
        if (refocusTick > 0) {
            delay(250)
            runCatching { firstFocus.requestFocus() }
        }
    }

    BackHandler(enabled = move.active) { cancelMove() }
    BackHandler(enabled = shelfMove.active) { cancelShelfMove() }
    LaunchedEffect(shelfMove.target) {
        val id = shelfMove.target
        if (id != null) {
            focusShelfId = id
            delay(150)
            runCatching { shelfRequester.requestFocus() }
        }
    }
    // Keep the moving shelf on screen (a shelf scrolled out of the list would drop focus).
    val movingShelfIndex = shelves.indexOfFirst { it.config.id == focusShelfId }
    LaunchedEffect(movingShelfIndex, focusShelfId) {
        if (movingShelfIndex >= 0) {
            val index = movingShelfIndex
            val info = listState.layoutInfo
            val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
            val fullyVisible = shown != null && shown.offset >= 0 && shown.offset + shown.size <= info.viewportEndOffset
            if (!fullyVisible) listState.animateScrollToItem(index)
        }
    }
    LaunchedEffect(shelfSettleTick) {
        if (shelfSettleTick > 0) {
            delay(400)
            runCatching { shelfRequester.requestFocus() }
            delay(300)
            focusShelfId = null
        }
    }
    LaunchedEffect(move.target) {
        if (move.active) {
            focusPkg = move.target
            // Let the app menu finish closing so it does not take focus back from the tile.
            delay(150)
            runCatching { moveFocus.requestFocus() }
        }
    }
    // After placing or cancelling, make sure the tile (which may have jumped back) still has focus.
    LaunchedEffect(settleTick) {
        if (settleTick > 0) {
            delay(350)
            runCatching { moveFocus.requestFocus() }
            delay(300)
            focusPkg = null
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

    val actions = rememberHeaderActions(onOpenEditHome)

    fun pickStage(mode: MainStage) {
        val next = layout.copy(mainStage = mode)
        HomeLayout.save(context, next)
        homeLayout = next
    }

    // Home options card: default shelves and header, keeping the apps pinned to the Apps shelf.
    fun resetHome() {
        val next = HomeLayout.default((homeLayout ?: HomeLayout.load(context)).appsPackages())
        HomeLayout.save(context, next)
        homeLayout = next
        DisplayPrefs.setHeaderVisible(context, true)
        DisplayPrefs.setHidden(context, emptySet())
        config = DisplayPrefs.load(context)
        refocusTick++
        Toast.makeText(context, "Home reset", Toast.LENGTH_SHORT).show()
    }

    fun pickSpan(span: WeatherSpan) {
        DisplayPrefs.setWeatherSpan(context, span)
        config = config.copy(weatherSpan = span)
    }

    Box(Modifier.fillMaxSize().onFocusChanged { homeFocused = it.hasFocus }) {
        HomeColumn(
            config = config,
            layout = layout,
            clock = clock,
            date = date,
            weather = weather,
            headlines = headlines,
            stageHeadlines = stageHeadlines,
            forecast = forecast,
            net = net,
            nowPlaying = nowPlaying,
            actions = actions,
            shelves = shelves,
            shelvesLoaded = shelvesLoaded,
            listState = listState,
            scope = scope,
            move = move,
            shelfMove = shelfMove,
            stageFocus = stageFocus,
            firstFocus = firstFocus,
            stageDown = stageDown,
            shelfRequester = shelfRequester,
            moveFocus = moveFocus,
            focusShelfId = focusShelfId,
            focusPkg = focusPkg,
            onLaunch = { launch(it) },
            onEdit = { editTarget = it },
            onShelfMenu = { shelfMenu = it },
            onOpenStory = { reading = it },
            onHoldStage = { pickingStage = true },
            onPickStage = ::pickStage,
            onPickSpan = ::pickSpan,
            onResetHome = ::resetHome,
            onStepMove = ::stepMove,
            onPlaceMove = ::placeMove,
            onStepShelf = ::stepShelf,
            onPlaceShelf = ::placeShelf,
        )
    }

    if (pickingStage) {
        StageSwitchDialog(
            current = layout.mainStage,
            span = config.weatherSpan,
            pinLabel = null,
            onPickStage = ::pickStage,
            onPickSpan = ::pickSpan,
            onPin = null,
            onDismiss = {
                pickingStage = false
                // A new stage replaces the target that held focus; without this it falls to the header.
                scope.launch {
                    delay(100)
                    runCatching { stageFocus.requestFocus() }
                }
            },
        )
    }

    HomeShelfDialogs(
        menu = shelfMenu,
        sources = sourceShelf,
        reading = reading,
        shelfCount = shelves.size,
        canHide = shelfMenu?.let { layout.canHideShelf(it.id) } ?: false,
        onMove = { menu ->
            shelfMenu = null
            shelfMove.begin(menu.id, layout)
        },
        onHide = { menu ->
            shelfMenu = null
            hideShelf(menu)
        },
        onSources = { menu ->
            shelfMenu = null
            sourceShelf = menu
        },
        onDismissMenu = { shelfMenu = null },
        onApplySources = { shelf, restrict, packages ->
            val next = layout.withWatchNextSources(shelf.id, restrict, packages)
            HomeLayout.save(context, next)
            homeLayout = next
            sourceShelf = null
        },
        onDismissSources = { sourceShelf = null },
        onDismissStory = { reading = null },
    )

    editTarget?.let { item ->
        HomeMenuHost(
            item = item,
            layout = layout,
            onLayoutChange = {
                homeLayout = it
                refocusTick++
            },
            onOpen = { launch(it) },
            onChangeCover = { changeCoverTarget = it },
            onMove = { pkg -> move.begin(pkg, layout) },
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

    availableUpdate?.takeIf { it != updateDismissed }?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDismiss = { updateDismissed = release },
        )
    }
}

private const val FOCUS_TRIES = 30
private const val FOCUS_RETRY_MS = 100L
