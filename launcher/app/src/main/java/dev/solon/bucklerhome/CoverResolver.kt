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
        if (CoverStore.isLocalIcon(stored)) return iconArt(item, layout)
        if (CoverStore.isLocalBanner(stored)) {
            return leanbackBanner(context, pkg)?.let { fittedArt(item, it, layout, fromBanner = true) } ?: iconArt(item, layout)
        }
        return automaticArt(context, item, layout, curated[pkg])
    }

    /**
     * Banner layout uses the app's TV banner, poster layout a curated vertical cover, icon layout
     * the launcher icon. An app with no picture of the tile's shape gets one made from what it
     * has: its TV banner (for a poster) or its icon, fitted whole on its own edge colour.
     */
    private fun automaticArt(
        context: Context,
        item: ShelfItem,
        layout: CoverLayout,
        entry: CuratedCoverEntry?,
    ): ShelfItem {
        val pkg = item.packageName
        val banner = if (layout != CoverLayout.ICON && pkg != null) leanbackBanner(context, pkg) else null
        if (layout == CoverLayout.BANNER && banner != null) return bannerArt(item, banner)
        entry?.urlFor(layout)?.let { url ->
            // The curated pack's pictures are square logos: letterbox them in a poster or banner.
            return item.copy(imageUrl = url, imageUri = null, banner = null, coverFit = layout != CoverLayout.ICON)
        }
        // A poster made from the TV banner shows the app's name and art; else the icon.
        if (layout == CoverLayout.POSTER && banner != null) return fittedArt(item, banner, layout, fromBanner = true)
        return iconArt(item, layout)
    }

    private fun bannerArt(item: ShelfItem, banner: Drawable): ShelfItem =
        item.copy(imageUrl = null, imageUri = null, banner = banner, coverFit = false)

    /** The icon: as it is on a square tile, fitted into a poster or banner. An icon-pack icon fits itself. */
    private fun iconArt(item: ShelfItem, layout: CoverLayout): ShelfItem {
        val icon = item.icon
        if (layout == CoverLayout.ICON || icon == null || icon is PackIconDrawable) {
            return item.copy(imageUrl = null, imageUri = null, banner = null, coverFit = false)
        }
        return fittedArt(item, icon, layout, fromBanner = false)
    }

    private fun fittedArt(item: ShelfItem, art: Drawable, layout: CoverLayout, fromBanner: Boolean): ShelfItem {
        if (layout == CoverLayout.BANNER && fromBanner) return bannerArt(item, art)
        val fill = FittedArt.edgeColour(art) ?: TILE_FALLBACK
        // A banner spans the poster's width; an icon sits with room around it, as on a TV banner.
        val share = if (fromBanner) 0.94f else 0.62f
        return bannerArt(item, FittedArt(art.constantState?.newDrawable()?.mutate() ?: art, fill, share))
    }

    /** Behind a cut-out logo with no edge colour: a dark neutral that suits every theme's tiles. */
    private const val TILE_FALLBACK = 0xFF2A2A2E.toInt()

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
