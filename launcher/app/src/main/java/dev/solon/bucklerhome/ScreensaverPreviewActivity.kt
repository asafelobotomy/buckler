package dev.solon.bucklerhome

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Full-screen preview of the configured screensaver; any key or touch closes it. */
class ScreensaverPreviewActivity : ComponentActivity() {
    private var saver: ScreensaverView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = ScreensaverView(this, ScreensaverPrefs.load(this))
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
}
