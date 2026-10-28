package dev.solon.bucklerhome

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lyrics auto-sync, shared between the Lyrics stage (what is showing) and [LyricsSyncService]
 * (what is heard). The service writes the offset it measures for each song; the stage adds it.
 */
object AutoSync {
    enum class Status(val label: String) {
        OFF("off"),
        STARTING("starting"),
        LISTENING("listening"),
        SILENT("hearing nothing"),
        FAILED("could not start"),
    }

    data class Result(val songKey: String, val offsetMs: Long)

    private val statusFlow = MutableStateFlow(Status.OFF)
    private val resultFlow = MutableStateFlow<Result?>(null)
    val status: StateFlow<Status> = statusFlow
    val result: StateFlow<Result?> = resultFlow

    /** What the Lyrics stage is showing; null when it is not on screen or has no synced lines. */
    @Volatile
    var songKey: String? = null
        private set

    @Volatile
    var track: NowPlayingInfo? = null
        private set

    @Volatile
    var lines: List<LyricLine>? = null
        private set

    fun follow(key: String?, info: NowPlayingInfo?, synced: List<LyricLine>?) {
        songKey = key
        track = info
        lines = synced
    }

    fun setStatus(status: Status) {
        statusFlow.value = status
    }

    /** A measured offset for [key], kept so the next play of the song starts in sync. */
    fun publish(context: Context, key: String, offsetMs: Long) {
        resultFlow.value = Result(key, offsetMs)
        LyricsPrefs.setAutoOffset(context, key, offsetMs)
    }

    /** The live measurement for [key], else the one saved from an earlier play, else null. */
    fun offsetFor(context: Context, key: String, live: Result?): Long? =
        live?.takeIf { it.songKey == key }?.offsetMs ?: LyricsPrefs.autoOffset(context, key)
}
