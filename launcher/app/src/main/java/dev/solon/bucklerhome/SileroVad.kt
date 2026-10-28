package dev.solon.bucklerhome

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Silero VAD v5 (MIT, github.com/snakers4/silero-vad): the chance that a 32 ms frame of 16 kHz
 * audio holds a voice, singing over music included. Runs on ONNX Runtime, one thread.
 */
class SileroVad(model: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        model.path,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        },
    )
    private val state = FloatArray(2 * 128)
    private val input = FloatArray(CONTEXT + FRAME)
    private val rate = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf())

    fun reset() {
        state.fill(0f)
        input.fill(0f)
    }

    /** Probability (0..1) that [frame] ([FRAME] samples, -1..1) is voice. */
    fun probability(frame: FloatArray): Float {
        // The model reads the last 64 samples of the previous frame as context.
        System.arraycopy(input, FRAME, input, 0, CONTEXT)
        System.arraycopy(frame, 0, input, CONTEXT, FRAME)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                session.run(mapOf("input" to x, "state" to s, "sr" to rate)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val next = out.get(1).value as Array<Array<FloatArray>>
                    for (layer in 0 until 2) System.arraycopy(next[layer][0], 0, state, layer * 128, 128)
                    @Suppress("UNCHECKED_CAST")
                    return (out.get(0).value as Array<FloatArray>)[0][0]
                }
            }
        }
    }

    override fun close() {
        rate.close()
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 512
        private const val CONTEXT = 64
        private const val URL =
            "https://raw.githubusercontent.com/snakers4/silero-vad/v5.1.2/src/silero_vad/data/silero_vad.onnx"
        private const val SHA256 = "2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"

        fun modelFile(context: Context): File = File(context.filesDir, "models/silero_vad_v5.onnx")

        fun hasModel(context: Context): Boolean = modelFile(context).let { it.isFile && sha256(it) == SHA256 }

        /** Downloads and verifies the model; blocking. False when offline or the file is not the pinned one. */
        fun ensureModel(context: Context): Boolean {
            if (hasModel(context)) return true
            val dest = modelFile(context).also { it.parentFile?.mkdirs() }
            val part = File(dest.path + ".part")
            val code = runCatching { HttpJson.downloadTo(URL, part) }.getOrDefault(-1)
            if (code !in 200..299 || sha256(part) != SHA256 || !part.renameTo(dest)) {
                part.delete()
                return false
            }
            return true
        }

        private fun sha256(file: File): String = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }
}
