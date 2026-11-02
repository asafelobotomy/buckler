package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Buckler Settings: a panel that slides in from the right over whatever is on screen, like
 * SHIELD's own settings ([BucklerSettingsPanel]). It has its own task, so it opens over an app as
 * well as over Home, from the gear, a remapped button, or SHIELD's ≡ set to start Buckler.
 */
class BucklerSettingsActivity : ComponentActivity() {
    private var start by mutableStateOf<SettingsPane?>(null)
    private var opened by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppearanceStore.ensureLoaded(this)
        start = paneOf(intent)
        setContent {
            BucklerHomeTheme {
                BucklerSettingsPanel(start = start, opened = opened, onClose = { finish() })
            }
        }
    }

    /** Opened again while showing: at a page when one is asked for, otherwise it closes, as SHIELD's does. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pane = paneOf(intent)
        if (pane == null) {
            finish()
        } else {
            start = pane
            opened++
        }
    }

    private fun paneOf(intent: Intent?): SettingsPane? =
        intent?.getStringExtra(EXTRA_PANE)?.let { raw -> SettingsPane.entries.firstOrNull { it.name == raw } }

    companion object {
        /** A [SettingsPane] name to open at, for "More in Settings" on Home's hold menus. */
        const val EXTRA_PANE = "dev.solon.bucklerhome.PANE"

        fun open(context: Context, pane: SettingsPane) = safeLaunch(
            context,
            Intent(context, BucklerSettingsActivity::class.java).putExtra(EXTRA_PANE, pane.name),
            "Buckler Settings",
        )
    }
}

/**
 * One list for Buckler and the TV together, by topic: Home's look first (Appearance with a page for
 * each part of Home, Widgets with a page for each widget wherever it shows, Screensaver with a page
 * for each screensaver ([saver])), then the TV's, each page holding Buckler's rows and Android's
 * side by side, then Accounts, Updates and About. An entry with a [parent] is not in
 * the panel's first list: choosing the parent opens a list of its own, with the parent's own page
 * first as [ownPage] ("Global", or "Overview" for a summary) and then its children.
 */
enum class SettingsPane(
    val label: String,
    val parent: SettingsPane? = null,
    val saver: ScreensaverMode? = null,
    val ownPage: String = "Global",
) {
    APPEARANCE("Appearance"),
    HEADER("Header", APPEARANCE),
    MAIN_STAGE("Main Stage", APPEARANCE),
    SHELVES("Shelves", APPEARANCE),
    COVERS("Icons & artwork", APPEARANCE),
    WIDGETS("Widgets"),
    WEATHER("Weather", WIDGETS),
    NEWS("News", WIDGETS),
    CALENDAR("Calendar & reminders", WIDGETS),
    NOW_PLAYING("Now Playing & Lyrics", WIDGETS),
    MULTIVIEW("Multiview", WIDGETS),
    COMPANION("Companion", WIDGETS),
    STORE("bucklerStore", ownPage = "Overview"),
    STORE_WIDGETS("Widgets", STORE),
    STORE_THEMES("Colour schemes", STORE),
    STORE_FONTS("Fonts", STORE),
    STORE_ICON_PACKS("Icon packs", STORE),
    SCREENSAVER("Screensaver"),
    SAVER_CLOCK(ScreensaverMode.CLOCK.label, SCREENSAVER, ScreensaverMode.CLOCK),
    SAVER_DVD(ScreensaverMode.DVD.label, SCREENSAVER, ScreensaverMode.DVD),
    SAVER_PHOTOS(ScreensaverMode.PHOTOS.label, SCREENSAVER, ScreensaverMode.PHOTOS),
    SAVER_UPLOADED(ScreensaverMode.UPLOADED.label, SCREENSAVER, ScreensaverMode.UPLOADED),
    SAVER_NOW_PLAYING(ScreensaverMode.NOW_PLAYING.label, SCREENSAVER, ScreensaverMode.NOW_PLAYING),
    SAVER_MAZE(ScreensaverMode.MAZE.label, SCREENSAVER, ScreensaverMode.MAZE),
    SAVER_PIPES(ScreensaverMode.PIPES.label, SCREENSAVER, ScreensaverMode.PIPES),
    SAVER_MYSTIFY(ScreensaverMode.MYSTIFY.label, SCREENSAVER, ScreensaverMode.MYSTIFY),
    SAVER_STARFIELD(ScreensaverMode.STARFIELD.label, SCREENSAVER, ScreensaverMode.STARFIELD),
    DISPLAY_SOUND("Display & sound"),
    POWER("Power"),
    REMOTE("Remote & accessories"),
    NETWORK("Network"),
    ACCESSIBILITY("Accessibility"),
    STORAGE("Apps & storage", ownPage = "Overview"),
    UNUSED_APPS("Unused apps", STORAGE),
    CACHE("Cache", STORAGE),
    LARGEST_APPS("Largest apps", STORAGE),
    DEVICE("Device"),
    SHIELD_CLEAN_UP("SHIELD clean-up", DEVICE),
    ACCOUNTS("Accounts"),
    UPDATES("Updates"),
    ABOUT("About"),
    ;

    companion object {
        /** The page with [stage]'s own options, for "More in Settings" and the Main Stage page. */
        fun forStage(stage: MainStage): SettingsPane = when (stage) {
            MainStage.NEWS -> NEWS
            MainStage.WEATHER, MainStage.WEATHER_MAP -> WEATHER
            MainStage.NOW_PLAYING, MainStage.LYRICS, MainStage.SUGGESTED -> NOW_PLAYING
            MainStage.GLANCE, MainStage.CALENDAR -> CALENDAR
            MainStage.MULTIVIEW -> MULTIVIEW
            MainStage.COMPANION -> COMPANION
        }
    }
}

