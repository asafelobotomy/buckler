package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class GitHubRelease(
    val tag: String,
    val name: String,
    val body: String,
    val apkUrl: String?,
    val apkName: String?,
    /** API asset URL (works with PAT on private repos). */
    val apkApiUrl: String? = null,
) {
    fun downloadUrl(preferApi: Boolean): String? =
        if (preferApi) apkApiUrl ?: apkUrl else apkUrl ?: apkApiUrl
}

sealed class UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data object Snoozed : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdatePrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_SNOOZE_UNTIL = "update_snooze_until"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_IGNORED_TAG = "update_ignored_tag"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snoozeUntil(context: Context): Long =
        prefs(context).getLong(KEY_SNOOZE_UNTIL, 0L)

    fun setSnoozeDays(context: Context, days: Int) {
        val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
        prefs(context).edit().putLong(KEY_SNOOZE_UNTIL, until).apply()
    }

    fun clearSnooze(context: Context) {
        prefs(context).edit().remove(KEY_SNOOZE_UNTIL).apply()
    }

    fun lastCheck(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheck(context: Context, millis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, millis).apply()
    }

    fun ignoredTag(context: Context): String? =
        prefs(context).getString(KEY_IGNORED_TAG, null)

    fun setIgnoredTag(context: Context, tag: String?) {
        prefs(context).edit().putString(KEY_IGNORED_TAG, tag).apply()
    }

    fun isSnoozed(context: Context): Boolean =
        System.currentTimeMillis() < snoozeUntil(context)
}

object UpdateChecker {
    private const val THROTTLE_MS = 12L * 60L * 60L * 1000L

    fun shouldAutoCheck(context: Context): Boolean {
        if (UpdatePrefs.isSnoozed(context)) return false
        val last = UpdatePrefs.lastCheck(context)
        return System.currentTimeMillis() - last >= THROTTLE_MS
    }

    fun check(context: Context, force: Boolean = false): UpdateCheckResult {
        if (!force && UpdatePrefs.isSnoozed(context)) return UpdateCheckResult.Snoozed
        UpdatePrefs.setLastCheck(context)
        val response = runCatching {
            HttpJson.get(
                BuildConfig.GITHUB_RELEASES_URL,
                headers = CoverAccounts.githubAuthHeaders(context),
            )
        }.getOrElse {
            Log.w("UpdateChecker", "Release check failed", it)
            return UpdateCheckResult.Error(it.message ?: "Network error")
        }
        if (response.code == 404) {
            // No releases, or private repo without a GitHub token.
            val hint = if (CoverAccounts.githubToken(context).isNullOrBlank()) {
                "No release (or private repo — add a GitHub token in Settings → Updates)"
            } else {
                "No GitHub releases yet — publish v${BuildConfig.VERSION_NAME} with an APK"
            }
            return if (force) {
                UpdateCheckResult.Error(hint)
            } else {
                UpdateCheckResult.UpToDate
            }
        }
        if (response.code == 401 || response.code == 403) {
            return UpdateCheckResult.Error("GitHub auth failed — check token in Settings → Updates")
        }
        if (response.code !in 200..299) {
            return UpdateCheckResult.Error("GitHub HTTP ${response.code}")
        }
        val json = HttpJson.parseObject(response.body)
            ?: return UpdateCheckResult.Error("Bad release JSON")
        val release = parseRelease(json)
            ?: return UpdateCheckResult.Error("No release data")
        val ignored = UpdatePrefs.ignoredTag(context)
        if (ignored != null && ignored == release.tag) {
            return UpdateCheckResult.UpToDate
        }
        val remote = normalizeVersion(release.tag)
        val local = normalizeVersion(BuildConfig.VERSION_NAME)
        return if (compareSemver(remote, local) > 0) {
            UpdateCheckResult.Available(release)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    fun parseRelease(json: JSONObject): GitHubRelease? {
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val apk = pickApkAsset(assets) ?: return GitHubRelease(
            tag = tag,
            name = json.optString("name").ifBlank { tag },
            body = json.optString("body"),
            apkUrl = null,
            apkName = null,
        )
        return GitHubRelease(
            tag = tag,
            name = json.optString("name").ifBlank { tag },
            body = json.optString("body"),
            apkUrl = apk.browserUrl,
            apkName = apk.name,
            apkApiUrl = apk.apiUrl,
        )
    }

    private data class ApkAsset(val name: String, val browserUrl: String?, val apiUrl: String?)

    private fun pickApkAsset(assets: JSONArray): ApkAsset? {
        val candidates = mutableListOf<ApkAsset>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val browser = a.optString("browser_download_url").takeIf { it.isNotBlank() }
            val id = a.optLong("id", 0L)
            val api = if (id > 0L) {
                "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/assets/$id"
            } else {
                null
            }
            if (browser == null && api == null) continue
            candidates += ApkAsset(name, browser, api)
        }
        fun score(name: String): Int = when {
            name.contains("buckler-home", ignoreCase = true) -> 0
            name.contains("bucklerhome", ignoreCase = true) -> 1
            name.contains("app-release", ignoreCase = true) -> 2
            else -> 3
        }
        return candidates.minByOrNull { score(it.name) }
    }

    /** Strip a leading `v` and build metadata; keeps any `-prerelease` suffix. */
    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore("+")

    /** Positive if [a] is newer than [b]. A release outranks its own pre-release (1.0.0 > 1.0.0-rc1). */
    fun compareSemver(a: String, b: String): Int {
        val (coreA, preA) = splitPre(a)
        val (coreB, preB) = splitPre(b)
        val pa = coreA.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = coreB.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val ai = pa.getOrElse(i) { 0 }
            val bi = pb.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return when {
            preA == preB -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    private fun splitPre(version: String): Pair<String, String?> =
        version.substringBefore("-") to version.substringAfter("-", "").ifEmpty { null }
}
