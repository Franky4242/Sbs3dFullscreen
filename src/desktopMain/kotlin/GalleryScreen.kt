import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.gallery_empty
import sbs3dfullscreen.resources.gallery_favorite_content_description
import sbs3dfullscreen.resources.gallery_image_count
import sbs3dfullscreen.resources.gallery_legend_image_content_description
import sbs3dfullscreen.resources.gallery_legend_text_content_description
import sbs3dfullscreen.resources.gallery_screen_title
import sbs3dfullscreen.resources.gallery_warning_content_description
import sbs3dfullscreen.resources.ic_image_comment
import sbs3dfullscreen.resources.ic_text_comment
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
/**
 * Absolute position (within the LazyColumn built by GalleryScreen below, one header item per
 * group followed by one row item per [columns] photos when that group is expanded) of the row
 * containing [target], or null if [target] isn't in any expanded group. Mirrors the item order
 * GalleryScreen itself lays down, so LazyListState.scrollToItem(index) lands on the right row.
 */
private fun flatItemIndexOf(
    groups: List<GalleryGroup>,
    expandedGroups: Set<String>,
    columns: Int,
    target: File,
): Int? {
    var index = 0
    for (group in groups) {
        index++ // header item
        if (!expandedGroups.contains(group.relativePath)) continue
        val photoIndex = group.files.indexOf(target)
        if (photoIndex >= 0) return index + photoIndex / columns
        index += (group.files.size + columns - 1) / columns // row count for this group
    }
    return null
}

