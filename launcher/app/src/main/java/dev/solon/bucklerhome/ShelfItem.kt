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
    val imageUri: Uri? = null,
    val imageUrl: String? = null,
)
