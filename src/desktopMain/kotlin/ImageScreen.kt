import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.image_settings_exit_fullscreen_label
import sbs3dfullscreen.resources.image_settings_halve_left_right_toggle_label
import sbs3dfullscreen.resources.image_settings_info_panel_label
import sbs3dfullscreen.resources.image_settings_keep_best_of_each_toggle_label
import sbs3dfullscreen.resources.image_settings_menu_content_description
import sbs3dfullscreen.resources.image_settings_next_label
import sbs3dfullscreen.resources.image_settings_previous_label
import java.io.File

// Unlike Exif3dInfoPanel's InfoPanelShiftPercent, this label is meant to read as pinned to the
// screen glass rather than floating, so it's duplicated per half (for the same reason every other
// overlay is - a single copy would only appear in one eye) but not shifted apart.
private const val RawEditedLabelShiftPercent = 0f

// Same reasoning as RawEditedLabelShiftPercent: this is UI chrome, not a photo annotation, so it
// reads as pinned to the screen glass rather than floating.
private const val SettingsMenuShiftPercent = 0f

@Composable
fun ImageScreen(
    file: File,
    overrideBitmap: ImageBitmap? = null,
    showInfoPanel: Boolean = false,
    hasAlignedPreview: Boolean = false,
    isAligning: Boolean = false,
    alignToast: AlignToast? = null,
    saveToast: SaveToast? = null,
    keepBestOfEachOnly: Boolean = false,
    halveLeftRightImages: Boolean = true,
    manualAlignMode: Boolean = false,
    manualAlignOffsetX: Int = 0,
    manualAlignOffsetY: Int = 0,
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onStartManualAlign: () -> Unit = {},
    onCancelManualAlign: () -> Unit = {},
    onSaveManualAlign: () -> Unit = {},
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit = {},
    onHalveLeftRightImagesChosen: (Boolean) -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onNextImage: () -> Unit = {},
    onPreviousImage: () -> Unit = {},
    onToggleInfoPanel: () -> Unit = {},
    onImageLoaded: () -> Unit = {},
) {
    // Decoded off the UI thread (large side-by-side 3D JPEGs can take a while) so a loading
    // overlay drawn by the caller (see Main.kt's isEnteringFullscreen/FullscreenLoadingOverlay)
    // actually gets a chance to render instead of the whole composition blocking until decode
    // finishes.
    var fileBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        fileBitmap = withContext(Dispatchers.IO) { file.readBytes().decodeToImageBitmap() }
    }
    val imageBitmap = overrideBitmap ?: fileBitmap
    LaunchedEffect(fileBitmap) {
        if (fileBitmap != null) onImageLoaded()
    }

    // Hosted here rather than inside Exif3dInfoPanel so the toast survives the panel being
    // toggled closed (pressing Shift/Ctrl again), which removes Exif3dInfoPanel (and any state
    // it holds) from composition.
    var exifUpdateToken by remember(file) { mutableStateOf(0) }

    Stereo3DCursorHost {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            imageBitmap?.let { bitmap ->
                StereoImage(bitmap, halveLeftRightImages, manualAlignOffsetX, manualAlignOffsetY)
            }
            RawEditedLabelOverlay(file)
            SettingsMenuOverlay(
                keepBestOfEachOnly,
                halveLeftRightImages,
                onKeepBestOfEachOnlyChosen,
                onHalveLeftRightImagesChosen,
                onExitFullscreen,
                onNextImage,
                onPreviousImage,
                onToggleInfoPanel,
            )
            if (showInfoPanel) {
                Exif3dInfoPanel(
                    file,
                    onExifUpdated = { exifUpdateToken++ },
                    hasAlignedPreview = hasAlignedPreview,
                    isAligning = isAligning,
                    manualAlignMode = manualAlignMode,
                    hasManualOffset = manualAlignOffsetX != 0 || manualAlignOffsetY != 0,
                    onAutoAlign = onAutoAlign,
                    onCorrectZoom = onCorrectZoom,
                    onSaveAligned = onSaveAligned,
                    onStartManualAlign = onStartManualAlign,
                    onCancelManualAlign = onCancelManualAlign,
                    onSaveManualAlign = onSaveManualAlign,
                )
            }
            ExifUpdatedToast(exifUpdateToken)
            AlignResultToast(alignToast)
            SaveResultToast(saveToast)
        }
    }
}

