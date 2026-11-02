package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import org.json.JSONObject

/** Something the store has updated among the things this TV has, and what to do about it. */
data class StoreUpdate(val item: StoreItem, val kind: Kind) {
    enum class Kind {
        /** A built-in this Buckler already carries at the new revision: say so once. */
        UPDATED,

        /** A built-in whose new revision needs a newer Buckler ([StoreBuiltIn.requires]). */
        NEEDS_BUCKLER,

        /** A download (scheme, font, icon pack) with a newer revision to fetch. */
        DOWNLOAD,
    }
}

/**
 * Tells people when something they use from bucklerStore has been updated, and only that: never
 * about items they have not added or installed.
 *
 * Built-ins (widgets, screensavers, built-in schemes) ship in Buckler, so their news is "it was
 * updated" once this Buckler carries the new revision. `seen.json` keeps, for each added built-in,
 * the revision people have been told about; it starts as what this Buckler carries, so an update
 * that arrives with the store's first revisions is not announced as new. Downloads keep their own
 * revision in their saved files ([StoreLibrary]); an icon pack is compared by its APK version.
 */
object StoreUpdates {
    private const val TAG = "StoreUpdates"
    private const val PREFS = "buckler_store"

    private fun seenFile(context: Context) = File(StoreLibrary.root(context), "seen.json")

    // Pure rules, tested without a TV.

    /** Whether this Buckler ([appVersion]) carries [item] at its current revision. */
    fun carries(item: StoreBuiltIn, appVersion: String): Boolean = UpdateChecker.compareSemver(item.requires, appVersion) <= 0

    /** The first `seen.json`: every added built-in this Buckler carries, at its current revision. */
    fun firstSeen(added: List<StoreBuiltIn>, appVersion: String): Map<String, Int> =
        added.filter { carries(it, appVersion) }.associate { it.id to it.revision }

    /**
     * What an added built-in at [seen] (null: never recorded) has waiting. One with no record that
     * this Buckler carries is new to the list, not updated, so it has nothing to say (and is recorded).
     */
    fun builtInStatus(item: StoreBuiltIn, seen: Int?, appVersion: String): StoreUpdate.Kind? = when {
        seen != null && seen >= item.revision -> null
        !carries(item, appVersion) -> StoreUpdate.Kind.NEEDS_BUCKLER
        seen == null -> null
        else -> StoreUpdate.Kind.UPDATED
    }

    /** Whether a download installed at [installed] (null: not installed) is behind the index's [latest]. */
    fun downloadBehind(installed: Int?, latest: Int): Boolean = installed != null && installed < latest

    // On the TV.

    /** What is waiting for this TV. Records new and first-seen built-ins in `seen.json` as it goes. Blocking. */
    @Synchronized
    fun pending(context: Context, catalog: StoreCatalog = StoreIndex.cached(context)): List<StoreUpdate> {
        StoreLibrary.ensureLoaded(context)
        val app = BuildConfig.VERSION_NAME
        val added = StoreLibrary.addedBuiltIns(catalog)
        val seen = readSeen(context)?.toMutableMap() ?: firstSeen(added, app).toMutableMap().also { writeSeen(context, it) }
        var changed = false
        val out = ArrayList<StoreUpdate>()
        for (item in added) {
            val status = builtInStatus(item, seen[item.id], app)
            if (status == null && seen[item.id] == null && carries(item, app)) {
                seen[item.id] = item.revision
                changed = true
            }
            if (status != null) out += StoreUpdate(item, status)
        }
        if (changed) writeSeen(context, seen)
        catalog.themes.filter { downloadBehind(StoreLibrary.themeRevision(it.id), it.revision) }.forEach { out += StoreUpdate(it, StoreUpdate.Kind.DOWNLOAD) }
        catalog.fonts.filter { downloadBehind(StoreLibrary.fontRevision(it.id), it.revision) }.forEach { out += StoreUpdate(it, StoreUpdate.Kind.DOWNLOAD) }
        catalog.iconPacks.filter { iconPackBehind(context, it) }.forEach { out += StoreUpdate(it, StoreUpdate.Kind.DOWNLOAD) }
        return out
    }

    /** An installed icon pack whose APK version is not the one the store lists. */
    fun iconPackBehind(context: Context, pack: StoreIconPack): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(pack.packageName, 0).versionName }
            .getOrElse { e -> if (e is PackageManager.NameNotFoundException) return false else null }
        return installed != null && pack.version.isNotBlank() && installed != pack.version
    }

    /** The updates people have not been told about yet; [markTold] records them. */
    fun untold(context: Context, updates: List<StoreUpdate>): List<StoreUpdate> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return updates.filter { u ->
            when (u.kind) {
                StoreUpdate.Kind.UPDATED -> true
                StoreUpdate.Kind.DOWNLOAD -> prefs.getInt("told_${u.item.id}", 0) < u.item.revision
                StoreUpdate.Kind.NEEDS_BUCKLER -> false
            }
        }
    }

    /** Built-ins told about count as seen; downloads are remembered as told until updated again. */
    @Synchronized
    fun markTold(context: Context, updates: List<StoreUpdate>) {
        val seen = readSeen(context).orEmpty().toMutableMap()
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (u in updates) {
            when (u.kind) {
                StoreUpdate.Kind.UPDATED -> seen[u.item.id] = u.item.revision
                StoreUpdate.Kind.DOWNLOAD -> edit.putInt("told_${u.item.id}", u.item.revision)
                StoreUpdate.Kind.NEEDS_BUCKLER -> Unit
            }
        }
        edit.apply()
        writeSeen(context, seen)
    }

    /** A built-in just added: it is new to this TV, not updated, so record what this Buckler carries. */
    @Synchronized
    fun recordAdded(context: Context, id: String) {
        val item = StoreIndex.cached(context).builtIns.firstOrNull { it.id == id } ?: return
        if (!carries(item, BuildConfig.VERSION_NAME)) return
        val seen = readSeen(context) ?: return // No file yet: the first check records every added item.
        if ((seen[id] ?: 0) >= item.revision) return
        writeSeen(context, seen + (id to item.revision))
    }

    /** One line for Home about [updates], or null when there is nothing to say. */
    fun noticeText(updates: List<StoreUpdate>): String? = when {
        updates.isEmpty() -> null
        updates.size == 1 -> updates.single().let { u ->
            val what = u.item.changes.ifBlank { null }
            when (u.kind) {
                StoreUpdate.Kind.DOWNLOAD -> "${u.item.label} has an update in Settings → bucklerStore" + (what?.let { ": $it" } ?: ".")
                else -> "${u.item.label} was updated" + (what?.let { ": $it" } ?: ".")
            }
        }
        else -> "${updates.joinToString(", ") { it.item.label }} were updated. See Settings → bucklerStore."
    }

    private fun readSeen(context: Context): Map<String, Int>? {
        val file = seenFile(context)
        if (!file.isFile) return null
        val doc = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return doc.keys().asSequence().associateWith { doc.optInt(it, 1) }
    }

    private fun writeSeen(context: Context, seen: Map<String, Int>) {
        runCatching {
            StoreLibrary.root(context).mkdirs()
            val doc = JSONObject()
            seen.toSortedMap().forEach { (id, rev) -> doc.put(id, rev) }
            seenFile(context).writeText(doc.toString(2))
        }.onFailure { Log.w(TAG, "Could not write seen.json", it) }
    }
}
