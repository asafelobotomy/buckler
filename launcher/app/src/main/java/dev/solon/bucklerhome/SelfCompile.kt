package dev.solon.bucklerhome

import android.content.Context
import android.util.Log

/**
 * After an update Android runs Buckler uncompiled until the TV's overnight maintenance. Once per
 * version, a minute after it starts, Buckler asks for that same compile now: the background
 * dexopt job, which uses the startup profile and does not restart the app (a plain "compile -f"
 * may). It goes through Buckler's own adb link only if the TV already trusts it, quietly: no
 * "Allow debugging?" prompt for work no one asked for. Without that link it waits for the night.
 */
object SelfCompile {
    private const val PREFS = "bucklerhome"
    private const val KEY_DONE = "self_compiled_version"
    private const val DELAY_MS = 60_000L

    fun afterUpdate(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_DONE, 0) == BuildConfig.VERSION_CODE) return
        Thread({
            Thread.sleep(DELAY_MS)
            val ok = runCatching {
                AdbSelf.connect(app, quiet = true).use { adb ->
                    adb.shell("cmd package bg-dexopt-job ${app.packageName}").ok
                }
            }.onFailure { Log.i(TAG, "Not compiled now: ${it.message}") }.getOrDefault(false)
            // Tried once per version either way: no link now means none on the next start either.
            prefs.edit().putInt(KEY_DONE, BuildConfig.VERSION_CODE).apply()
            Log.i(TAG, if (ok) "Compiled ${BuildConfig.VERSION_NAME}" else "Left to Android's maintenance")
        }, "self-compile").apply { isDaemon = true }.start()
    }

    private const val TAG = "SelfCompile"
}
