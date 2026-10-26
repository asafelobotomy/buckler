package dev.solon.bucklerhome

import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Exit country of the connected VPN. Android does not report it, so this asks Cloudflare's
 * trace endpoint through the VPN [Network] and reads the `loc` line.
 */
object VpnCountry {
    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    fun lookup(network: Network): String? = runCatching {
        val conn = (network.openConnection(URL(TRACE_URL)) as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "buckler-home/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            parseTrace(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** `loc=GB` becomes UK; any other two-letter code is kept (IE, US, DE, …). */
    fun parseTrace(body: String): String? {
        val loc = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("loc=") }
            ?.substringAfter("loc=")
            ?.trim()
            ?.uppercase(Locale.US)
            ?: return null
        if (loc.length != 2 || loc.any { !it.isLetter() }) return null
        return if (loc == "GB") "UK" else loc
    }
}
