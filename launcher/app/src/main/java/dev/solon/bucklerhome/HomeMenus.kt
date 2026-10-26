package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Wires the long-press app menu to layout edits, uninstall and cover picking. */
@Composable
fun HomeMenuHost(
    item: ShelfItem,
    layout: HomeLayout,
    onLayoutChange: (HomeLayout) -> Unit,
    onOpen: (ShelfItem) -> Unit,
    onChangeCover: (ShelfItem) -> Unit,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pkg = item.packageName
    val pins = layout.appsPackages()
    val pinned = pkg != null && pkg in pins
    val appsShelfShown = layout.shelves.any { it.type == ShelfType.APPS && it.enabled }

    fun edit(next: HomeLayout) {
        HomeLayout.save(context, next)
        onLayoutChange(next)
        onDismiss()
    }

    AppMenuDialog(
        item = item,
        pinned = pinned,
        canMove = pinned && pins.size > 1 && appsShelfShown,
        canUninstall = canUninstallPackage(context, pkg),
        onOpen = {
            onOpen(item)
            onDismiss()
        },
        onChangeCover = {
            onChangeCover(item)
            onDismiss()
        },
        onRemove = { pkg?.let { edit(layout.withoutPinned(it)) } ?: onDismiss() },
        onUninstall = {
            pkg?.let { uninstallPackage(context, it) }
            onDismiss()
        },
        onPin = { pkg?.let { edit(layout.withPinned(it)) } ?: onDismiss() },
        onMove = {
            pkg?.let(onMove)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
