package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChangeCoverDialog(
    packageName: String,
    title: String,
    icon: android.graphics.drawable.Drawable?,
    layout: CoverLayout,
    onDismiss: () -> Unit,
    onPicked: () -> Unit,
) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Loading covers…") }

    LaunchedEffect(packageName, layout) {
        loading = true
        status = "Loading covers…"
        candidates = withContext(Dispatchers.IO) {
            CuratedCovers.ensureCached(context)
            val local = CoverResolver.localCandidates(context, packageName, icon)
            val curated = CoverResolver.curatedCandidates(context, packageName)
            val sgdb = if (CoverAccounts.isLinked(context, CoverProvider.STEAMGRIDDB)) {
                SteamGridDbClient.searchCovers(context, title, layout)
            } else {
                emptyList()
            }
            val igdb = if (CoverAccounts.isLinked(context, CoverProvider.IGDB)) {
                IgdbClient.searchCovers(context, title)
            } else {
                emptyList()
            }
            local + curated + sgdb + igdb
        }
        loading = false
        status = if (candidates.isEmpty()) {
            "No covers found. Link SteamGridDB or IGDB in Buckler Settings for more."
        } else {
            "${candidates.size} covers"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            onClick = {},
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ColorFooter,
                focusedContainerColor = ColorFooter,
                contentColor = ColorText,
                focusedContentColor = ColorText,
            ),
            modifier = Modifier.widthIn(min = 520.dp, max = 900.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text(
                    text = "Change cover",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = "$title · $status",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                if (!loading && candidates.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    ) {
                        items(candidates, key = { it.id }) { candidate ->
                            CoverPickTile(candidate) {
                                CoverStore.set(context, packageName, candidate.storeValue)
                                onPicked()
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    SettingsAction("Use default") {
                        CoverStore.set(context, packageName, null)
                        onPicked()
                    }
                    SettingsAction("Close", onClick = onDismiss)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CoverPickTile(candidate: CoverCandidate, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(120.dp)
            .height(160.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp),
        ) {
            val drawable = candidate.previewDrawable
            if (drawable != null) {
                val bmp = remember(candidate.id) {
                    drawable.toBitmap(128, 128).asImageBitmap()
                }
                Image(
                    bitmap = bmp,
                    contentDescription = candidate.label,
                    modifier = Modifier
                        .size(96.dp)
                        .weight(1f),
                )
            } else if (candidate.previewUrl != null) {
                AsyncImage(
                    model = candidate.previewUrl,
                    contentDescription = candidate.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .weight(1f),
                )
            }
            Text(text = candidate.source, fontSize = 11.sp, maxLines = 1)
            Text(text = candidate.label, fontSize = 11.sp, maxLines = 1, color = ColorMuted)
        }
    }
}
