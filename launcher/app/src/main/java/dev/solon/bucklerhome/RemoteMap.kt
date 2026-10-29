package dev.solon.bucklerhome

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager

/** How a button was pressed; [short] is for one-line summaries. */
enum class PressKind(val label: String, val short: String) {
    PRESS("Press", "Press"),
    DOUBLE("Double press", "Double"),
    HOLD("Hold", "Hold"),
}

/**
 * Buttons across the SHIELD remotes ([RemoteModel] says which a model has and where), and who can
 * change them. Measured on SHIELD with the 2019 remote: Netflix, Back and the media keys reach an
 * accessibility service first; Home, Power and its microphone button never do; ≡ is remapped
 * through SHIELD's own setting; volume drives the TV or soundbar and stays as it is. The 2015 and
 * 2017 remotes send their microphone button as an ordinary button (NVIDIA's key layout: BUTTON_16).
 */
enum class RemoteButton(
    val label: String,
    val keyCode: Int?,
    val mapping: Mapping,
    /** Why it cannot be changed, for buttons that cannot. */
    val fixedReason: String? = null,
) {
    POWER("Power", null, Mapping.FIXED, "Android handles Power before any app sees it"),
    ASSIST("Microphone", null, Mapping.FIXED, "This remote does not send it while Google Assistant is off"),
    VOICE("Microphone", KeyEvent.KEYCODE_BUTTON_16, Mapping.BUCKLER),
    DPAD("D-pad and OK", null, Mapping.FIXED, "Needed to move around"),
    BACK("Back", KeyEvent.KEYCODE_BACK, Mapping.BUCKLER),
    HOME("Home", null, Mapping.FIXED, "SHIELD handles Home before any app sees it"),
    REWIND("Rewind", KeyEvent.KEYCODE_MEDIA_REWIND, Mapping.BUCKLER),
    PLAY_PAUSE("Play/Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Mapping.BUCKLER),
    FAST_FORWARD("Fast-forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, Mapping.BUCKLER),
    VOLUME("Volume", null, Mapping.FIXED, "Controls the TV or soundbar; left as it is"),
    MENU("≡ Menu", null, Mapping.SHIELD),
    NETFLIX("Netflix", KeyEvent.KEYCODE_BUTTON_12, Mapping.BUCKLER),
    ;

    enum class Mapping { BUCKLER, SHIELD, FIXED }

    companion object {
        fun forKey(keyCode: Int): RemoteButton? = entries.firstOrNull { it.mapping == Mapping.BUCKLER && it.keyCode == keyCode }
    }
}

/** What a remapped press does. [code] is how it is stored. */
sealed class RemoteAction(val code: String, val label: String) {
    data object Default : RemoteAction("default", "Default")
    data object DoNothing : RemoteAction("none", "Nothing")
    data object BucklerSettings : RemoteAction("settings", "Buckler Settings")
    data object RecentApps : RemoteAction("recents", "Recent apps")
    data object Home : RemoteAction("home", "Home")
    data object Back : RemoteAction("back", "Back")
    data object PlayPause : RemoteAction("playpause", "Play/Pause")
    data object Screensaver : RemoteAction("screensaver", "Screensaver")
    data object Screenshot : RemoteAction("screenshot", "Screenshot")
    data object PowerMenu : RemoteAction("power", "Power menu")
    data class OpenApp(val packageName: String, val appLabel: String) : RemoteAction("app:$packageName", appLabel)

    companion object {
        val FIXED_CHOICES = listOf(DoNothing, BucklerSettings, RecentApps, Home, Back, PlayPause, Screensaver, Screenshot, PowerMenu)

        fun parse(code: String?, labelOf: (String) -> String = { it }): RemoteAction = when {
            code == null -> Default
            code.startsWith("app:") -> code.removePrefix("app:").let { OpenApp(it, labelOf(it)) }
            else -> (FIXED_CHOICES + Default).firstOrNull { it.code == code } ?: Default
        }
    }
}

/** Saved choices for the buttons Buckler handles, plus switching the service on and off. */
object RemoteMap {
    private const val PREFS = "bucklerhome_remote"
    private const val KEY_WANTED = "service_wanted"
    private const val RELIST_DELAY_MS = 3_000L
    private val SERVICE = ComponentName(OUR_PACKAGE, RemoteButtonsService::class.java.name).flattenToString()

    private fun key(button: RemoteButton, kind: PressKind) = "${button.name}.${kind.name}"

    fun action(context: Context, button: RemoteButton, kind: PressKind): RemoteAction {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(button, kind), null)
        return RemoteAction.parse(code) { pkg -> appLabel(context, pkg) }
    }

    fun set(context: Context, button: RemoteButton, kind: PressKind, action: RemoteAction) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (action == RemoteAction.Default) edit.remove(key(button, kind)) else edit.putString(key(button, kind), action.code)
        edit.apply()
    }

    /** True when any press of [button] does something other than its default. */
    fun remapped(context: Context, button: RemoteButton): Boolean =
        PressKind.entries.any { action(context, button, it) != RemoteAction.Default }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun serviceOn(context: Context): Boolean =
        enabledServices(context).contains(SERVICE) &&
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    /** Adds or removes the service from Android's list, keeping any others; false without permission. */
    fun setServiceOn(context: Context, on: Boolean): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).apply()
        return writeService(context, on)
    }

    /**
     * When Buckler's process is killed (Settings → Force stop, a crash) Android marks the service
     * crashed and will not bind it again, though it stays listed. Nothing changes the list on its
     * own to fix that: on Android 11, editing it while Android is still binding the service can
     * crash the system itself (seen on SHIELD). Settings → Remote offers [restartService] instead.
     */
    fun needsRestart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false) && !serviceRunning(context)

    /**
     * Brings stopped remote buttons back. After a force-stop Android first takes the service off the
     * list, then marks it crashed; the mark only clears when the list changes from having it to not.
     * So: list it (a crashed service is not bound, so this cannot race a bind; one that merely fell
     * off the list binds now and that is the end of it), and only if it is still not running a few
     * seconds later, when nothing can be binding, take it off and put it back.
     */
    fun restartService(context: Context, onDone: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        writeService(context, true)
        handler.postDelayed({
            if (serviceRunning(context)) {
                onDone()
                return@postDelayed
            }
            writeService(context, false)
            handler.postDelayed({
                writeService(context, true)
                handler.postDelayed(onDone, RELIST_DELAY_MS)
            }, RELIST_DELAY_MS)
        }, RELIST_DELAY_MS)
    }

    /** Bound and taking keys (listed is not enough: a crashed service stays listed). */
    fun serviceRunning(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == OUR_PACKAGE && info.name == RemoteButtonsService::class.java.name
        }
    }

    private fun writeService(context: Context, on: Boolean): Boolean = runCatching {
        val others = enabledServices(context).filter { it != SERVICE }
        val list = if (on) others + SERVICE else others
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
    }.isSuccess

    private fun enabledServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }
}

