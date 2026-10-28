package dev.solon.bucklerhome

import android.content.Context
import java.io.File
import java.io.PrintWriter

/**
 * Developer aid for tuning auto-sync off the TV: when the marker file `autosync-dump` exists in the
 * app's external files folder (created over adb), each song's lyric lines, voice probabilities and
 * estimates are written beside it as CSV. Without the marker nothing is written.
 */
class SyncDump private constructor(private val out: PrintWriter) {
    fun frame(at: Long, reported: Long, probability: Float) = out.println("f,$at,$reported,$probability")

    fun estimate(at: Long, best: SyncEstimator.Estimate?, accepted: Boolean) =
        out.println("e,$at,${best?.offsetMs},${best?.score},${best?.margin},${best?.prominence},$accepted")

    fun close() = out.close()

    companion object {
        fun open(context: Context, key: String, lines: List<LyricLine>): SyncDump? {
            val dir = context.getExternalFilesDir(null) ?: return null
            if (!File(dir, "autosync-dump").exists()) return null
            val name = key.filter { it.isLetterOrDigit() }.take(40)
            val file = File(dir, "sync-$name-${System.currentTimeMillis()}.csv")
            val out = runCatching { PrintWriter(java.io.FileWriter(file), true) }.getOrNull()
                ?: return null
            lines.forEach { line -> out.println("l,${line.atMs},${line.endMs ?: ""},${line.text.replace(',', ' ')}") }
            return SyncDump(out)
        }
    }
}
