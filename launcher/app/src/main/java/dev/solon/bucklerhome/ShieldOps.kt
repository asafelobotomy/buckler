package dev.solon.bucklerhome

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One clean-up list from the CLI's profiles (privacy-core, minimal, speech, extras). */
data class ShieldProfile(val name: String, val description: String, val packages: List<String>)

/**
 * The profiles and safety lists `buckler setup` uses, bundled as assets/shield_profiles.json by
 * scripts/gen_profiles.py, so the TV applies exactly the same lists under the same rules.
 */
class ShieldData(
    val profiles: Map<String, ShieldProfile>,
    val never: Set<String>,
    val stockLaunchers: Set<String>,
    val appops: List<String>,
    val permissions: List<String>,
) {
    companion object {
        @Volatile private var cached: ShieldData? = null

        fun load(context: Context): ShieldData = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("shield_profiles.json").bufferedReader().use { it.readText() })
                .also { cached = it }
        }

        internal fun parse(text: String): ShieldData {
            val json = JSONObject(text)
            fun JSONArray.strings() = (0 until length()).map { getString(it) }
            val profiles = json.getJSONObject("profiles").let { all ->
                all.keys().asSequence().associateWith { name ->
                    val p = all.getJSONObject(name)
                    ShieldProfile(name, p.optString("description"), p.getJSONArray("packages").strings())
                }
            }
            return ShieldData(
                profiles = profiles,
                never = json.getJSONArray("never").strings().toSet(),
                stockLaunchers = json.getJSONArray("stock_launchers").strings().toSet(),
                appops = json.getJSONArray("appops").strings(),
                permissions = json.getJSONArray("permissions").strings(),
            )
        }
    }
}

