package dev.solon.bucklerhome

import android.content.Context

data class ResolvedShelf(
    val config: ShelfConfig,
    val items: List<ShelfItem>,
)

object Shelves {
    fun resolve(
        context: Context,
        layout: HomeLayout,
        apps: List<LaunchableApp>,
    ): List<ResolvedShelf> {
        val pinned = layout.appsPackages()
        return layout.shelves
            .filter { it.enabled }
            .map { config ->
                ResolvedShelf(
                    config = config,
                    items = itemsFor(context, config, apps, pinned, layout.suggestedFeedUrl),
                )
            }
            .filter { shelf ->
                // Keep empty watch_next/suggested visible with hint via UI; hide empty recents
                when (shelf.config.type) {
                    ShelfType.RECENTS -> shelf.items.isNotEmpty()
                    else -> true
                }
            }
    }

    private fun itemsFor(
        context: Context,
        config: ShelfConfig,
        apps: List<LaunchableApp>,
        pinned: List<String>,
        feedUrl: String?,
    ): List<ShelfItem> {
        val byPkg = apps.associateBy { it.packageName }
        return when (config.type) {
            ShelfType.APPS -> {
                val pkgs = config.packages.ifEmpty { DEFAULT_DOCK_PACKAGES }
                pkgs.mapNotNull { byPkg[it] }.map { it.toShelfItem() }
                    .ifEmpty { apps.filterNot { it.isSystem }.take(6).map { it.toShelfItem() } }
            }
            ShelfType.LIBRARY ->
                apps.filterNot { it.isSystem }.map { it.toShelfItem() }
            ShelfType.RECENTS ->
                LaunchStats.loadRecents(context).mapNotNull { byPkg[it]?.toShelfItem() }
            ShelfType.WATCH_NEXT ->
                WatchNextRepository.load(context)
            ShelfType.SUGGESTED ->
                SuggestedRepository.load(context, apps, pinned, feedUrl)
        }
    }
}

fun LaunchableApp.toShelfItem(): ShelfItem =
    ShelfItem(
        id = "app-$packageName",
        title = label,
        packageName = packageName,
        launchIntent = launchIntent,
        icon = icon,
    )

fun heroItem(shelves: List<ResolvedShelf>, layout: HomeLayout): ShelfItem? {
    if (layout.layout != CoverLayout.BANNER) return null
    shelves.firstOrNull { it.config.type == ShelfType.WATCH_NEXT }?.items?.firstOrNull()
        ?.let { return it }
    shelves.firstOrNull { it.config.type == ShelfType.APPS }?.items?.firstOrNull()
        ?.let { return it }
    return shelves.flatMap { it.items }.firstOrNull()
}
