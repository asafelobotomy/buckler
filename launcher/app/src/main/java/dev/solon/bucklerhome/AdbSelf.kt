package dev.solon.bucklerhome

import android.content.Context
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * A connection to this TV's own debugging service (Network debugging, `127.0.0.1:5555`), so
 * Buckler can make the changes `buckler setup` makes from a computer: disable apps, grant its own
 * permissions, set Home. It speaks the plain adb protocol: connect, prove who it is with its own
 * RSA key, then open `shell:` streams. The first time, the TV asks "Allow debugging?" for Buckler's
 * key; with "Always allow" ticked it does not ask again. The key never leaves the TV.
 *
 * Blocking: use it off the main thread. One connection runs any number of [shell] commands.
 */
class AdbSelf private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private var nextLocalId = 1

    /** Output of a shell command and its exit code. */
    data class Result(val output: String, val exit: Int) {
        val ok: Boolean get() = exit == 0
    }

    sealed class Failure(message: String) : IOException(message) {
        /** Network debugging is off (nothing listens on the port). */
        class NotListening : Failure("Network debugging is off")

        /** The TV did not allow Buckler's key: declined, or no one answered in time. */
        class NotAllowed : Failure("The TV did not allow debugging for Buckler")
    }

    /**
     * Runs [command] in a shell as the `shell` user and waits for it to end. The exit code comes
     * from a marker echoed after the command, since the plain shell stream does not carry one.
     */
    fun shell(command: String): Result {
        val local = nextLocalId++
        send(OPEN, local, 0, "shell:$command; echo \"$EXIT_MARK\$?\"\u0000".toByteArray())
        var remote = 0
        val out = StringBuilder()
        while (true) {
            val m = read()
            when (m.command) {
                OKAY -> if (m.arg1 == local) remote = m.arg0
                WRTE -> if (m.arg1 == local) {
                    remote = m.arg0
                    out.append(String(m.data, Charsets.UTF_8))
                    send(OKAY, local, remote, ByteArray(0))
                }
                CLSE -> if (m.arg1 == local || remote == 0) {
                    if (remote != 0) send(CLSE, local, remote, ByteArray(0))
                    break
                }
            }
        }
        val text = out.toString()
        val at = text.lastIndexOf(EXIT_MARK)
        if (at < 0) return Result(text.trimEnd(), -1)
        val exit = text.substring(at + EXIT_MARK.length).trim().toIntOrNull() ?: -1
        return Result(text.substring(0, at).trimEnd(), exit)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xFF }).putInt(command.inv())
        output.write(header.array())
        output.write(data)
        output.flush()
    }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("adb: bad message length $length")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val PORT = 5555
        private const val EXIT_MARK = "__buckler_exit:"

        /** How long the TV's "Allow debugging?" prompt may wait for an answer. */
        private const val APPROVAL_MS = 90_000

        /** SHA-1 DigestInfo: adbd checks the token's signature as if the token were a SHA-1 hash. */
        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /**
         * Connects and signs in. [onApprovalNeeded] runs (on this thread) when the TV is showing its
         * "Allow debugging?" prompt, so the caller can say so on screen.
         */
        fun connect(context: Context, onApprovalNeeded: () -> Unit = {}): AdbSelf {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 3_000)
            } catch (e: IOException) {
                socket.close()
                throw Failure.NotListening()
            }
            socket.soTimeout = 15_000
            val adb = AdbSelf(socket)
            try {
                adb.handshake(context, onApprovalNeeded)
            } catch (e: IOException) {
                adb.close()
                throw if (e is Failure) e else Failure.NotAllowed()
            }
            socket.soTimeout = 60_000
            return adb
        }

        private fun AdbSelf.handshake(context: Context, onApprovalNeeded: () -> Unit) {
            val keys = AdbKey.load(context)
            send(CNXN, VERSION, MAX_PAYLOAD, "host::buckler\u0000".toByteArray())
            var signed = false
            while (true) {
                val m = read()
                when (m.command) {
                    CNXN -> return
                    AUTH -> if (m.arg0 == AUTH_TOKEN) {
                        if (!signed) {
                            // Known key: the signature is enough.
                            signed = true
                            send(AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                        } else {
                            // Unknown key: offer it, and wait while the TV asks.
                            send(AUTH, AUTH_RSAPUBLICKEY, 0, AdbKey.publicKeyBlob(keys))
                            socket.soTimeout = APPROVAL_MS
                            onApprovalNeeded()
                        }
                    }
                }
            }
        }

        private fun sign(keys: KeyPair, token: ByteArray): ByteArray =
            Signature.getInstance("NONEwithRSA").run {
                initSign(keys.private)
                update(SHA1_PREFIX + token)
                sign()
            }
    }
}

/** Buckler's own 2048-bit adb key, made once and kept in its private, encrypted preferences. */
private object AdbKey {
    private const val KEY_PRIVATE = "adb_key_private"
    private const val KEY_PUBLIC = "adb_key_public"

    fun load(context: Context): KeyPair {
        val prefs = SecurePrefs.get(context)
        val private = prefs.getString(KEY_PRIVATE, null)
        val public = prefs.getString(KEY_PUBLIC, null)
        if (private != null && public != null) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(Base64.decode(public, Base64.NO_WRAP))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(private, Base64.NO_WRAP))),
                )
            }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }

    /**
     * The public key in adbd's own format: a little-endian struct (word count, n0inv, modulus,
     * R² mod n, exponent), base64, then a name, as `adb` writes it to `adbkey.pub`.
     */
    fun publicKeyBlob(keys: KeyPair): ByteArray {
        val key = keys.public as RSAPublicKey
        val words = 64
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32)
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv.toInt())
        putWords(b, n, words)
        putWords(b, rr, words)
        b.putInt(key.publicExponent.toInt())
        val text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " buckler@shield\u0000"
        return text.toByteArray()
    }

    private fun putWords(b: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var v = value
        repeat(words) {
            b.putInt(v.and(mask).toInt())
            v = v.shiftRight(32)
        }
    }
}