@Composable
fun GalleryScreen(
    groups: List<GalleryGroup>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onOpenImage: (GalleryGroup, Int) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    scrollTarget: File? = null,
    onScrollTargetConsumed: () -> Unit = {},
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

                    LaunchedEffect(scrollTarget, columns) {
                        val target = scrollTarget ?: return@LaunchedEffect
                        val itemIndex = flatItemIndexOf(groups, expandedGroups, columns, target)
                        if (itemIndex != null) listState.scrollToItem(itemIndex)
                        onScrollTargetConsumed()
                    }

                    LazyColumn(
                        state = listState,
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

/**
 * The kind of legend a photo carries, mirroring CameraSync3D's LegendIconType. AddLegend (no
 * legend yet) is never rendered in the gallery - it only exists so [legendTypeOf] can return an
 * "absent" value the info row filters out - which is why only the text/image bubbles are drawn.
 */
private enum class LegendIconType { AddLegend, TextLegend, ImageLegend }

/** Decoded thumbnail plus the per-photo flags shown on/under it, all read in one Dispatchers.IO pass. */
private data class ThumbnailInfo(
    val bitmap: ImageBitmap?,
    val isFavorite: Boolean,
    val legendType: LegendIconType,
    val hasWarning: Boolean,
)

/** CameraSync3D's stereo-issue badge orange (StereoIssueWarningIcon), same value as Exif3dInfoPanel's WarningColor. */
private val WarningColor = Color(0xFFFF9800)

// CameraSync3D's double-extension convention for SBS/JPS stereo photos, plus the "_raw"/"editedN"
// edit marker that sits right before it - kept in sync with AutoAlign.kt's sbsDoubleExtensions/
// existingEditMarker (which write those names); here we only read them back for the gallery label.
private val sbsDoubleExtensions = listOf(".sbs.jpg", ".jps.jpg")
private val editMarkerRegex = Regex("_(raw|edited\\d*)$")

/**
 * The "raw"/"edited"/"editedN" label shown under a thumbnail, derived from the filename the same
 * way CameraSync3D's gallery derives Photo3d.commentOrRawEdited. Returns "" for arbitrarily-named
 * JPEGs Windows can open that don't carry the marker, so the info row simply omits the label.
 * Not file-private: also used by ImageScreen.kt for the same label shown over the fullscreen photo.
 */
internal fun rawEditedLabel(file: File): String {
    val name = file.name
    val matchedExt = sbsDoubleExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
    val base = if (matchedExt != null) name.dropLast(matchedExt.length) else file.nameWithoutExtension
    return editMarkerRegex.find(base)?.groupValues?.get(1).orEmpty()
}

/**
 * The subset of [files] that are each the best raw/edited version within their group (same
 * directory + base name once the marker is stripped, e.g. "photo_raw.sbs.jpg" and
 * "photo_edited2.sbs.jpg" are the same group). Ranked raw < edited < edited2 < ... < editedN.
 * Files with no marker (rawEditedLabel returns "") form a singleton group of their own, so they're
 * always kept. Used by AppViewModel's "keep best of each" filter, driven from ImageScreen's
 * settings menu.
 */
internal fun bestVersionsOnly(files: List<File>): Set<File> =
    files.groupBy(::rawEditedGroupKey).values.map { versions -> versions.maxBy(::rawEditedRank) }.toSet()

private fun rawEditedGroupKey(file: File): String {
    val name = file.name
    val matchedExt = sbsDoubleExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
    val base = if (matchedExt != null) name.dropLast(matchedExt.length) else file.nameWithoutExtension
    val strippedBase = editMarkerRegex.replace(base, "")
    // Mirrors AutoAlign.nextAvailableFile's own baseName normalization: CameraSync3D's "_raw"
    // marker sits after a base that already ends in "_" (so stripping it leaves a trailing "_"),
    // while "_editedN" doesn't carry its own leading underscore beyond the one the marker itself
    // consumes - without collapsing both to the same single trailing "_", "photo__raw.sbs.jpg" and
    // "photo_edited.sbs.jpg" strip to "photo_" and "photo" respectively and never group together.
    val normalizedBase = if (strippedBase.endsWith("_")) strippedBase else "${strippedBase}_"
    return File(file.parentFile, normalizedBase).path
}

private fun rawEditedRank(file: File): Int {
    val marker = rawEditedLabel(file)
    return when {
        marker.isEmpty() || marker == "raw" -> 0
        else -> marker.removePrefix("edited").toIntOrNull() ?: 1
    }
}

/**
 * Legend state of a photo, mirroring CameraSync3D's Exif3d.getLegendIconType precedence: a text
 * legend (EXIF UserComment set) wins over an image legend ([hasImageLegend], the Desc3d.hasLegend
 * flag already read from ImageDescription by the caller); neither present means no legend yet.
 */
private fun legendTypeOf(file: File, hasImageLegend: Boolean): LegendIconType {
    val comment = runCatching { Exif.getExifUserComment(file) }.getOrNull()
    return when {
        !comment.isNullOrBlank() -> LegendIconType.TextLegend
        hasImageLegend -> LegendIconType.ImageLegend
        else -> LegendIconType.AddLegend
    }
}

@Composable
private fun GalleryThumbnail(file: File, onClick: () -> Unit) {
    val label = remember(file) { rawEditedLabel(file) }
    val info by produceState<ThumbnailInfo?>(initialValue = null, key1 = file) {
        value = withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                ImageIO.read(file)?.toThumbnail(thumbnailPixelWidth, thumbnailPixelHeight)?.toComposeImageBitmap()
            }.getOrNull()
            val desc = runCatching { Exif3d.get3dCameraCharacteristics(file) }.getOrNull()
            ThumbnailInfo(
                bitmap = bitmap,
                isFavorite = desc?.favorite == true,
                legendType = legendTypeOf(file, hasImageLegend = desc?.hasLegend == true),
                hasWarning = desc?.warning == true,
            )
        }
    }
    Column(modifier = Modifier.width(thumbnailWidth)) {
        Box(
            modifier = Modifier
                .size(width = thumbnailWidth, height = thumbnailHeight)
                .clickable(onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val loadedBitmap = info?.bitmap
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
            // Overlaid in the bottom-end corner over the thumbnail, like CameraSync3D's
            // StereoIssueWarningBadge (shown only when the warning is set - hideIfNotActivated).
            if (info?.hasWarning == true) {
                StereoIssueWarningBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
        }
        GalleryThumbnailInfoRow(label = label, info = info)
    }
}

/** The raw/editedN label + favorite + legend icons shown under a thumbnail (see CameraSync3D's ComposablePictureList). */
@Composable
private fun GalleryThumbnailInfoRow(label: String, info: ThumbnailInfo?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (info?.isFavorite == true) {
            Spacer(Modifier.width(4.dp))
            FavoriteIcon(size = 15.dp)
        }
        val legendType = info?.legendType ?: LegendIconType.AddLegend
        if (legendType != LegendIconType.AddLegend) {
            Spacer(Modifier.width(4.dp))
            LegendIcon(size = 15.dp, type = legendType)
        }
    }
}

/**
 * The orange stereo-issue warning icon on a translucent black circle, mirroring CameraSync3D's
 * StereoIssueWarningIcon (as used by StereoIssueWarningBadge in its gallery). Display-only here.
 */
@Composable
private fun StereoIssueWarningBadge(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(
        modifier = modifier.size(size).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(Res.string.gallery_warning_content_description),
            tint = WarningColor,
            modifier = Modifier.fillMaxSize(0.9f).padding(bottom = size * 0.15f),
        )
    }
}

/** Display-only heart, red like CameraSync3D's FavoriteIcon (the gallery never toggles it). */
@Composable
private fun FavoriteIcon(size: Dp) {
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = stringResource(Res.string.gallery_favorite_content_description),
        tint = Color.Red,
        modifier = Modifier.size(size),
    )
}

/** The text/image legend bubble, mirroring CameraSync3D's LegendIcon (AddLegend is never drawn here). */
@Composable
private fun LegendIcon(size: Dp, type: LegendIconType) {
    if (type == LegendIconType.AddLegend) return
    val isText = type == LegendIconType.TextLegend
    Icon(
        painter = painterResource(if (isText) Res.drawable.ic_text_comment else Res.drawable.ic_image_comment),
        contentDescription = stringResource(
            if (isText) Res.string.gallery_legend_text_content_description
            else Res.string.gallery_legend_image_content_description,
        ),
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(size),
    )
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