/** One Settings page's content, with the dialogs its account links open. */
@Composable
fun SettingsPageContent(pane: SettingsPane) {
    val context = LocalContext.current
    var connectIgdb by remember { mutableStateOf(false) }
    var connectSgdb by remember { mutableStateOf(false) }
    var connectGithub by remember { mutableStateOf(false) }
    var accountTick by remember { mutableIntStateOf(0) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var availableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    when (pane) {
        SettingsPane.APPEARANCE -> AppearancePane()
        SettingsPane.HEADER -> HeaderPane()
        SettingsPane.MAIN_STAGE -> MainStagePane()
        SettingsPane.SHELVES -> ShelvesPane()
        SettingsPane.COVERS -> CoversPane()
        SettingsPane.WIDGETS -> WidgetsPane()
        SettingsPane.WEATHER -> WeatherPane()
        SettingsPane.NEWS -> NewsPane()
        SettingsPane.CALENDAR -> CalendarSection()
        SettingsPane.NOW_PLAYING -> NowPlayingPane()
        SettingsPane.MULTIVIEW -> MultiviewSettings()
        SettingsPane.COMPANION -> CompanionPane()
        SettingsPane.STORE -> StorePane()
        SettingsPane.STORE_WIDGETS -> StoreWidgetsPane()
        SettingsPane.STORE_THEMES -> StoreThemesPane()
        SettingsPane.STORE_FONTS -> StoreFontsPane()
        SettingsPane.STORE_ICON_PACKS -> StoreIconPacksPane()
        SettingsPane.ACCESSIBILITY -> AccessibilityPane()
        SettingsPane.SCREENSAVER -> ScreensaverPane()
        SettingsPane.SAVER_CLOCK, SettingsPane.SAVER_DVD, SettingsPane.SAVER_PHOTOS,
        SettingsPane.SAVER_UPLOADED, SettingsPane.SAVER_NOW_PLAYING, SettingsPane.SAVER_MAZE,
        SettingsPane.SAVER_PIPES, SettingsPane.SAVER_MYSTIFY, SettingsPane.SAVER_STARFIELD,
        -> ScreensaverModePane(pane.saver ?: ScreensaverMode.CLOCK)
        SettingsPane.REMOTE -> RemotePane()
        SettingsPane.NETWORK -> NetworkPane()
        SettingsPane.DISPLAY_SOUND -> DisplaySoundPane()
        SettingsPane.POWER -> PowerPane()
        SettingsPane.STORAGE -> CleanUpPane()
        SettingsPane.UNUSED_APPS -> AppsPane()
        SettingsPane.CACHE -> CachePane()
        SettingsPane.LARGEST_APPS -> LargestAppsPane()
        SettingsPane.DEVICE -> ShieldPane()
        SettingsPane.SHIELD_CLEAN_UP -> ShieldCleanUpPane()
        SettingsPane.ACCOUNTS -> AccountsPane(
            accountTick = accountTick,
            onConnectGithub = { connectGithub = true },
            onConnectSgdb = { connectSgdb = true },
            onConnectIgdb = { connectIgdb = true },
            onAccountChanged = { accountTick++ },
        )
        SettingsPane.UPDATES -> UpdatesPane(
            accountTick = accountTick,
            updateResult = updateResult,
            onResult = { updateResult = it },
            onAvailable = { availableRelease = it },
        )
        SettingsPane.ABOUT -> AboutPane()
    }

    if (connectIgdb) {
        ConnectIgdbDialog(
            onDismiss = { connectIgdb = false },
            onLinked = {
                connectIgdb = false
                accountTick++
                Toast.makeText(context, "IGDB linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (connectSgdb) {
        ConnectSgdbDialog(
            onDismiss = { connectSgdb = false },
            onLinked = {
                connectSgdb = false
                accountTick++
                Toast.makeText(context, "SteamGridDB linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (connectGithub) {
        ConnectGithubDialog(
            onDismiss = { connectGithub = false },
            onLinked = {
                connectGithub = false
                accountTick++
                Toast.makeText(context, "GitHub token linked", Toast.LENGTH_SHORT).show()
            },
        )
    }
    availableRelease?.let { release ->
        UpdateAvailableDialog(release = release, onDismiss = { availableRelease = null })
    }
}
