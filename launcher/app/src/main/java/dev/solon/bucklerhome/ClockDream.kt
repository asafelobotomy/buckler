package dev.solon.bucklerhome

import android.service.dreams.DreamService

/**
 * System screensaver entry point. The class keeps its original name so devices already set to
 * "buckler-home/.ClockDream" keep working; what it shows is chosen in Settings → Screensaver.
 */
class ClockDream : DreamService() {
    private var view: ScreensaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val saver = ScreensaverView(this, ScreensaverPrefs.load(this))
        view = saver
        setContentView(saver)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}
