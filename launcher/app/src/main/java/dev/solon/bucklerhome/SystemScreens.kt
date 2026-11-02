package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TV_SETTINGS = "com.android.tv.settings"

/**
 * Android TV screens Buckler opens. SHIELD's TvSettings leaves out some of Android's standard
 * actions (Display, Screensaver, Accessibility, Notification access) and answers others twice, so
 * Android asks which app to use (Apps, Developer options). Each screen names TvSettings' own
 * activity first, then the standard action for other TVs; where neither opens, the main Settings
 * list opens and [path] says where to go from there.
 */
enum class SystemScreen(val label: String, private val activity: String?, private val action: String?, private val path: String? = null) {
    NETWORK("network settings", ".connectivity.NetworkActivity", Settings.ACTION_WIFI_SETTINGS),
    APPS("apps", ".device.apps.AppsActivity", Settings.ACTION_APPLICATION_SETTINGS),
    DISPLAY_SOUND("display and sound settings", ".device.displaysound.DisplaySoundActivity", Settings.ACTION_DISPLAY_SETTINGS),
    SCREENSAVER("screensaver settings", ".device.display.daydream.DaydreamActivity", Settings.ACTION_DREAM_SETTINGS),
    STORAGE("storage settings", ".device.StorageResetActivity", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    DATE_TIME("date and time settings", ".system.DateTimeActivity", Settings.ACTION_DATE_SETTINGS),
    KEYBOARD("keyboard settings", ".inputmethod.KeyboardActivity", Settings.ACTION_INPUT_METHOD_SETTINGS),
    CAPTIONS("caption settings", ".system.CaptionSetupActivity", Settings.ACTION_CAPTIONING_SETTINGS),
    ACCESSIBILITY("accessibility settings", null, Settings.ACTION_ACCESSIBILITY_SETTINGS, "Device Preferences → Accessibility"),
    NOTIFICATION_ACCESS(
        "notification access",
        null,
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "Apps → Special app access → Notification access",
    ),

    // Android's developer options, where Network debugging is; SHIELD's own are NvDevelopmentActivity.
    DEVELOPER("Developer options", ".system.development.DevelopmentActivity", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Device Preferences → Developer options"),
    SHIELD_DEVELOPER("SHIELD developer options", ".system.development.NvDevelopmentActivity", null, "Device Preferences → Developer options"),
    ADD_ACCOUNT("accounts", null, Settings.ACTION_ADD_ACCOUNT),
    ACCESSORIES("accessory pairing", ".accessories.AddAccessoryActivity", "com.google.android.intent.action.CONNECT_INPUT"),
    SYSTEM_UPDATE("system update", null, "android.settings.SYSTEM_UPDATE_SETTINGS", "Device Preferences → About → System upgrade"),
    VPN("VPN settings", null, Settings.ACTION_VPN_SETTINGS),
    ABOUT("About this TV", ".about.AboutActivity", Settings.ACTION_DEVICE_INFO_SETTINGS),
    ALL("Settings", ".MainSettings", Settings.ACTION_SETTINGS),
    ;

    /** This screen's own intents, in the order tried: TvSettings' activity, then the standard action. */
    fun intents(): List<Intent> = listOfNotNull(
        activity?.let { Intent().setClassName(TV_SETTINGS, TV_SETTINGS + it) },
        action?.let { Intent(it) },
    )

    /** Opens this screen, else [orElse], else the main Settings list. */
    fun open(context: Context, orElse: SystemScreen? = null) {
        if (intents().any { start(context, it) }) return
        if (orElse != null) return orElse.open(context)
        if (start(context, Intent(Settings.ACTION_SETTINGS))) {
            path?.let { Toast.makeText(context, "In Settings: $it", Toast.LENGTH_LONG).show() }
        } else {
            Toast.makeText(context, "Could not open $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** Goes up each time Buckler grants itself media access, so Now Playing looks again at once. */
object MediaAccess {
    var granted by mutableIntStateOf(0)
        private set

    internal fun bump() {
        granted++
    }
}

/**
 * Turns on Buckler's media access in place, over Network debugging, as `buckler launcher
 * media-access` does from a computer: there is no screen for it to open on SHIELD. Without
 * Network debugging it opens Settings at the nearest screen and says where to go.
 */
@Composable
fun rememberMediaAccessGrant(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        toast("Turning on media access…")
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val main = Handler(Looper.getMainLooper())
                    AdbSelf.connect(context) { main.post { toast("Allow debugging on the TV's prompt (tick Always allow).") } }.use { adb ->
                        val pkg = context.packageName
                        adb.shell("cmd notification allow_listener $pkg/$pkg.NowPlayingListener")
                    }
                    if (!nowPlayingAccessGranted(context)) error("not turned on")
                }.exceptionOrNull()
            }
            if (failure == null) {
                MediaAccess.bump()
                toast("Media access is on.")
            } else {
                toast(AdbSelf.explain(failure, "Not turned on"))
                SystemScreen.NOTIFICATION_ACCESS.open(context)
            }
        }
    }
}
