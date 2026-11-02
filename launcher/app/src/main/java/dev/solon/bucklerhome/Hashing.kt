package dev.solon.bucklerhome

import java.io.File
import java.security.MessageDigest

/** Lower-case hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/** Lower-case hex SHA-256 of [file], read in chunks. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
