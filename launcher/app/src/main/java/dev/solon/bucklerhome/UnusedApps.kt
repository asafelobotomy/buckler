package dev.solon.bucklerhome

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** One app's use over the chosen period, from the TV's own records (nothing leaves the TV). */
data class AppUsage(
    val packageName: String,
    val label: String,
    val system: Boolean,
    /** Last time one of its screens was visible; 0 when the records never saw it. */
    val lastUsedAt: Long,
    val foregroundMs: Long,
    /** Times it started a foreground service (work while you were elsewhere), and for how long. */
    val backgroundRuns: Int,
    val backgroundRunMs: Long,
    /** Data it moved while not on screen. */
    val backgroundBytes: Long,
) {
    /** How much it does in the background, for ranking unused apps (busiest first). */
    val backgroundScore: Long get() = backgroundBytes / 100_000 + backgroundRunMs / 60_000 + backgroundRuns
}

/**
 * Apps not opened for a while, ranked by how much they still do in the background. Only apps
 * with a launcher entry are judged, and never Buckler, Settings, the Play Store, the keyboard,
 * Home, an enabled accessibility service, an app installed within the period, or one you kept.
 * Needs usage access (`buckler launcher grant-settings`).
 */
object UnusedApps {
    val PERIOD_DAYS = listOf(14, 30, 60, 90)
    private const val DAY_MS = 86_400_000L
    private const val PREFS = "bucklerhome_unused"
    private const val KEY_DAYS = "days"
    private const val KEY_NOTICE = "notice"
    private const val KEY_KEPT = "kept"
    private const val KEY_NOTICED_AT = "noticed_at"
    private const val KEY_NOTICED_LIST = "noticed_list"
    private const val KEY_EXPORTED_AT = "exported_at"
    const val EXPORT_FILE = "unused-apps.json"
    /** Never flagged: Buckler and the CLI's never list (profiles/never.yaml; a test keeps them equal). */
    private val ALWAYS_KEEP = setOf(
        "dev.solon.bucklerhome",
        "com.nvidia.NvCPLSvc",
        "com.nvidia.nvaudiosvc",
        "com.nvidia.avsync",
        "com.dolby.android.audio.service",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.android.keychain",
        "com.android.se",
        "com.android.tv.settings",
        "com.android.providers.tv",
        "com.android.systemui",
        "com.nvidia.shieldservice",
        "com.nvidia.packagemanagerservice",
        "com.nvidia.KeyComboOta",
        "com.google.android.tv.remote.service",
        "com.nvidia.blakepairing",
        "com.nvidia.ControllerMapper",
        "com.nvidia.remotelocator",
        "com.nvidia.irtuner",
        "com.nvidia.shieldtech.accessoryui",
        "com.nvidia.bluetooth.ShieldBluetoothManager",
        "com.nvidia.bluetooth.ps3usbpairer",
        "com.android.bluetooth",
        "com.google.android.tungsten.setupwraith",
        "com.nvidia.ota",
        "com.android.documentsui",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun days(context: Context): Int = prefs(context).getInt(KEY_DAYS, 30).takeIf { it in PERIOD_DAYS } ?: 30
    fun setDays(context: Context, days: Int) = prefs(context).edit().putInt(KEY_DAYS, days).apply()
    fun noticeOn(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTICE, true)
    fun setNoticeOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_NOTICE, on).apply()
    fun kept(context: Context): Set<String> = prefs(context).getStringSet(KEY_KEPT, null).orEmpty()
    fun keep(context: Context, pkg: String) = prefs(context).edit().putStringSet(KEY_KEPT, kept(context) + pkg).apply()
    fun clearKept(context: Context) = prefs(context).edit().remove(KEY_KEPT).apply()

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val op = AppOpsManager.OPSTR_GET_USAGE_STATS
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Unused apps, busiest in the background first. Empty without usage access. */
    fun scan(context: Context, days: Int = days(context), now: Long = System.currentTimeMillis()): List<AppUsage> {
        if (!hasAccess(context)) return emptyList()
        val since = now - days * DAY_MS
        val pm = context.packageManager
        val skip = ALWAYS_KEEP + kept(context) + roleHolders(context)
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        // Two years is as far back as Android keeps records.
        val lastSeen = usage.queryAndAggregateUsageStats(now - 730 * DAY_MS, now)
        val recent = usage.queryAndAggregateUsageStats(since, now)
        val runs = foregroundServiceRuns(usage, since, now)
        val bytes = backgroundBytesByUid(context, since, now)
        return launchablePackages(context).mapNotNull { pkg ->
            if (pkg in skip) return@mapNotNull null
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val app = info.applicationInfo ?: return@mapNotNull null
            if (!app.enabled || info.firstInstallTime > since) return@mapNotNull null
            val stats = lastSeen[pkg]
            // Visible is exact (Android 10+); "used" can also count background work.
            val last = when {
                stats == null -> 0L
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> stats.lastTimeVisible
                else -> stats.lastTimeUsed
            }
            if (last >= since) return@mapNotNull null
            AppUsage(
                packageName = pkg,
                label = app.loadLabel(pm).toString(),
                system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                lastUsedAt = last,
                foregroundMs = recent[pkg]?.totalTimeInForeground ?: 0L,
                backgroundRuns = runs[pkg]?.first ?: 0,
                backgroundRunMs = runs[pkg]?.second ?: 0L,
                backgroundBytes = bytes[app.uid] ?: 0L,
            )
        }.sortedWith(compareByDescending<AppUsage> { it.backgroundScore }.thenBy { it.lastUsedAt })
    }

