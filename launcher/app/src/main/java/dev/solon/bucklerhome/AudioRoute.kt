package dev.solon.bucklerhome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * Where media audio is going, and roughly how long it takes to reach the speaker. Media sessions
 * report the player's position, not what is audible, so lyrics subtract this to match the sound.
 *
 * The route is read from a brief silent media track (where Android sends it is where Spotify's
 * audio goes), so a paired but idle Bluetooth speaker does not count. Device queries are slow on
 * SHIELD, so all of it runs on a worker thread and callers read [cached].
 */
object AudioRoute {
    enum class Kind(val label: String) {
        BLUETOOTH("Bluetooth"),
        DIRECT("TV / HDMI"),
    }

    data class Snapshot(val kind: Kind, val delayMs: Long)

    /**
     * Bluetooth time the platform does not report (the A2DP link and the speaker's decoding).
     * Calibrated by ear on SHIELD with a Bluetooth soundbar: about 0.2 s in all with the output
     * latency, which matches the 220 ms SonicSound measures on the same route.
     */
    private const val BLUETOOTH_EXTRA_MS = 100L
    private const val REFRESH_INTERVAL_MS = 2_000L
    private const val PROBE_SETTLE_MS = 150L

    @Volatile
    private var cached = Snapshot(Kind.DIRECT, 0L)

    @Volatile
    private var lastRefresh = 0L
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "buckler-audio-route").apply { isDaemon = true } }

    /** Last measured route and delay; never blocks. */
    fun cached(): Snapshot = cached

    /** Re-measure on the worker, at most every 2 s unless [force]; [onResult] runs on the worker. */
    fun refresh(context: Context, force: Boolean = false, onResult: (Snapshot) -> Unit = {}) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRefresh < REFRESH_INTERVAL_MS) return
        lastRefresh = now
        val app = context.applicationContext
        worker.execute {
            runCatching { measure(app) }.onSuccess {
                cached = it
                onResult(it)
            }
        }
    }

    /** Blocking: the active route and its delay. */
    private fun measure(context: Context): Snapshot {
        val am = context.getSystemService(AudioManager::class.java) ?: return Snapshot(Kind.DIRECT, 0L)
        val kind = probeRoute()?.takeIf(::isEndpoint)?.let(::kindOf) ?: fallbackKind(am)
        val delay = (outputLatencyMs(am) ?: 0L) + if (kind == Kind.BLUETOOTH) BLUETOOTH_EXTRA_MS else 0L
        return Snapshot(kind, delay)
    }

    internal fun kindOf(type: Int): Kind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> Kind.BLUETOOTH
        else -> Kind.DIRECT
    }

    /**
     * True for a real speaker or output. SHIELD routes every media track to an internal BUS
     * output and bridges that to Bluetooth, so BUS (or an unknown type) says nothing about the speaker.
     */
    internal fun isEndpoint(type: Int): Boolean = type != AudioDeviceInfo.TYPE_BUS && type != AudioDeviceInfo.TYPE_UNKNOWN

    /** Device a silent media track is routed to; null when the platform will not say. */
    private fun probeRoute(): Int? {
        val rate = 44_100
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return null
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return try {
            track.setVolume(0f)
            track.write(ByteArray(size), 0, size)
            track.play()
            Thread.sleep(PROBE_SETTLE_MS)
            track.routedDevice?.type
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /**
     * When the probe cannot tell: whether Android has A2DP on for media (connected and routed, not
     * just paired), else whether any Bluetooth output is connected at all.
     */
    @Suppress("DEPRECATION")
    private fun fallbackKind(am: AudioManager): Kind {
        val a2dpOn = runCatching { am.isBluetoothA2dpOn }.getOrNull()
        if (a2dpOn != null) return if (a2dpOn) Kind.BLUETOOTH else Kind.DIRECT
        val anyBluetooth = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { kindOf(it.type) == Kind.BLUETOOTH }
        return if (anyBluetooth) Kind.BLUETOOTH else Kind.DIRECT
    }

    /** The mixer-to-output latency the platform reports (a hidden but long-standing call). */
    private fun outputLatencyMs(am: AudioManager): Long? = runCatching {
        val latency = AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
            .invoke(am, AudioManager.STREAM_MUSIC) as Int
        latency.toLong().takeIf { it in 0..2_000 }
    }.getOrNull()
}

/** The route and delay, re-checked every 2 s and whenever an output connects or drops. */
@Composable
fun rememberAudioRoute(): AudioRoute.Snapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AudioRoute.cached()) }
    DisposableEffect(context) {
        val am = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                AudioRoute.refresh(context, force = true) { snapshot = it }
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                AudioRoute.refresh(context, force = true) { snapshot = it }
            }
        }
        am?.registerAudioDeviceCallback(callback, null)
        onDispose { am?.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(context) {
        while (true) {
            AudioRoute.refresh(context) { snapshot = it }
            delay(2_000)
        }
    }
    return snapshot
}