/**
 * The ≡ button, through SHIELD's own "Customize menu button" setting (global settings; the values
 * are SHIELD's). Buckler writes them with WRITE_SECURE_SETTINGS. "Buckler Settings" is SHIELD's
 * "Start any app" ([START_APP]) with Buckler's package: SHIELD opens the package's first TV entry
 * (getLeanbackLaunchIntentForPackage), which the manifest makes Buckler Settings.
 */
object ShieldMenuButton {
    /** SHIELD's "Start any app": opens the package in the matching *_start_any_apps_item setting. */
    const val START_APP = 10

    /** SHIELD's action values and their names. */
    val CHOICES = listOf(
        START_APP to "Buckler Settings",
        0 to "Settings",
        1 to "Recent apps",
        2 to "All apps",
        12 to "Close all apps",
        18 to "Screensaver",
        6 to "Screenshot",
        7 to "Mute",
        13 to "Menu (to the app)",
    )

    private fun setting(kind: PressKind) = when (kind) {
        PressKind.PRESS -> "customize_menu_option"
        PressKind.DOUBLE -> "customize_menu_double_press_option"
        PressKind.HOLD -> "customize_menu_hold_option"
    }

    private fun appSetting(kind: PressKind) = when (kind) {
        PressKind.PRESS -> "customize_menu_start_any_apps_item"
        PressKind.DOUBLE -> "customize_menu_double_press_start_any_apps_item"
        PressKind.HOLD -> "customize_menu_hold_start_any_apps_item"
    }

    fun value(context: Context, kind: PressKind): Int =
        Settings.Global.getInt(context.contentResolver, setting(kind), 0)

    private fun app(context: Context, kind: PressKind): String? =
        Settings.Global.getString(context.contentResolver, appSetting(kind))

    /** Whether [kind] is set to [choice]; "Start any app" counts only with Buckler's package. */
    fun isChosen(context: Context, kind: PressKind, choice: Int): Boolean {
        val v = value(context, kind)
        return v == choice && (choice != START_APP || app(context, kind) == context.packageName)
    }

    fun label(context: Context, kind: PressKind): String {
        val v = value(context, kind)
        if (v == START_APP) {
            val pkg = app(context, kind)
            return if (pkg == context.packageName) "Buckler Settings" else "Open ${pkg?.let { appName(context, it) } ?: "an app"}"
        }
        return CHOICES.firstOrNull { it.first == v }?.second ?: "SHIELD option $v"
    }

    fun set(context: Context, kind: PressKind, value: Int): Boolean = runCatching {
        // The app first, so SHIELD never sees "Start any app" without Buckler's package.
        if (value == START_APP) Settings.Global.putString(context.contentResolver, appSetting(kind), context.packageName)
        Settings.Global.putInt(context.contentResolver, setting(kind), value)
    }.isSuccess
}
