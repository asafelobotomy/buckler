package dev.solon.bucklerhome

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri

data class ShelfItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val packageName: String? = null,
    val launchIntent: Intent? = null,
    val icon: Drawable? = null,
    /** Leanback banner for the banner layout, or the one chosen via Change cover. */
    val banner: Drawable? = null,
    val imageUri: Uri? = null,
    val imageUrl: String? = null,
    /** Logo-style art (curated icons) is letterboxed instead of cropped. */
    val coverFit: Boolean = false,
)
