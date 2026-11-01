package dev.solon.bucklerhome

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One of Tyler Vigen's spurious correlations: two unrelated yearly series that move together. */
data class Correlation(
    val first: String,
    val second: String,
    /** Units from the data table, such as "Total votes"; may be blank. */
    val firstUnit: String,
    val secondUnit: String,
    val r: Double,
    val years: List<Int>,
    val firstValues: List<Double>,
    val secondValues: List<Double>,
)

/** Credit the stage shows: the charts are Tyler Vigen's, under CC BY 4.0. */
const val SPURIOUS_CREDIT = "Tyler Vigen · tylervigen.com · CC BY 4.0"

/**
 * Fetches a random correlation from tylervigen.com (no key, no API: its random page, read for the
 * title and the data table). The last few are kept on disk so the stage is not blank at start.
 */
object SpuriousCorrelations {
    private const val RANDOM = "https://www.tylervigen.com/spurious/random"
    const val KEPT = 12
    private val HTML = mapOf("Accept" to "text/html")

    /** Blocking: call off the main thread. Null when offline or the page has changed shape. */
    fun fetchRandom(): Correlation? {
        val res = runCatching { HttpJson.get(RANDOM, headers = HTML) }.getOrNull() ?: return null
        return if (res.code in 200..299) parse(res.body) else null
    }

    fun parse(html: String): Correlation? {
        val title = Regex("<title>(.*?) correlates with (.*?) \\(r=(-?[0-9.]+)\\)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val tableStart = html.indexOf("<!--Start Table of Data-->").takeIf { it >= 0 } ?: return null
        val tableEnd = html.indexOf("</table>", tableStart).takeIf { it >= 0 } ?: return null
        val rows = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html.substring(tableStart, tableEnd))
            .map { row -> Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { plain(it.groupValues[1]) }.toList() }
            .toList()
        if (rows.size < 3) return null
        val years = rows[0].drop(1).map { it.toIntOrNull() ?: return null }
        fun values(row: List<String>): List<Double>? = row.drop(1).map { it.replace(",", "").toDoubleOrNull() ?: return null }
        val a = values(rows[1]) ?: return null
        val b = values(rows[2]) ?: return null
        if (years.size < 2 || a.size != years.size || b.size != years.size) return null
        return Correlation(
            first = plain(title.groupValues[1]),
            second = plain(title.groupValues[2]),
            firstUnit = unit(rows[1][0]),
            secondUnit = unit(rows[2][0]),
            r = title.groupValues[3].toDoubleOrNull() ?: return null,
            years = years,
            firstValues = a,
            secondValues = b,
        )
    }

    /** "Votes in Texas (Total votes)" → "Total votes". */
    private fun unit(label: String): String = Regex("\\(([^()]*)\\)\\s*$").find(label)?.groupValues?.get(1).orEmpty()

    private fun plain(html: String): String = html.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun file(context: Context) = File(context.cacheDir, "spurious.json")

    fun saved(context: Context): List<Correlation> = runCatching {
        val arr = JSONArray(file(context).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun nums(name: String) = o.getJSONArray(name).let { a -> (0 until a.length()).map { a.getDouble(it) } }
            Correlation(
                o.getString("a"), o.getString("b"), o.optString("ua"), o.optString("ub"), o.getDouble("r"),
                nums("y").map { it.toInt() }, nums("va"), nums("vb"),
            )
        }
    }.getOrDefault(emptyList())

    /** When the kept list was last written, i.e. the last fetch; 0 if never. */
    fun savedAt(context: Context): Long = file(context).lastModified()

    fun save(context: Context, list: List<Correlation>) {
        val arr = JSONArray(
            list.takeLast(KEPT).map { c ->
                JSONObject().put("a", c.first).put("b", c.second).put("ua", c.firstUnit).put("ub", c.secondUnit)
                    .put("r", c.r).put("y", JSONArray(c.years)).put("va", JSONArray(c.firstValues)).put("vb", JSONArray(c.secondValues))
            },
        )
        runCatching { file(context).writeText(arr.toString()) }
    }
}