    private fun launchablePackages(context: Context): Set<String> {
        val pm = context.packageManager
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }.toSet()
    }

    /** Home, the keyboard and accessibility services: removing them would break the TV. */
    private fun roleHolders(context: Context): Set<String> {
        val resolver = context.contentResolver
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
        val ime = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val a11y = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return (listOfNotNull(home, ime) + a11y.orEmpty().split(':'))
            .map { it.substringBefore('/') }.filter { it.isNotBlank() }.toSet()
    }

    /** Per package: foreground-service starts and their total running time. */
    private fun foregroundServiceRuns(usage: UsageStatsManager, since: Long, now: Long): Map<String, Pair<Int, Long>> {
        val runs = HashMap<String, Pair<Int, Long>>()
        val started = HashMap<String, Long>()
        val events = usage.queryEvents(since, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                    started[pkg] = event.timeStamp
                    val (count, ms) = runs[pkg] ?: (0 to 0L)
                    runs[pkg] = count + 1 to ms
                }
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> started.remove(pkg)?.let { start ->
                    val (count, ms) = runs.getValue(pkg)
                    runs[pkg] = count to ms + (event.timeStamp - start)
                }
            }
        }
        started.forEach { (pkg, start) -> runs[pkg] = runs.getValue(pkg).let { it.first to it.second + (now - start) } }
        return runs
    }

    /** Bytes each uid moved in the background over Wi-Fi and Ethernet. */
    private fun backgroundBytesByUid(context: Context, since: Long, now: Long): Map<Int, Long> {
        val stats = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val bytes = HashMap<Int, Long>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
            runCatching {
                stats.querySummary(type, null, since, now).use { summary ->
                    val bucket = NetworkStats.Bucket()
                    while (summary.hasNextBucket()) {
                        summary.getNextBucket(bucket)
                        if (bucket.state != NetworkStats.Bucket.STATE_FOREGROUND) {
                            bytes[bucket.uid] = (bytes[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                        }
                    }
                }
            }
        }
        return bytes
    }

    /**
     * Whether returning Home should scan at all: a scan walks months of usage records, so it runs
     * only when the weekly notice could show, or once a day to refresh the list for the CLI.
     */
    fun scanDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        val noticeDue = noticeOn(context) && now - p.getLong(KEY_NOTICED_AT, 0L) >= 7 * DAY_MS
        return noticeDue || now - p.getLong(KEY_EXPORTED_AT, 0L) >= DAY_MS
    }

    /** Writes the list where `buckler unused` reads it over adb. */
    fun export(context: Context, apps: List<AppUsage>, days: Int) {
        prefs(context).edit().putLong(KEY_EXPORTED_AT, System.currentTimeMillis()).apply()
        val dir = context.getExternalFilesDir(null) ?: return
        val json = JSONObject()
            .put("generated_at", Instant.now().toString())
            .put("days", days)
            .put("apps", JSONArray(apps.map { it.toJson() }))
        runCatching { File(dir, EXPORT_FILE).writeText(json.toString(1)) }
    }

    private fun AppUsage.toJson() = JSONObject()
        .put("package", packageName)
        .put("label", label)
        .put("system", system)
        .put("last_used", if (lastUsedAt > 0) Instant.ofEpochMilli(lastUsedAt).toString() else JSONObject.NULL)
        .put("foreground_ms", foregroundMs)
        .put("background_runs", backgroundRuns)
        .put("background_run_ms", backgroundRunMs)
        .put("background_bytes", backgroundBytes)

    /**
     * The Home notice: at most once a week, and only when the list differs from the one last
     * shown. Returns the text to show, or null.
     */
    fun noticeText(context: Context, apps: List<AppUsage>, days: Int, now: Long = System.currentTimeMillis()): String? {
        if (!noticeOn(context) || apps.isEmpty()) return null
        val p = prefs(context)
        val list = apps.joinToString(",") { it.packageName }
        if (now - p.getLong(KEY_NOTICED_AT, 0L) < 7 * DAY_MS || list == p.getString(KEY_NOTICED_LIST, null)) return null
        p.edit().putLong(KEY_NOTICED_AT, now).putString(KEY_NOTICED_LIST, list).apply()
        return noticeMessage(apps.map { it.label }, days)
    }

    internal fun noticeMessage(labels: List<String>, days: Int): String {
        val names = labels.take(3).joinToString(", ") + if (labels.size > 3) " and ${labels.size - 3} more" else ""
        val count = if (labels.size == 1) "1 app" else "${labels.size} apps"
        return "$count unused for $days days: $names. See Settings → Apps."
    }

    /** "never opened", "last opened 45 days ago". */
    fun describeLastUse(app: AppUsage, now: Long = System.currentTimeMillis()): String =
        if (app.lastUsedAt <= 0) "never opened" else "last opened ${(now - app.lastUsedAt) / DAY_MS} days ago"

    /** "ran in the background 12 times (40 min), 85 MB" — or null when it did nothing. */
    fun describeBackground(app: AppUsage): String? {
        val parts = mutableListOf<String>()
        if (app.backgroundRuns > 0) {
            val minutes = app.backgroundRunMs / 60_000
            val times = if (app.backgroundRuns == 1) "once" else "${app.backgroundRuns} times"
            parts += "ran in the background $times" + if (minutes > 0) " ($minutes min)" else ""
        }
        if (app.backgroundBytes >= 1_000_000) parts += "${app.backgroundBytes / 1_000_000} MB of data"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