/**
 * Crops the combined L+R photo apart and draws each eye-half fit and centered within its own half
 * of the window, full-bleed - the source file already has each eye at full native resolution side
 * by side (see CLAUDE.md's "full-width SBS" note). Always splitting this way (rather than fitting
 * the combined bitmap as one unit) keeps the L/R split centered on each half's own midpoint: for a
 * source image taller (relative to width) than the window, fitting as one unit would only letterbox
 * the two outer edges, pushing the split off-center from each half's own midpoint.
 *
 * When [halveLeftRightImages] is on, each half is additionally squeezed horizontally by 2 before
 * being fit - a Half-SBS 3D monitor (native window width, hardware unsqueezes each half per eye)
 * needs that squeeze; a Full-SBS monitor (native window width already double, no hardware unsqueeze)
 * wants the toggle off.
 *
 * [manualAlignOffsetX]/[manualAlignOffsetY] (source-image pixels, 0 unless manual-align mode is
 * active - see AppViewModel.manualAlignOffsetX/Y) nudge the right half only, but rather than
 * leaving a blank gap where the shift no longer overlaps the left half, both halves are cropped
 * live to their common overlapping region - the same math ManualAlign.saveManualAlign applies to
 * the actual file at save time, so this preview is WYSIWYG.
 */
@Composable
private fun StereoImage(bitmap: ImageBitmap, halveLeftRightImages: Boolean, manualAlignOffsetX: Int = 0, manualAlignOffsetY: Int = 0) {
    val halfWidth = bitmap.width / 2
    val heightPx = bitmap.height
    val dx = manualAlignOffsetX.coerceIn(-(halfWidth - 1).coerceAtLeast(0), (halfWidth - 1).coerceAtLeast(0))
    val dy = manualAlignOffsetY.coerceIn(-(heightPx - 1).coerceAtLeast(0), (heightPx - 1).coerceAtLeast(0))
    val cropWidth = halfWidth - abs(dx)
    val cropHeight = heightPx - abs(dy)
    val leftOffset = IntOffset(maxOf(dx, 0), maxOf(dy, 0))
    val rightOffset = IntOffset(halfWidth + maxOf(-dx, 0), maxOf(-dy, 0))
    val cropSize = IntSize(cropWidth, cropHeight)
    Row(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, leftOffset, cropSize, halveLeftRightImages)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, rightOffset, cropSize, halveLeftRightImages)
        }
    }
}

/**
 * Crops [bitmap] to the [srcOffset]/[srcSize] region and fits+centers it within this composable's
 * own box (squeezing its width by 2 first when [halveLeftRightImages] is on). Plain [Image] can't
 * crop a sub-region of a bitmap (its `contentScale` always maps the whole source), so this draws
 * directly via [Canvas]'s source-rect [drawImage].
 */
