package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable version of ComposablePlaylistItem, previously defined inline in PlaylistFragment.kt:
// same behavior, but uses coil3.compose.SubcomposeAsyncImage (Coil3 supports desktop/JVM targets,
// unlike the coil2-era API) with a plain String model instead of an android.net.Uri, and a
// CircularProgressIndicator instead of the Android-drawable-backed LoadingPlaceholder. Reorder-drag
// orchestration (ReorderableItem/longPressDraggableHandle) stays with the caller; this composable
// only owns the row's own visual content. Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem

/**
 * Displays a single playlist item: thumbnail on the left, comment on the right.
 * @param index : position of the item in the displayed list
 * @param photo : item data (uri, comment, …)
 * @param shadowElevation : elevated while dragging
 * @param onOpenPlaylistItem : called when the user taps the item
 * @param reorderModifier : modifier with the caller's drag-handle already attached (no-op if the
 *   caller doesn't support reordering)
 * @param halfWidthImage : when true, the thumbnail takes half the row's width (and the comment the
 *   other half) instead of a fixed 200dp - meant for callers with much more horizontal room than a
 *   phone list (e.g. desktop)
 * @param imageHeight : thumbnail height, also used as the fixed height in the default (phone) layout
 */
@Composable
fun ComposablePlaylistItem(
    index: Int = 0,
    photo: PlaylistItem = PlaylistItem("toto.jpg", ""),
    shadowElevation: Dp = 4.dp,
    onOpenPlaylistItem: (Int) -> Unit = {},
    reorderModifier: Modifier = Modifier,
    halfWidthImage: Boolean = false,
    imageHeight: Dp = 100.dp,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(reorderModifier),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = shadowElevation,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp).clickable { onOpenPlaylistItem(index) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val imageModifier = if (halfWidthImage) {
                Modifier.weight(1f).height(imageHeight)
            } else {
                Modifier.size(200.dp, imageHeight)
            }
            if (photo.imageUriString.isEmpty()) {
                Box(imageModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SubcomposeAsyncImage(
                    model = photo.imageUriString,
                    contentDescription = "Image",
                    loading = { Box(imageModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                    error = {
                        Box(imageModifier, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.BrokenImage, contentDescription = null)
                        }
                    },
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                )
            }
            Text(
                photo.comment,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
