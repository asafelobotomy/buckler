package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class CoverCandidate(
    val id: String,
    val label: String,
    val source: String,
    /** Value persisted in CoverStore when selected. */
    val storeValue: String,
    val previewUrl: String? = null,
    val previewDrawable: Drawable? = null,
)

object CoverResolver {
    /** Resolve covers for many items with one read of the override + curated stores. */
    fun applyAll(context: Context, items: List<ShelfItem>, layout: CoverLayout): List<ShelfItem> {
        val overrides = CoverStore.all(context)
        val curated = CuratedCovers.entries(context)
        return items.map { applyOne(context, it, layout, overrides, curated) }
    }

    private fun applyOne(
        context: Context,
        item: ShelfItem,
        layout: CoverLayout,
        overrides: Map<String, String>,
        curated: Map<String, CuratedCoverEntry>,
    ): ShelfItem {
        val pkg = item.packageName ?: return item
        // Episode and feed art already has its own picture; only app tiles follow the layout.
        if (item.imageUrl != null || item.imageUri != null) return item
        val stored = overrides[pkg]?.takeIf { it.isNotBlank() }
        val picked = CoverStore.parseUrl(stored)
        if (stored != null && picked != null) {
            return item.copy(
                imageUrl = picked,
                imageUri = null,
                banner = null,
                coverFit = stored.startsWith("curated:"),
            )
        }
        if (CoverStore.isLocalIcon(stored)) return iconArt(item)
        if (CoverStore.isLocalBanner(stored)) {
            return leanbackBanner(context, pkg)?.let { bannerArt(item, it) } ?: iconArt(item)
        }
        return automaticArt(context, item, layout, curated[pkg])
    }

    /**
     * Banner layout uses the app's TV banner, poster layout a real vertical cover, icon layout
     * the launcher icon. A missing picture falls back to the icon — never a different shape's art.
     */
    private fun automaticArt(
        context: Context,
        item: ShelfItem,
        layout: CoverLayout,
        entry: CuratedCoverEntry?,
    ): ShelfItem {
        val pkg = item.packageName
        if (layout == CoverLayout.BANNER && pkg != null) {
            leanbackBanner(context, pkg)?.let { return bannerArt(item, it) }
        }
        entry?.urlFor(layout)?.let { url ->
            return item.copy(imageUrl = url, imageUri = null, banner = null, coverFit = false)
        }
        return iconArt(item)
    }

    private fun bannerArt(item: ShelfItem, banner: Drawable): ShelfItem =
        item.copy(imageUrl = null, imageUri = null, banner = banner, coverFit = false)

    private fun iconArt(item: ShelfItem): ShelfItem =
        item.copy(imageUrl = null, imageUri = null, banner = null, coverFit = false)

    fun leanbackBanner(context: Context, packageName: String): Drawable? {
        return runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationBanner(appInfo) ?: pm.getActivityBanner(
                pm.getLeanbackLaunchIntentForPackage(packageName)?.component
                    ?: return@runCatching null,
            )
        }.getOrNull()
    }

    fun localCandidates(context: Context, packageName: String, icon: Drawable?): List<CoverCandidate> {
        val list = mutableListOf<CoverCandidate>()
        if (icon != null) {
            list += CoverCandidate(
                id = "local-icon",
                label = "App icon",
                source = "Local",
                storeValue = "local:icon",
                previewDrawable = icon,
            )
        }
        leanbackBanner(context, packageName)?.let { banner ->
            list += CoverCandidate(
                id = "local-banner",
                label = "Leanback banner",
                source = "Local",
                storeValue = "local:banner",
                previewDrawable = banner,
            )
        }
        return list
    }

    fun curatedCandidates(context: Context, packageName: String): List<CoverCandidate> {
        val entry = CuratedCovers.get(context, packageName) ?: return emptyList()
        val list = mutableListOf<CoverCandidate>()
        entry.icon?.let {
            list += CoverCandidate("curated-icon", "Curated icon", "Curated", "curated:$it", it)
        }
        entry.poster?.let {
            list += CoverCandidate("curated-poster", "Curated poster", "Curated", "curated:$it", it)
        }
        entry.banner?.let {
            list += CoverCandidate("curated-banner", "Curated banner", "Curated", "curated:$it", it)
        }
        return list
    }
}
