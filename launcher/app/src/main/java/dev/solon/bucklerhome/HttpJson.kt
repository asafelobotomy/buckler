package dev.solon.bucklerhome

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object HttpJson {
    data class Response(val code: Int, val body: String)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return read(conn)
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return read(conn)
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
        return read(conn)
    }

    /** Stream a download straight to [dest]; returns the HTTP status code. */
    fun downloadTo(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 120_000,
    ): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    fun parseObject(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun read(conn: HttpURLConnection): Response {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        // Feeds, pages and API replies are far smaller; a huge or endless body must not run the
        // launcher out of memory, so stop reading at the cap.
        val text = stream?.use { inp ->
            val reader = BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8))
            val out = StringBuilder()
            val buffer = CharArray(8_192)
            while (out.length < MAX_BODY_CHARS) {
                val n = reader.read(buffer, 0, minOf(buffer.size, MAX_BODY_CHARS - out.length))
                if (n < 0) break
                out.appendRange(buffer, 0, n)
            }
            out.toString()
        }.orEmpty()
        conn.disconnect()
        return Response(code, text)
    }

    private const val MAX_BODY_CHARS = 2_000_000

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