@Composable
private fun StereoHalfImage(bitmap: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, halveLeftRightImages: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveWidthPx = if (halveLeftRightImages) srcSize.width / 2f else srcSize.width.toFloat()
        val scale = minOf(size.width / effectiveWidthPx, size.height / srcSize.height)
        val dstWidth = effectiveWidthPx * scale
        val dstHeight = srcSize.height * scale
        drawImage(
            image = bitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset(((size.width - dstWidth) / 2).toInt(), ((size.height - dstHeight) / 2).toInt()),
            dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * "raw"/"edited"/"editedN" badge derived from [file]'s name (see GalleryScreen.kt's
 * rawEditedLabel, which this reuses so the fullscreen view and the gallery thumbnails agree),
 * shown at the top end of each half so it reads in 3D like every other overlay - duplicated per
 * half and offset by [RawEditedLabelShiftPercent] (0%, i.e. no depth shift here) of half the
 * screen width, same technique as Exif3dInfoPanel. Renders nothing for arbitrarily-named JPEGs
 * that don't carry the marker.
 */
@Composable
private fun RawEditedLabelOverlay(file: File) {
    val label = remember(file) { rawEditedLabel(file) }
    if (label.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * RawEditedLabelShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { RawEditedLabelHalf(label, offsetX = -shift / 2) }
            Box(Modifier.fillMaxSize().weight(1f)) { RawEditedLabelHalf(label, offsetX = shift / 2) }
        }
    }
}

@Composable
private fun RawEditedLabelHalf(label: String, offsetX: Dp) {
    Box(
        modifier = Modifier.fillMaxSize().padding(end = 24.dp, top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    shadow = Shadow(color = Color.Black, blurRadius = 3f, offset = Offset(2f, 2f)),
                ),
            )
        }
    }
}

/**
 * Settings gear at the top start of each half (see [SettingsMenuShiftPercent] for why it's
 * pinned rather than floating, same technique as [RawEditedLabelOverlay]/Exif3dInfoPanel).
 * Tapping it opens a small panel with the "keep best of each" switch; open/closed state is
 * hoisted above the per-half Row so tapping either half's gear opens both.
 */
@Composable
private fun SettingsMenuOverlay(
    keepBestOfEachOnly: Boolean,
    halveLeftRightImages: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: () -> Unit,
    onPreviousImage: () -> Unit,
    onToggleInfoPanel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * SettingsMenuShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = -shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, halveLeftRightImages, onKeepBestOfEachOnlyChosen, onHalveLeftRightImagesChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, halveLeftRightImages, onKeepBestOfEachOnlyChosen, onHalveLeftRightImagesChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel)
            }
        }
    }
}

@Composable
private fun SettingsMenuHalf(
    offsetX: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    keepBestOfEachOnly: Boolean,
    halveLeftRightImages: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: () -> Unit,
    onPreviousImage: () -> Unit,
    onToggleInfoPanel: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            // canFocus = false for the same reason as Exif3dInfoPanel's icons: a click stealing
            // keyboard focus would break Escape/arrow key handling on Main.kt's root Box.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .focusProperties { canFocus = false }
                    .clickable(onClick = onToggleExpanded)
                    .cursor3DClickTarget(onToggleExpanded),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(Res.string.image_settings_menu_content_description),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusProperties { canFocus = false },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.image_settings_keep_best_of_each_toggle_label),
                            style = TextStyle(color = Color.White, fontSize = 14.sp),
                            modifier = Modifier.width(220.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = keepBestOfEachOnly,
                            onCheckedChange = onKeepBestOfEachOnlyChosen,
                            modifier = Modifier
                                .focusProperties { canFocus = false }
                                .cursor3DClickTarget { onKeepBestOfEachOnlyChosen(!keepBestOfEachOnly) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.image_settings_halve_left_right_toggle_label),
                            style = TextStyle(color = Color.White, fontSize = 14.sp),
                            modifier = Modifier.width(220.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = halveLeftRightImages,
                            onCheckedChange = onHalveLeftRightImagesChosen,
                            modifier = Modifier
                                .focusProperties { canFocus = false }
                                .cursor3DClickTarget { onHalveLeftRightImagesChosen(!halveLeftRightImages) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_next_label), onNextImage)
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_previous_label), onPreviousImage)
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_info_panel_label), onToggleInfoPanel)
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_exit_fullscreen_label), onExitFullscreen)
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuItemRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = Color.White, fontSize = 14.sp),
        )
    }
}
