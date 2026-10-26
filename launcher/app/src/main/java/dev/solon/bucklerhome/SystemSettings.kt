package dev.solon.bucklerhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android system settings the launcher may change on the user's behalf. Both permissions are
 * granted once from a computer (`buckler launcher grant-settings`); without them every call here
 * reports failure and Settings shows how to grant them.
 */
object SystemSettings {
    const val GRANT_COMMAND = "buckler launcher grant-settings"

    /** Hidden-API key names; stable since Android 5 but not exposed as constants. */
    private const val KEY_MONO = "master_mono"
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

    fun monoAudio(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, KEY_MONO, 0) == 1

    fun setMonoAudio(context: Context, on: Boolean): Boolean = canWriteSystem(context) &&
        runCatching { Settings.System.putInt(context.contentResolver, KEY_MONO, if (on) 1 else 0) }
            .getOrDefault(false)
}
