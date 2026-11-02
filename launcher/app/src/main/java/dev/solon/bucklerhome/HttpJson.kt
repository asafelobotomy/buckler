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
    /** [etag] and [lastModified] let a caller ask next time for the body only if it changed (304). */
    data class Response(val code: Int, val body: String, val etag: String? = null, val lastModified: String? = null)

    /** How Buckler names itself to the servers it calls. */
    val USER_AGENT = "buckler-home/${BuildConfig.VERSION_NAME}"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response = read(open(url, "GET", headers, connectTimeoutMs, readTimeoutMs, accept = "application/json"))

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val body = form.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return post(url, body, "application/x-www-form-urlencoded", headers, connectTimeoutMs, readTimeoutMs)
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): Response = post(url, jsonBody, "application/json", headers, connectTimeoutMs, readTimeoutMs)

    /** Stream a download straight to [dest]; returns the HTTP status code. */
    fun downloadTo(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 120_000,
    ): Int {
        val conn = open(url, "GET", headers, connectTimeoutMs, readTimeoutMs, accept = null)
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

    private fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Response {
        val conn = open(url, "POST", mapOf("Content-Type" to contentType) + headers, connectTimeoutMs, readTimeoutMs, accept = "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return read(conn)
    }

    /** A connection with Buckler's defaults; [headers] are set last, so a caller's win. */
    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String?,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        accept?.let { setRequestProperty("Accept", it) }
        setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
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
        val etag = conn.getHeaderField("ETag")
        val lastModified = conn.getHeaderField("Last-Modified")
        conn.disconnect()
        return Response(code, text, etag, lastModified)
    }

    private const val MAX_BODY_CHARS = 2_000_000

    fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
