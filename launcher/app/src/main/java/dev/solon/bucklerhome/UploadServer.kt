package dev.solon.bucklerhome

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny LAN-only HTTP server so a phone can send photos to the screensaver folder.
 *
 * It only runs while the upload dialog is open, and every URL carries a random token, so
 * nothing else on the network can write to the TV.
 */
class UploadServer(private val context: Context) {
    val token: String = randomToken()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var received: Int = 0
        private set

    private var server: ServerSocket? = null

    fun start(): Boolean {
        for (candidate in 8080..8099) {
            val socket = runCatching { ServerSocket(candidate) }.getOrNull() ?: continue
            server = socket
            port = candidate
            thread(name = "buckler-upload", isDaemon = true) { acceptLoop(socket) }
            return true
        }
        return false
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun url(): String? = localIpv4()?.let { "http://$it:$port/$token/" }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            thread(name = "buckler-upload-conn", isDaemon = true) {
                runCatching { client.soTimeout = 30_000; handle(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        var contentLength = 0L
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val base = "/$token/"
        when {
            method == "GET" && (path == base || path == "/$token") ->
                respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && path == "${base}upload" -> receive(input, out, query, contentLength)
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun receive(input: InputStream, out: OutputStream, query: String, length: Long) {
        val rawName = query.split('&').firstOrNull { it.startsWith("name=") }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .orEmpty()
        val name = sanitize(rawName)
        when {
            !ScreensaverImages.isImageName(name) -> respond(out, 415, "text/plain", "Only jpg, png or webp")
            length <= 0 || length > MAX_BYTES -> respond(out, 413, "text/plain", "File too large")
            else -> {
                val dir = ScreensaverImages.uploadDir(context)
                val dest = uniqueFile(dir, name)
                val tmp = File(dir, dest.name + ".part")
                var remaining = length
                tmp.outputStream().use { file ->
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        file.write(buf, 0, n)
                        remaining -= n
                    }
                }
                if (remaining == 0L && tmp.renameTo(dest)) {
                    received++
                    respond(out, 200, "text/plain", "ok")
                } else {
                    tmp.delete()
                    respond(out, 400, "text/plain", "Incomplete upload")
                }
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Unsupported Media Type"
        }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val MAX_BYTES = 60L * 1024 * 1024
        private const val PAGE = "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>Buckler photos</title><body style=\"font-family:sans-serif;background:#0b0e14;color:#e8ecf2;" +
            "padding:24px\"><h2>Send photos to your TV</h2><p>Choose images, then tap Upload.</p>" +
            "<input id=f type=file accept=\"image/*\" multiple> <button onclick=up()>Upload</button><pre id=log></pre>" +
            "<script>async function up(){const fs=document.getElementById('f').files;let ok=0;" +
            "const log=document.getElementById('log');for(const f of fs){log.textContent+='Uploading '+f.name+'...\\n';" +
            "try{const r=await fetch('upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});" +
            "log.textContent+=(r.ok?'OK ':'FAILED ')+f.name+'\\n';if(r.ok)ok++}catch(e){log.textContent+='FAILED '+f.name+'\\n'}}" +
            "log.textContent+='Done: '+ok+'/'+fs.length+'\\n'}</script>"

        private fun randomToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun sanitize(raw: String): String =
            raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trimStart('.')
                .take(80)

        fun uniqueFile(dir: File, name: String): File {
            var candidate = File(dir, name)
            var n = 1
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            while (candidate.exists()) {
                candidate = File(dir, "$stem-$n.$ext")
                n++
            }
            return candidate
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
            return null
        }

        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
