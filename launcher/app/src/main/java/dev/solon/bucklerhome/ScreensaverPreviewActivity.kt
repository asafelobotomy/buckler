package dev.solon.bucklerhome

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Full-screen preview of a screensaver ([EXTRA_MODE], else the chosen one); any key or touch closes it. */
class ScreensaverPreviewActivity : ComponentActivity() {
    private var saver: ScreensaverView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val saved = ScreensaverPrefs.load(this)
        // A screensaver's own settings page previews that one, even before it is chosen.
        val mode = intent.getStringExtra(EXTRA_MODE)?.let(ScreensaverMode::fromName) ?: saved.mode
        val view = ScreensaverView(this, saved.copy(mode = mode))
        saver = view
        setContentView(view)
        view.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        saver?.stop()
        saver = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "dev.solon.bucklerhome.SAVER_MODE"
    }
}
