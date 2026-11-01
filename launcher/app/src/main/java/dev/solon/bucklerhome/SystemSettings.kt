package dev.solon.bucklerhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/**
 * Android system settings the launcher may change on the user's behalf. Both permissions are
 * granted once from a computer (`buckler launcher grant-settings`); without them every call here
 * reports failure and Settings shows how to grant them.
 */
object SystemSettings {
    const val GRANT_COMMAND = "buckler launcher grant-settings"

    /** Hidden-API key names; stable since Android 5 but not exposed as constants. */
    private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"

    /** Longest screen-off timeout is "never"; Android stores it as the max int. */
    const val NEVER_MS = Int.MAX_VALUE

    fun canWriteSystem(context: Context): Boolean = Settings.System.canWrite(context)

    fun canWriteSecure(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Milliseconds of inactivity before the screensaver starts (the system screen-off timeout). */
    fun idleTimeoutMs(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 300_000)

    fun setIdleTimeoutMs(context: Context, ms: Int): Boolean = canWriteSystem(context) &&
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) }
            .getOrDefault(false)

    fun screensaverEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_SCREENSAVER_ENABLED, 1) == 1

    fun setScreensaverEnabled(context: Context, enabled: Boolean): Boolean = canWriteSecure(context) &&
        runCatching { Settings.Secure.putInt(context.contentResolver, KEY_SCREENSAVER_ENABLED, if (enabled) 1 else 0) }
            .getOrDefault(false)

    fun canWrite(context: Context, table: SettingsTable): Boolean =
        if (table == SettingsTable.SYSTEM) canWriteSystem(context) else canWriteSecure(context)

    /** [key]'s value, or what Android uses while it is unset. */
    fun get(context: Context, key: SystemKey): String {
        val resolver = context.contentResolver
        val value = runCatching {
            when (key.table) {
                SettingsTable.SYSTEM -> Settings.System.getString(resolver, key.name)
                SettingsTable.SECURE -> Settings.Secure.getString(resolver, key.name)
                SettingsTable.GLOBAL -> Settings.Global.getString(resolver, key.name)
            }
        }.getOrNull()
        return value ?: key.default
    }

    /** Writes [key]; Android's own services watch these keys, so the change applies at once. */
    fun put(context: Context, key: SystemKey, value: String): Boolean = canWrite(context, key.table) &&
        runCatching {
            val resolver = context.contentResolver
            when (key.table) {
                SettingsTable.SYSTEM -> Settings.System.putString(resolver, key.name, value)
                SettingsTable.SECURE -> Settings.Secure.putString(resolver, key.name, value)
                SettingsTable.GLOBAL -> Settings.Global.putString(resolver, key.name, value)
            }
        }.getOrDefault(false)

    fun explainGrant(context: Context) {
        Toast.makeText(context, "One-time setup needed: run $GRANT_COMMAND", Toast.LENGTH_LONG).show()
    }
}

/** Android's three settings tables: System needs the WRITE_SETTINGS app-op, the others WRITE_SECURE_SETTINGS. */
enum class SettingsTable { SYSTEM, SECURE, GLOBAL }

/**
 * One of Android's whole-TV settings: its table, its key, and the value Android uses while it is
 * unset. Android refuses apps its private System keys whatever they hold; [overAdb] ones are then
 * written over Buckler's own adb link, as the shell user may.
 */
data class SystemKey(val table: SettingsTable, val name: String, val default: String, val overAdb: Boolean = false) {
    val shellTable: String get() = table.name.lowercase()
}

/** The whole-TV settings Buckler Settings shows as its own rows. */
object SystemKeys {
    val AUTO_TIME = SystemKey(SettingsTable.GLOBAL, Settings.Global.AUTO_TIME, "1")
    val AUTO_TIME_ZONE = SystemKey(SettingsTable.GLOBAL, Settings.Global.AUTO_TIME_ZONE, "1")
    /** "12", "24", or unset to follow the language. */
    val TIME_12_24 = SystemKey(SettingsTable.SYSTEM, Settings.System.TIME_12_24, "")
    val MONO_AUDIO = SystemKey(SettingsTable.SYSTEM, "master_mono", "0", overAdb = true)
    /** Text size for every app, 1.0 being normal. */
    val FONT_SCALE = SystemKey(SettingsTable.SYSTEM, Settings.System.FONT_SCALE, "1.0", overAdb = true)
    val SOUND_EFFECTS = SystemKey(SettingsTable.SYSTEM, Settings.System.SOUND_EFFECTS_ENABLED, "1")
    val AWAKE_ON_MUSIC = SystemKey(SettingsTable.SECURE, "awake_on_music", "1")
    /** Milliseconds from the last button press until the TV sleeps; -1 is never. */
    val SLEEP_TIMEOUT = SystemKey(SettingsTable.SECURE, "sleep_timeout", "-1")
    val HIGH_TEXT_CONTRAST = SystemKey(SettingsTable.SECURE, "high_text_contrast_enabled", "0")
    /** Speech rate in percent of normal. */
    val TTS_RATE = SystemKey(SettingsTable.SECURE, Settings.Secure.TTS_DEFAULT_RATE, "100")
    val CAPTIONS = SystemKey(SettingsTable.SECURE, "accessibility_captioning_enabled", "0")
    val CAPTION_SCALE = SystemKey(SettingsTable.SECURE, "accessibility_captioning_font_scale", "1.0")
    /** 0–3 are Android's styles; -1 is a custom one made in Android's caption settings. */
    val CAPTION_PRESET = SystemKey(SettingsTable.SECURE, "accessibility_captioning_preset", "0")
    val HDMI_CONTROL = SystemKey(SettingsTable.GLOBAL, "hdmi_control_enabled", "1")
    val HDMI_TV_ON = SystemKey(SettingsTable.GLOBAL, "hdmi_one_touch_play_enabled", "1")
    val HDMI_TV_OFF = SystemKey(SettingsTable.GLOBAL, "hdmi_control_auto_device_off_enabled", "1")
    val HDMI_VOLUME = SystemKey(SettingsTable.GLOBAL, "hdmi_control_volume_control_enabled", "1")
    /** 0 automatic, 1 never, 2 always, 3 manual (formats picked in Android's settings). */
    val SURROUND = SystemKey(SettingsTable.GLOBAL, "encoded_surround_output", "0")
    val ANIMATOR_SCALE = SystemKey(SettingsTable.GLOBAL, Settings.Global.ANIMATOR_DURATION_SCALE, "1.0")
    val WINDOW_SCALE = SystemKey(SettingsTable.GLOBAL, Settings.Global.WINDOW_ANIMATION_SCALE, "1.0")
    val TRANSITION_SCALE = SystemKey(SettingsTable.GLOBAL, Settings.Global.TRANSITION_ANIMATION_SCALE, "1.0")
    val DNS_MODE = SystemKey(SettingsTable.GLOBAL, "private_dns_mode", "opportunistic")
    val DNS_HOST = SystemKey(SettingsTable.GLOBAL, "private_dns_specifier", "")
    val KEYBOARD = SystemKey(SettingsTable.SECURE, Settings.Secure.DEFAULT_INPUT_METHOD, "")
    /** Each keyboard's id and its subtypes: "id;subtype;subtype:id2". */
    val KEYBOARDS = SystemKey(SettingsTable.SECURE, Settings.Secure.ENABLED_INPUT_METHODS, "")
    val AUTOFILL = SystemKey(SettingsTable.SECURE, "autofill_service", "")
}
