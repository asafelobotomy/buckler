package dev.solon.bucklerhome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Lyrics auto-sync: hears what is playing (Android playback capture, after the user's consent),
 * asks a voice detector when singing happens, and lines that up with the lyrics to find each
 * song's real offset. Audio is only analysed in memory; nothing is stored or sent.
 *
 * Voice detection only runs while the Lyrics stage shows synced lyrics for a playing song.
 */
class LyricsSyncService : Service() {
    @Volatile
    private var running = false
    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || running) return START_NOT_STICKY
        AutoSync.setStatus(AutoSync.Status.STARTING)
        startInForeground()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (mp == null) {
            fail("no media projection")
            return START_NOT_STICKY
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, null)
        projection = mp
        running = true
        worker = Thread({ listen(mp) }, "buckler-lyrics-sync").apply { start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        projection?.stop()
        projection = null
        AutoSync.setStatus(AutoSync.Status.OFF)
        super.onDestroy()
    }

    private fun fail(why: String) {
        Log.w(TAG, "auto-sync: $why")
        AutoSync.setStatus(AutoSync.Status.FAILED)
        stopSelf()
    }

    private fun listen(mp: MediaProjection) {
        val vad = runCatching { SileroVad(SileroVad.modelFile(this)) }.getOrElse {
            fail("voice model: ${it.message}")
            return
        }
        val record = runCatching { PlaybackCapture.open(mp) }.getOrElse {
            vad.close()
            fail("capture: ${it.message}")
            return
        }
        try {
            record.startRecording()
            AutoSync.setStatus(AutoSync.Status.LISTENING)
            Listener(vad, record).run()
        } catch (e: Exception) {
            if (running) Log.w(TAG, "auto-sync stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            vad.close()
        }
    }

    /** Reads 100 ms chunks, cuts 32 ms frames, and feeds voice probabilities to the estimator. */
    private inner class Listener(private val vad: SileroVad, private val record: AudioRecord) {
        private val raw = ShortArray(PlaybackCapture.RAW_CHUNK_SHORTS)
        private val mono = FloatArray(PlaybackCapture.RAW_CHUNK_SHORTS / 2)
        private val frame = FloatArray(SileroVad.FRAME)
        private val downsampler = PlaybackCapture.Downsampler()
        private val estimator = SyncEstimator()
        private val measured = ArrayList<Pair<Long, Long>>()
        private val stamp = AudioTimestamp()
        private var filled = 0
        private var framesRead = 0L
        private var silentSince = SystemClock.elapsedRealtime()
        private var lastKey: String? = null
        private var lastEstimate = 0L
        private var lastPeak = 0
        private var dump: SyncDump? = null
        private var dumpKey: String? = null

        fun run() {
            while (running) {
                val n = record.read(raw, 0, raw.size)
                if (n <= 0) continue
                val chunkStart = framesRead
                framesRead += n / 2
                watchForSilence(n)
                val chunkTime = chunkStartTime(chunkStart, n / 2)
                val m = downsampler.process(raw, n, mono)
                for (j in 0 until m) {
                    frame[filled++] = mono[j]
                    if (filled == SileroVad.FRAME) {
                        filled = 0
                        // Start of this frame, in elapsedRealtime ms (the clock media sessions use).
                        val at = chunkTime + (j + 1 - SileroVad.FRAME) * 1000L / SileroVad.SAMPLE_RATE
                        onFrame(at)
                    }
                }
            }
        }

        /** When the first sample of this chunk was mixed, from the recorder's own timestamps. */
        private fun chunkStartTime(chunkStart: Long, frames: Int): Long {
            val ok = record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
            return if (ok) {
                stamp.nanoTime / 1_000_000 + (chunkStart - stamp.framePosition) * 1000 / PlaybackCapture.CAPTURE_RATE
            } else {
                SystemClock.elapsedRealtime() - frames * 1000L / PlaybackCapture.CAPTURE_RATE
            }
        }

        private fun watchForSilence(n: Int) {
            var peak = 0
            for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(raw[i].toInt()))
            lastPeak = peak
            val now = SystemClock.elapsedRealtime()
            if (peak > 0) {
                silentSince = now
                if (AutoSync.status.value == AutoSync.Status.SILENT) AutoSync.setStatus(AutoSync.Status.LISTENING)
            } else if (now - silentSince > SILENT_MS && AutoSync.track?.playing == true) {
                AutoSync.setStatus(AutoSync.Status.SILENT)
            }
        }

        private fun onFrame(at: Long) {
            val key = AutoSync.songKey
            val track = AutoSync.track
            val lines = AutoSync.lines
            if (key != lastKey) {
                lastKey = key
                estimator.reset()
                vad.reset()
                measured.clear()
            }
            // The recorder starts once the song's lines have loaded, which can be after the key.
            if (key != dumpKey && key != null && lines != null) {
                dump?.close()
                dump = SyncDump.open(this@LyricsSyncService, key, lines)
                dumpKey = key
            }
            if (key == null || track == null || lines == null || !track.playing) return
            val reported = track.positionNow(at)
            val probability = vad.probability(frame)
            estimator.add(reported, probability)
            dump?.frame(at, reported, probability)
            if (at - lastEstimate < ESTIMATE_EVERY_MS) return
            lastEstimate = at
            val estimate = estimator.estimate(lines)
            Log.d(
                TAG,
                "auto-sync: frames=${estimator.frames} voice=${"%.2f".format(estimator.meanVoice())} " +
                    "peak=$lastPeak best=${estimator.lastBest} accepted=${estimate != null}",
            )
            dump?.estimate(at, estimator.lastBest, estimate != null)
            estimate ?: return
            measured += at to estimate.offsetMs
            if (measured.size > KEEP) measured.removeAt(0)
            // Only once three agree to within 0.1 s across at least 12 s of new audio: estimates
            // 4 s apart share most of their 45 s window, so close ones are not independent.
            val latest = estimate.offsetMs
            val agreeing = measured.filter { kotlin.math.abs(it.second - latest) <= AGREE_WITHIN_MS }
            val spread = agreeing.last().first - agreeing.first().first
            if (agreeing.size < AGREE || spread < AGREE_SPAN_MS) return
            val offsets = agreeing.map { it.second }.sorted()
            AutoSync.publish(this@LyricsSyncService, key, offsets[offsets.size / 2])
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lyrics auto-sync", NotificationManager.IMPORTANCE_LOW),
        )
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Lyrics auto-sync")
            .setContentText("Listening to time lyrics. Audio stays on this TV.")
            .setOngoing(true)
            .build()
        // Microphone as well as media projection: without it Android 11 feeds zeros in the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                note,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, note)
        }
    }

    companion object {
        private const val TAG = "bucklerhome"
        private const val CHANNEL = "lyrics_sync"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ESTIMATE_EVERY_MS = 4_000L
        private const val KEEP = 8
        private const val AGREE = 3
        private const val AGREE_WITHIN_MS = 100L
        private const val AGREE_SPAN_MS = 12_000L
        private const val SILENT_MS = 15_000L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LyricsSyncService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricsSyncService::class.java))
        }
    }
}
