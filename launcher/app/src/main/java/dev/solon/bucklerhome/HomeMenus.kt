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
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pkg = item.packageName
    val pins = layout.appsPackages()
    val pinned = pkg != null && pkg in pins
    val index = if (pkg != null) pins.indexOf(pkg) else -1

    fun edit(next: HomeLayout) {
        HomeLayout.save(context, next)
        onLayoutChange(next)
        onDismiss()
    }

    AppMenuDialog(
        item = item,
        pinned = pinned,
        canMoveLeft = pinned && index > 0,
        canMoveRight = pinned && index < pins.lastIndex,
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
        onMoveLeft = { pkg?.let { edit(layout.withMovedPinned(it, -1)) } ?: onDismiss() },
        onMoveRight = { pkg?.let { edit(layout.withMovedPinned(it, 1)) } ?: onDismiss() },
        onDismiss = onDismiss,
    )
}
