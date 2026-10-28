package dev.solon.bucklerhome

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import coil.memory.MemoryCache

/**
 * Keeps Home light while another app is in front. Coil's memory cache would otherwise take a
 * quarter of the heap limit (48 MB on SHIELD) and only empty under heavy memory pressure, which
 * Home rarely sees; the images it holds reload from the disk cache in a few milliseconds.
 */
class BucklerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(MEMORY_CACHE_SHARE).build() }
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN: every Buckler screen has left the display (an app or the screensaver is up).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) imageLoader.memoryCache?.clear()
    }

    private companion object {
        // About 19 MB on SHIELD: a screen of covers plus the stage picture.
        const val MEMORY_CACHE_SHARE = 0.10
    }
}
