package dev.solon.bucklerhome

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Records what other apps are playing, for lyrics auto-sync. Adapted from LiveSubs, which runs the
 * same capture on SHIELD: record at the mixer's native 48 kHz stereo and convert to 16 kHz mono
 * ourselves, since asking for 16 kHz directly is not reliable on every device.
 */
object PlaybackCapture {
    const val CAPTURE_RATE = 48_000
    private const val DECIMATION = CAPTURE_RATE / SileroVad.SAMPLE_RATE
    /** 100 ms of interleaved stereo shorts per read. */
    const val RAW_CHUNK_SHORTS = CAPTURE_RATE / 10 * 2

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service starts
    fun open(projection: MediaProjection): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, RAW_CHUNK_SHORTS * 2 * 4))
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialise")
        }
        return record
    }

    /**
     * 48 kHz interleaved stereo to 16 kHz mono floats (-1..1). A windowed-sinc low-pass runs before
     * keeping every third sample, so cymbals and sibilants don't alias into the voice band.
     * Keeps filter history and decimation phase across chunks.
     */
    class Downsampler {
        private val taps = lowPass()
        private val hist = taps.size - 1
        private var work = FloatArray(hist + RAW_CHUNK_SHORTS / 2)
        private var phase = 0

        /** Writes up to [out].size samples; returns how many. */
        fun process(raw: ShortArray, rawN: Int, out: FloatArray): Int {
            val frames = rawN / 2
            if (work.size < hist + frames) work = work.copyOf(hist + frames)
            for (i in 0 until frames) work[hist + i] = (raw[2 * i] + raw[2 * i + 1]) * 0.5f
            val total = hist + frames
            var k = hist + phase
            var n = 0
            while (k < total && n < out.size) {
                var acc = 0f
                for (j in taps.indices) acc += taps[j] * work[k - j]
                out[n++] = (acc / 32_768f).coerceIn(-1f, 1f)
                k += DECIMATION
            }
            phase = (k - total).coerceAtLeast(0)
            work.copyInto(work, 0, total - hist, total)
            return n
        }
    }

    /** Hamming-windowed sinc, flat to ~5.5 kHz and well down by 8 kHz. */
    private fun lowPass(): FloatArray {
        val m = TAPS - 1
        val fc = CUTOFF_HZ / CAPTURE_RATE
        val h = FloatArray(TAPS) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            (sinc * (0.54 - 0.46 * cos(2 * PI * i / m))).toFloat()
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        return h
    }

    private const val TAPS = 63
    private const val CUTOFF_HZ = 6_800.0
}
