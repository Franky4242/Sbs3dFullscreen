import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.gallery_empty
import sbs3dfullscreen.resources.gallery_image_count
import sbs3dfullscreen.resources.gallery_screen_title
import sbs3dfullscreen.resources.playlist_back_button
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.awt.Image as AwtImage

// Wide rather than square: these are side-by-side 3D photos, so a wide thumbnail box shows
// both eye-halves instead of cropping most of the frame away.
private val thumbnailWidth = 320.dp
private val thumbnailHeight = 160.dp
private val thumbnailSpacing = 8.dp

// Decoded at ~2x the on-screen thumbnail size so it still looks sharp on hi-DPI displays.
private const val thumbnailPixelWidth = 640
private const val thumbnailPixelHeight = 320

/**
 * Shows the images found under a directory chosen from WelcomeScreen's "Open 3D image directory"
 * button, recursively grouped by subdirectory (each a collapsible section - AppViewModel.openGallery/
 * scanGalleryDirectory). Tapping a thumbnail opens ImageView on that subdirectory's photos, starting
 * at the tapped one. Unlike CameraSync3D's GalleryFragment (MediaStore-backed, flat list, Coil
 * thumbnails), this reads plain JPEGs straight off disk and decodes thumbnails on the fly - there's
 * no Android gallery equivalent for the per-subdirectory grouping, since the whole notion of
 * "browse a folder tree" doesn't exist on a MediaStore-backed gallery.
 */
@Composable
fun GalleryScreen(
    groups: List<GalleryGroup>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onOpenImage: (GalleryGroup, Int) -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.gallery_screen_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {},
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.gallery_empty))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = (maxWidth / (thumbnailWidth + thumbnailSpacing)).toInt().coerceAtLeast(1)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(thumbnailSpacing),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        groups.forEach { group ->
                            item(key = "header:${group.relativePath}") {
                                GalleryGroupHeader(group, expandedGroups.contains(group.relativePath), onToggleGroup)
                            }
                            if (expandedGroups.contains(group.relativePath)) {
                                val rows = group.files.chunked(columns)
                                itemsIndexed(rows, key = { rowIndex, _ -> "row:${group.relativePath}:$rowIndex" }) { rowIndex, rowFiles ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(thumbnailSpacing)) {
                                        rowFiles.forEachIndexed { columnIndex, file ->
                                            val index = rowIndex * columns + columnIndex
                                            GalleryThumbnail(file = file, onClick = { onOpenImage(group, index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun GalleryGroupHeader(group: GalleryGroup, expanded: Boolean, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(group.relativePath) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(group.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(stringResource(Res.string.gallery_image_count, group.files.size))
    }
}

@Composable
private fun GalleryThumbnail(file: File, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageIO.read(file)?.toThumbnail(thumbnailPixelWidth, thumbnailPixelHeight)?.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(width = thumbnailWidth, height = thumbnailHeight)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

private fun BufferedImage.toThumbnail(maxWidth: Int, maxHeight: Int): BufferedImage {
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height, 1.0)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaledInstance = getScaledInstance(w, h, AwtImage.SCALE_SMOOTH)
    val output = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = output.createGraphics()
    g.drawImage(scaledInstance, 0, 0, null)
    g.dispose()
    return output
}
