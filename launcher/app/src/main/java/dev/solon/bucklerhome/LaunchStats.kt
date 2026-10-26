package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local launch counts / recents — no network. */
object LaunchStats {
    private const val PREFS = "bucklerhome"
    private const val KEY_RECENTS = "launch_recents"
    private const val KEY_COUNTS = "launch_counts"
    private const val MAX_RECENTS = 24

    fun recordLaunch(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recents = loadRecents(context).toMutableList()
        recents.remove(packageName)
        recents.add(0, packageName)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)

        val counts = loadCounts(context).toMutableMap()
        counts[packageName] = (counts[packageName] ?: 0) + 1

        val countsJson = JSONObject()
        for ((pkg, count) in counts) {
            countsJson.put(pkg, count)
        }
        prefs.edit()
            .putString(KEY_RECENTS, JSONArray(recents).toString())
            .putString(KEY_COUNTS, countsJson.toString())
            .apply()
    }

    fun loadRecents(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrDefault(emptyList())
    }

    fun loadCounts(context: Context): Map<String, Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COUNTS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    put(key, obj.optInt(key, 0))
                }
            }
        }.getOrDefault(emptyMap())
    }
}
