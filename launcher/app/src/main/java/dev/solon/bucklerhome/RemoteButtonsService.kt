package dev.solon.bucklerhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Does what Settings → Remote chose for Netflix, Back and the media keys. A button with nothing
 * remapped passes straight through. Once any of its presses is remapped Buckler takes the button
 * over, and its default press is done for it (Back, the media key, opening Netflix). Only a button
 * with a double press waits for a second press, so the others react at once.
 */
class RemoteButtonsService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var heldButton: RemoteButton? = null
    private var holdFired = false
    private var pendingPress: Pair<RemoteButton, Runnable>? = null

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val button = RemoteButton.forKey(event.keyCode) ?: return false
        if (!RemoteMap.remapped(this, button)) return false
        val hold = RemoteMap.action(this, button, PressKind.HOLD)
        val double = RemoteMap.action(this, button, PressKind.DOUBLE)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                heldButton = button
                holdFired = false
                handler.removeCallbacks(holdCheck)
                if (hold != RemoteAction.Default) handler.postDelayed(holdCheck, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(holdCheck)
                heldButton = null
                if (holdFired) return true
                val waiting = pendingPress
                when {
                    waiting != null && waiting.first == button -> {
                        handler.removeCallbacks(waiting.second)
                        pendingPress = null
                        run(button, double)
                    }
                    double != RemoteAction.Default -> {
                        val single = Runnable {
                            pendingPress = null
                            run(button, RemoteMap.action(this, button, PressKind.PRESS))
                        }
                        pendingPress = button to single
                        handler.postDelayed(single, DOUBLE_MS)
                    }
                    else -> run(button, RemoteMap.action(this, button, PressKind.PRESS))
                }
            }
        }
        return true
    }

    private val holdCheck = Runnable {
        val button = heldButton ?: return@Runnable
        holdFired = true
        run(button, RemoteMap.action(this, button, PressKind.HOLD))
    }

    private fun run(button: RemoteButton, action: RemoteAction) {
        when (action) {
            RemoteAction.Default -> runDefault(button)
            RemoteAction.DoNothing -> Unit
            RemoteAction.BucklerSettings -> open(Intent(this, BucklerSettingsActivity::class.java))
            RemoteAction.RecentApps -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            RemoteAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteAction.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemoteAction.Screensaver -> open(Intent(this, ScreensaverPreviewActivity::class.java))
            RemoteAction.Screenshot -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            RemoteAction.PowerMenu -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            is RemoteAction.OpenApp -> launchPackage(this, action.packageName, action.appLabel)
        }
    }

    /** What the button does when Buckler is not handling it. */
    private fun runDefault(button: RemoteButton) {
        when (button) {
            RemoteButton.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteButton.NETFLIX -> launchPackage(this, NETFLIX, "Netflix")
            else -> button.keyCode?.let(::mediaKey)
        }
    }

    private fun mediaKey(code: Int) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun open(intent: Intent) = safeLaunch(this, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Buckler")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private companion object {
        const val HOLD_MS = 500L
        const val DOUBLE_MS = 300L
        const val NETFLIX = "com.netflix.ninja"
    }
}
