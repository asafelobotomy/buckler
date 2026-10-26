package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri

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
    fun resolveImageUrl(
        context: Context,
        packageName: String?,
        layout: CoverLayout,
    ): String? {
        if (packageName.isNullOrBlank()) return null
        val stored = CoverStore.get(context, packageName)
        CoverStore.parseUrl(stored)?.let { return it }
        if (CoverStore.isLocalIcon(stored) || CoverStore.isLocalBanner(stored)) return null
        // Default: curated when available
        return CuratedCovers.urlFor(context, packageName, layout)
    }

    fun resolveBannerDrawable(context: Context, packageName: String?): Drawable? {
        if (packageName.isNullOrBlank()) return null
        val stored = CoverStore.get(context, packageName)
        if (stored != null && !CoverStore.isLocalBanner(stored) && CoverStore.parseUrl(stored) != null) {
            return null
        }
        if (stored != null && CoverStore.isLocalIcon(stored)) return null
        return leanbackBanner(context, packageName)
    }

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

    fun applyToItem(context: Context, item: ShelfItem, layout: CoverLayout): ShelfItem {
        val pkg = item.packageName ?: return item
        val url = resolveImageUrl(context, pkg, layout)
        val stored = CoverStore.get(context, pkg)
        val useBanner = CoverStore.isLocalBanner(stored) ||
            (stored == null && url == null && leanbackBanner(context, pkg) != null && layout != CoverLayout.ICON)
        if (url != null) {
            return item.copy(imageUrl = url, imageUri = null)
        }
        if (useBanner || CoverStore.isLocalBanner(stored)) {
            // Represent banner as android.resource URI is hard; CoverTile will fall back to icon.
            // Prefer storing curated URLs. For local banner, leave imageUrl null and let UI use icon/banner.
            return item
        }
        return item
    }
}