/** What applying a list did, as `buckler apply` reports it. */
data class ShieldApply(
    val changed: List<String> = emptyList(),
    val already: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

/**
 * The SHIELD changes `buckler setup` makes, made from the TV through [AdbSelf]. The rules match
 * the CLI's (ops.py, policy.py): never-list packages are refused; a stock launcher is disabled
 * only once Buckler is Home; installed, enabled packages alone are changed, each one recorded in
 * [ShieldJournal] so [restore] can bring it back.
 */
class ShieldOps(private val context: Context, private val adb: AdbSelf) {
    private val data = ShieldData.load(context)

    /**
     * Buckler's one-time grants (as `buckler launcher grant-settings`), only those it lacks:
     * returns what failed. Android restarts an app whenever its install permission changes, so
     * that one goes last; check [restartsOnGrant] first to warn about it.
     */
    fun grantBuckler(): List<String> {
        val pkg = context.packageName
        val failed = mutableListOf<String>()
        for (permission in missingPermissions(context, data)) {
            if (!adb.shell("pm grant $pkg $permission").ok) failed += permission
        }
        for (appop in missingAppops(context, data).sortedBy { it == INSTALL_APPOP }) {
            if (!adb.shell("appops set $pkg $appop allow").ok) failed += appop
        }
        return failed
    }

    /** The current Home app's package, as Android resolves the Home button. */
    fun homePackage(): String? = adb.shell(
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    ).output.lines().lastOrNull { "/" in it }?.substringBefore('/')?.trim()

    /** Makes Buckler what the Home button opens. The stock launcher stays installed as a fallback. */
    fun setHome(): Boolean {
        adb.shell("cmd package set-home-activity ${context.packageName}/.MainActivity")
        return homePackage() == context.packageName
    }

    /** Disables [packages] for the TV's user, journaled with [reason]. */
    fun disable(packages: Collection<String>, reason: String): ShieldApply {
        val installed = packageSet("pm list packages")
        val disabled = packageSet("pm list packages -d")
        val home = homePackage()
        val changed = mutableListOf<String>()
        val already = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (pkg in packages.distinct()) {
            when {
                !isPackageName(pkg) || pkg in data.never || pkg == context.packageName -> blocked += pkg
                // Never leave the TV without a Home: a launcher goes only once Buckler has taken over.
                (pkg in data.stockLaunchers || pkg == home) && home != context.packageName -> blocked += pkg
                pkg !in installed -> missing += pkg
                pkg in disabled -> already += pkg
                adb.shell("pm disable-user --user 0 $pkg").ok -> {
                    ShieldJournal.add(context, pkg, reason)
                    adb.shell("am force-stop $pkg")
                    changed += pkg
                }
                else -> failed += pkg
            }
        }
        return ShieldApply(changed, already, missing, blocked, failed)
    }

    /** Re-enables journaled packages ([packages], or every one), and forgets them. */
    fun restore(packages: Collection<String>? = null): List<String> {
        val wanted = packages?.toSet()
        val restored = mutableListOf<String>()
        for (entry in ShieldJournal.all(context)) {
            if (wanted != null && entry.pkg !in wanted) continue
            if (!isPackageName(entry.pkg)) continue
            if (adb.shell("pm enable ${entry.pkg}").ok) {
                ShieldJournal.remove(context, entry.pkg)
                restored += entry.pkg
            }
        }
        return restored
    }

    /**
     * Turns [packages] back on, whoever turned them off (the CLI or the TV), but only apps from the
     * clean-up lists: the wizard never enables anything else. Returns those enabled.
     */
    fun enable(packages: Collection<String>): List<String> {
        val listed = data.profiles.values.flatMap { it.packages }.toSet()
        return packages.filter { pkg ->
            pkg in listed && isPackageName(pkg) && adb.shell("pm enable $pkg").ok
        }.onEach { ShieldJournal.remove(context, it) }
    }

    /** Packages currently disabled for the TV's user. */
    fun disabledPackages(): Set<String> = packageSet("pm list packages -d")

    private fun packageSet(command: String): Set<String> =
        adb.shell(command).output.lines().mapNotNull { it.trim().removePrefix("package:").takeIf(::isPackageName) }.toSet()

    companion object {
        private const val INSTALL_APPOP = "REQUEST_INSTALL_PACKAGES"

        fun missingPermissions(context: Context, data: ShieldData = ShieldData.load(context)): List<String> =
            data.permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        fun missingAppops(context: Context, data: ShieldData = ShieldData.load(context)): List<String> {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return data.appops
            return data.appops.filter { op ->
                val name = "android:${op.lowercase()}"
                val mode = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ops.unsafeCheckOpNoThrow(name, Process.myUid(), context.packageName)
                    } else {
                        @Suppress("DEPRECATION")
                        ops.checkOpNoThrow(name, Process.myUid(), context.packageName)
                    }
                }.getOrDefault(AppOpsManager.MODE_ERRORED)
                mode != AppOpsManager.MODE_ALLOWED
            }
        }

        /** Granting would restart Buckler: its install permission is among what is missing. */
        fun restartsOnGrant(context: Context): Boolean = INSTALL_APPOP in missingAppops(context)

        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /** Only well-formed package names ever reach a shell command. */
        fun isPackageName(value: String): Boolean = PACKAGE.matches(value)
    }
}

/**
 * Every app Buckler disabled from the TV and why, kept in its private files so a later run, or
 * "Restore everything", can enable exactly those again. Mirrors the CLI's journals.
 */
object ShieldJournal {
    data class Entry(val pkg: String, val reason: String, val at: Long)

    private fun file(context: Context) = File(context.filesDir, "shield_journal.json")

    @Synchronized
    fun all(context: Context): List<Entry> = runCatching {
        val array = JSONArray(file(context).readText())
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Entry(it.getString("package"), it.optString("reason"), it.optLong("at")) }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, pkg: String, reason: String) {
        save(context, all(context).filterNot { it.pkg == pkg } + Entry(pkg, reason, System.currentTimeMillis()))
    }

    @Synchronized
    fun remove(context: Context, pkg: String) {
        save(context, all(context).filterNot { it.pkg == pkg })
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("package", it.pkg).put("reason", it.reason).put("at", it.at)) }
        val target = file(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(array.toString(2))
        tmp.renameTo(target)
    }
}
