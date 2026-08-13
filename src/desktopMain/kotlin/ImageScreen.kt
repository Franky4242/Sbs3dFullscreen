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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
import sbs3dfullscreen.resources.align_save_button
import sbs3dfullscreen.resources.cancel_button
import sbs3dfullscreen.resources.discard_button
import sbs3dfullscreen.resources.image_settings_exclude_stereo_issues_toggle_label
import sbs3dfullscreen.resources.image_settings_exit_fullscreen_label
import sbs3dfullscreen.resources.image_settings_favorites_only_toggle_label
import sbs3dfullscreen.resources.image_settings_halve_left_right_toggle_label
import sbs3dfullscreen.resources.image_settings_info_panel_label
import sbs3dfullscreen.resources.image_settings_keep_best_of_each_toggle_label
import sbs3dfullscreen.resources.image_settings_menu_content_description
import sbs3dfullscreen.resources.image_settings_next_label
import sbs3dfullscreen.resources.image_settings_previous_label
import sbs3dfullscreen.resources.unsaved_align_changes_dialog_message
import sbs3dfullscreen.resources.unsaved_align_changes_dialog_title
import java.io.File

// Unlike Exif3dInfoPanel's InfoPanelShiftPercent, this label is meant to read as pinned to the
// screen glass rather than floating, so it's duplicated per half (for the same reason every other
// overlay is - a single copy would only appear in one eye) but not shifted apart.
private const val RawEditedLabelShiftPercent = 0f

// Same reasoning as RawEditedLabelShiftPercent: this is UI chrome, not a photo annotation, so it
// reads as pinned to the screen glass rather than floating.
private const val SettingsMenuShiftPercent = 0f

// Same sign convention as Exif3dInfoPanel's InfoPanelShiftPercent - the crop rectangle/blackout
// mask drawn while dragging is an overlay on top of the photo, so it gets the same per-half
// duplication + depth shift as everything else, per the user's spec ("draw a rectangle in 3D").
private const val CropOverlayShiftPercent = -0.01f

// Same rationale as CropOverlayShiftPercent, for the "Spot stereo issues" tool's pink rectangles.
private const val SpotIssueOverlayShiftPercent = -0.01f

// Hot pink - matches SpotStereoIssues.kt's PinkBgra (the color baked into the saved photo), so the
// live preview while drawing/reviewing looks the same as the result after Save.
private val SpotIssuePinkColor = Color(0xFFFF1493)

// Below this (screen px, in one eye-half's local space), a press-release is treated as an
// accidental click rather than a deliberate rectangle drag, so the tool stays in drawing mode
// instead of committing a near-zero-size rectangle. Shared by the crop tool and "Spot stereo
// issues" - both draw a rectangle by drag (see computeDragFraction).
private const val MinRectDragPx = 20f

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
    favoritesOnly: Boolean = false,
    excludeStereoIssues: Boolean = false,
    halveLeftRightImages: Boolean = true,
    manualAlignMode: Boolean = false,
    manualAlignOffsetX: Int = 0,
    manualAlignOffsetY: Int = 0,
    cropMode: Boolean = false,
    cropRect: CropRectFraction? = null,
    spotIssuesMode: Boolean = false,
    spotIssueRects: List<IssueRectFraction> = emptyList(),
    pendingNavigation: PendingNavigationDirection? = null,
    onConfirmSaveAlignedAndNavigate: () -> Unit = {},
    onDiscardAlignedPreviewAndNavigate: () -> Unit = {},
    onCancelPendingNavigation: () -> Unit = {},
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onStartManualAlign: () -> Unit = {},
    onCancelManualAlign: () -> Unit = {},
    onSaveManualAlign: () -> Unit = {},
    onStartCrop: () -> Unit = {},
    onCropRectFinalized: (CropRectFraction) -> Unit = {},
    onCancelCrop: () -> Unit = {},
    onSaveCrop: () -> Unit = {},
    onStartSpotIssues: () -> Unit = {},
    onSpotIssueRectAdded: (IssueRectFraction) -> Unit = {},
    onCancelSpotIssues: () -> Unit = {},
    onSaveSpotIssues: () -> Unit = {},
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit = {},
    onFavoritesOnlyChosen: (Boolean) -> Unit = {},
    onExcludeStereoIssuesChosen: (Boolean) -> Unit = {},
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

    // Live drag corners while the crop rectangle is being drawn (left-half-local screen px, same
    // space Stereo3DCursorHost's cursor uses) - purely ephemeral UI state, reset whenever cropMode
    // goes false (Cancel/Save/Escape) so a stray in-progress drag never survives past that.
    var cropDragStartPx by remember(file) { mutableStateOf<Offset?>(null) }
    var cropDragCurrentPx by remember(file) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(cropMode) {
        if (!cropMode) {
            cropDragStartPx = null
            cropDragCurrentPx = null
        }
    }

    // Live drag corners while a "Spot stereo issues" rectangle is being drawn - same treatment as
    // cropDragStartPx/cropDragCurrentPx, but reset per spotIssuesMode rather than per rectangle
    // (see AppViewModel.addSpotIssueRect): the tool stays active after each release so another
    // rectangle can be drawn immediately.
    var spotIssueDragStartPx by remember(file) { mutableStateOf<Offset?>(null) }
    var spotIssueDragCurrentPx by remember(file) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(spotIssuesMode) {
        if (!spotIssuesMode) {
            spotIssueDragStartPx = null
            spotIssueDragCurrentPx = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val halfWidthPx = with(density) { (maxWidth / 2).toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }

        Stereo3DCursorHost(
            rectDragActive = (cropMode && cropRect == null) || spotIssuesMode,
            onRectDragChange = { start, current ->
                if (spotIssuesMode) {
                    spotIssueDragStartPx = start
                    spotIssueDragCurrentPx = current
                } else {
                    cropDragStartPx = start
                    cropDragCurrentPx = current
                }
            },
            onRectDragEnd = { start, end ->
                val bitmap = imageBitmap
                if (bitmap != null) {
                    val frac = computeDragFraction(bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx, start, end)
                    if (frac != null) {
                        if (spotIssuesMode) {
                            onSpotIssueRectAdded(IssueRectFraction(frac[0], frac[1], frac[2], frac[3]))
                        } else {
                            onCropRectFinalized(CropRectFraction(frac[0], frac[1], frac[2], frac[3]))
                        }
                    }
                }
                if (spotIssuesMode) {
                    spotIssueDragStartPx = null
                    spotIssueDragCurrentPx = null
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                imageBitmap?.let { bitmap ->
                    StereoImage(bitmap, halveLeftRightImages, manualAlignOffsetX, manualAlignOffsetY, cropRect)
                }
                val dragStart = cropDragStartPx
                val dragCurrent = cropDragCurrentPx
                if (dragStart != null && dragCurrent != null) {
                    CropDrawOverlay(dragStart, dragCurrent)
                }
                imageBitmap?.let { bitmap ->
                    SpotIssueRectsOverlayIfAny(
                        bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx,
                        spotIssueRects, spotIssueDragStartPx, spotIssueDragCurrentPx,
                    )
                }
                RawEditedLabelOverlay(file)
                SettingsMenuOverlay(
                    keepBestOfEachOnly,
                    favoritesOnly,
                    excludeStereoIssues,
                    halveLeftRightImages,
                    onKeepBestOfEachOnlyChosen,
                    onFavoritesOnlyChosen,
                    onExcludeStereoIssuesChosen,
                    onHalveLeftRightImagesChosen,
                    onExitFullscreen,
                    onNextImage,
                    onPreviousImage,
                    onToggleInfoPanel,
                )
                if (showInfoPanel) {
                    InfoPanel(
                        file,
                        onExifUpdated = { exifUpdateToken++ },
                        hasAlignedPreview = hasAlignedPreview,
                        isAligning = isAligning,
                        manualAlignMode = manualAlignMode,
                        hasManualOffset = manualAlignOffsetX != 0 || manualAlignOffsetY != 0,
                        cropMode = cropMode,
                        hasCropRect = cropRect != null,
                        spotIssuesMode = spotIssuesMode,
                        hasSpotIssueRects = spotIssueRects.isNotEmpty(),
                        onAutoAlign = onAutoAlign,
                        onCorrectZoom = onCorrectZoom,
                        onSaveAligned = onSaveAligned,
                        onStartManualAlign = onStartManualAlign,
                        onCancelManualAlign = onCancelManualAlign,
                        onSaveManualAlign = onSaveManualAlign,
                        onStartCrop = onStartCrop,
                        onCancelCrop = onCancelCrop,
                        onSaveCrop = onSaveCrop,
                        onStartSpotIssues = onStartSpotIssues,
                        onCancelSpotIssues = onCancelSpotIssues,
                        onSaveSpotIssues = onSaveSpotIssues,
                    )
                }
                ExifUpdatedToast(exifUpdateToken)
                AlignResultToast(alignToast)
                SaveResultToast(saveToast)
            }
        }
    }
    if (pendingNavigation != null) {
        UnsavedAlignedChangesDialog(
            onSave = onConfirmSaveAlignedAndNavigate,
            onDiscard = onDiscardAlignedPreviewAndNavigate,
            onCancel = onCancelPendingNavigation,
        )
    }
}

/**
 * Asks before Next/Previous (see AppViewModel.pendingNavigation) discards an unsaved auto-align/
 * correct-zoom preview - the preview only lives in memory (PhotoToolsState.alignedPreview) until
 * Save is pressed, so navigating away without asking would silently lose it. Plain (non-stereo-
 * duplicated) AlertDialog, same treatment as every other confirmation dialog in this app (e.g.
 * InfoPanel's stereo-issue-comment-erase dialog).
 */
@Composable
private fun UnsavedAlignedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.unsaved_align_changes_dialog_title)) },
        text = { Text(stringResource(Res.string.unsaved_align_changes_dialog_message)) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(Res.string.align_save_button)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(Res.string.discard_button)) }
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel_button)) }
            }
        },
    )
}

/**
 * Live feedback while dragging out a crop rectangle (see Exif3dInfoPanel's Crop button and
 * ImageScreen's onCropDragEnd): everything outside [dragStartPx]/[dragCurrentPx]'s rectangle is
 * blacked out and the rectangle itself gets a white outline, duplicated per half and offset by
 * [CropOverlayShiftPercent] like every other overlay - both corners are already in left-half-local
 * screen px (see Stereo3DCursorHost), so both halves draw the identical rectangle, just shifted
 * apart for the 3D read.
 */
@Composable
private fun CropDrawOverlay(dragStartPx: Offset, dragCurrentPx: Offset) {
    val rectPx = Rect(
        left = minOf(dragStartPx.x, dragCurrentPx.x),
        top = minOf(dragStartPx.y, dragCurrentPx.y),
        right = maxOf(dragStartPx.x, dragCurrentPx.x),
        bottom = maxOf(dragStartPx.y, dragCurrentPx.y),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * CropOverlayShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { CropDrawOverlayHalf(rectPx, offsetX = -shift / 2) }
            Box(Modifier.fillMaxSize().weight(1f)) { CropDrawOverlayHalf(rectPx, offsetX = shift / 2) }
        }
    }
}

@Composable
private fun CropDrawOverlayHalf(rectPx: Rect, offsetX: Dp) {
    Canvas(Modifier.fillMaxSize().offset(x = offsetX)) {
        val maskColor = Color.Black.copy(alpha = 0.7f)
        if (rectPx.top > 0f) {
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = Size(size.width, rectPx.top))
        }
        if (rectPx.bottom < size.height) {
            drawRect(maskColor, topLeft = Offset(0f, rectPx.bottom), size = Size(size.width, size.height - rectPx.bottom))
        }
        if (rectPx.left > 0f) {
            drawRect(maskColor, topLeft = Offset(0f, rectPx.top), size = Size(rectPx.left, rectPx.height))
        }
        if (rectPx.right < size.width) {
            drawRect(maskColor, topLeft = Offset(rectPx.right, rectPx.top), size = Size(size.width - rectPx.right, rectPx.height))
        }
        drawRect(Color.White, topLeft = rectPx.topLeft, size = rectPx.size, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Converts a raw drag (left-half-local screen px, same space Stereo3DCursorHost's cursor uses)
 * into a fraction (0..1) of one eye-half's width/height - shared by the crop tool's onCropRectFinalized
 * and "Spot stereo issues"' onSpotIssueRectAdded (see Stereo3DCursorHost's onRectDragEnd), since
 * both draw a rectangle by drag against the same full, uncropped eye-half (neither tool is
 * start-able while the other, or manual-align, is active - see AppViewModel.startCrop/startSpotIssues).
 * Returns null if the drag was too small ([MinRectDragPx], an accidental click rather than a
 * deliberate rectangle) or the image hasn't been measured yet.
 */
private fun computeDragFraction(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    halfWidthPx: Float,
    boxHeightPx: Float,
    start: Offset,
    end: Offset,
): FloatArray? {
    val left = minOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    val right = maxOf(start.x, end.x)
    val bottom = maxOf(start.y, end.y)
    if (right - left < MinRectDragPx || bottom - top < MinRectDragPx) return null
    val halfWidthSrc = bitmap.width / 2
    val effectiveWidthPx = if (halveLeftRightImages) halfWidthSrc / 2f else halfWidthSrc.toFloat()
    val scale = minOf(halfWidthPx / effectiveWidthPx, boxHeightPx / bitmap.height)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = bitmap.height * scale
    if (dstWidth <= 0f || dstHeight <= 0f) return null
    val dstOffsetX = (halfWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    val fx = ((left - dstOffsetX) / dstWidth).coerceIn(0f, 1f)
    val fy = ((top - dstOffsetY) / dstHeight).coerceIn(0f, 1f)
    val fRight = ((right - dstOffsetX) / dstWidth).coerceIn(0f, 1f)
    val fBottom = ((bottom - dstOffsetY) / dstHeight).coerceIn(0f, 1f)
    val fw = (fRight - fx).coerceAtLeast(0.01f)
    val fh = (fBottom - fy).coerceAtLeast(0.01f)
    return floatArrayOf(fx, fy, fw, fh)
}

/** Inverse of [computeDragFraction]'s scale/offset math: converts one already-drawn [IssueRectFraction]
 *  back into left-half-local screen px, so it can be redrawn as an overlay alongside the live drag. */
private fun issueRectToPx(rect: IssueRectFraction, bitmap: ImageBitmap, halveLeftRightImages: Boolean, halfWidthPx: Float, boxHeightPx: Float): Rect {
    val halfWidthSrc = bitmap.width / 2
    val effectiveWidthPx = if (halveLeftRightImages) halfWidthSrc / 2f else halfWidthSrc.toFloat()
    val scale = minOf(halfWidthPx / effectiveWidthPx, boxHeightPx / bitmap.height)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = bitmap.height * scale
    val dstOffsetX = (halfWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    val left = dstOffsetX + rect.x * dstWidth
    val top = dstOffsetY + rect.y * dstHeight
    return Rect(left, top, left + rect.width * dstWidth, top + rect.height * dstHeight)
}

/**
 * Renders every already-drawn "Spot stereo issues" rectangle plus the one currently being dragged
 * (if any), all in pink - see [SpotIssuePinkColor] (matches the color SpotStereoIssues.kt bakes
 * into the saved photo) and [SpotIssueOverlayShiftPercent]. No-op while nothing to show, same
 * "don't compose an empty overlay" treatment as RawEditedLabelOverlay.
 */
@Composable
private fun SpotIssueRectsOverlayIfAny(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    halfWidthPx: Float,
    boxHeightPx: Float,
    rects: List<IssueRectFraction>,
    liveDragStartPx: Offset?,
    liveDragCurrentPx: Offset?,
) {
    val screenRects = rects.map { issueRectToPx(it, bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx) }
    val liveRect = if (liveDragStartPx != null && liveDragCurrentPx != null) {
        Rect(
            left = minOf(liveDragStartPx.x, liveDragCurrentPx.x),
            top = minOf(liveDragStartPx.y, liveDragCurrentPx.y),
            right = maxOf(liveDragStartPx.x, liveDragCurrentPx.x),
            bottom = maxOf(liveDragStartPx.y, liveDragCurrentPx.y),
        )
    } else null
    val allRects = if (liveRect != null) screenRects + liveRect else screenRects
    if (allRects.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * SpotIssueOverlayShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { SpotIssueRectsOverlayHalf(allRects, offsetX = -shift / 2) }
            Box(Modifier.fillMaxSize().weight(1f)) { SpotIssueRectsOverlayHalf(allRects, offsetX = shift / 2) }
        }
    }
}

@Composable
private fun SpotIssueRectsOverlayHalf(rectsPx: List<Rect>, offsetX: Dp) {
    Canvas(Modifier.fillMaxSize().offset(x = offsetX)) {
        rectsPx.forEach { rectPx ->
            drawRect(SpotIssuePinkColor, topLeft = rectPx.topLeft, size = rectPx.size, style = Stroke(width = 3.dp.toPx()))
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
 * active - see PhotoToolsState.manualAlignOffsetX/Y) nudge the right half only, but rather than
 * leaving a blank gap where the shift no longer overlaps the left half, both halves are cropped
 * live to their common overlapping region - the same math ManualAlign.saveManualAlign applies to
 * the actual file at save time, so this preview is WYSIWYG.
 *
 * [cropRect], once the crop tool's rectangle has been released (see ImageScreen's onCropDragEnd),
 * further restricts both halves to that same relative fraction - same "only show what would be
 * saved" WYSIWYG treatment, and this is literally what makes the post-release screen "the preview
 * shows only the crop area": no separate cropped bitmap is materialized, this just draws a smaller
 * source rect. Mutually exclusive with [manualAlignOffsetX]/[manualAlignOffsetY] in practice (the
 * crop tool can't be started while manual-align is active - see AppViewModel.startCrop), so when
 * present it simply overrides the dx/dy-derived region instead of composing with it.
 */
@Composable
private fun StereoImage(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    manualAlignOffsetX: Int = 0,
    manualAlignOffsetY: Int = 0,
    cropRect: CropRectFraction? = null,
) {
    val halfWidth = bitmap.width / 2
    val heightPx = bitmap.height
    val dx = manualAlignOffsetX.coerceIn(-(halfWidth - 1).coerceAtLeast(0), (halfWidth - 1).coerceAtLeast(0))
    val dy = manualAlignOffsetY.coerceIn(-(heightPx - 1).coerceAtLeast(0), (heightPx - 1).coerceAtLeast(0))
    val cropWidth = halfWidth - abs(dx)
    val cropHeight = heightPx - abs(dy)
    var leftOffset = IntOffset(maxOf(dx, 0), maxOf(dy, 0))
    var rightOffset = IntOffset(halfWidth + maxOf(-dx, 0), maxOf(-dy, 0))
    var cropSize = IntSize(cropWidth, cropHeight)
    if (cropRect != null) {
        val rx = (cropRect.x * halfWidth).toInt().coerceIn(0, halfWidth - 1)
        val ry = (cropRect.y * heightPx).toInt().coerceIn(0, heightPx - 1)
        val rw = (cropRect.width * halfWidth).toInt().coerceIn(1, halfWidth - rx)
        val rh = (cropRect.height * heightPx).toInt().coerceIn(1, heightPx - ry)
        leftOffset = IntOffset(rx, ry)
        rightOffset = IntOffset(halfWidth + rx, ry)
        cropSize = IntSize(rw, rh)
    }
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
    favoritesOnly: Boolean,
    excludeStereoIssues: Boolean,
    halveLeftRightImages: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onFavoritesOnlyChosen: (Boolean) -> Unit,
    onExcludeStereoIssuesChosen: (Boolean) -> Unit,
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
                SettingsMenuHalf(offsetX = -shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, favoritesOnly, excludeStereoIssues, halveLeftRightImages, onKeepBestOfEachOnlyChosen, onFavoritesOnlyChosen, onExcludeStereoIssuesChosen, onHalveLeftRightImagesChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, favoritesOnly, excludeStereoIssues, halveLeftRightImages, onKeepBestOfEachOnlyChosen, onFavoritesOnlyChosen, onExcludeStereoIssuesChosen, onHalveLeftRightImagesChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel)
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
    favoritesOnly: Boolean,
    excludeStereoIssues: Boolean,
    halveLeftRightImages: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onFavoritesOnlyChosen: (Boolean) -> Unit,
    onExcludeStereoIssuesChosen: (Boolean) -> Unit,
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
                    // Grouped together (tight spacing, no dividing line needed) since all three
                    // narrow down which photos Next/Previous land on - kept visually distinct from
                    // the unrelated toggles/actions below via the wider gap after the group.
                    Column {
                        SettingsMenuToggleRow(stringResource(Res.string.image_settings_keep_best_of_each_toggle_label), keepBestOfEachOnly, onKeepBestOfEachOnlyChosen)
                        Spacer(Modifier.height(8.dp))
                        SettingsMenuToggleRow(stringResource(Res.string.image_settings_favorites_only_toggle_label), favoritesOnly, onFavoritesOnlyChosen)
                        Spacer(Modifier.height(8.dp))
                        SettingsMenuToggleRow(stringResource(Res.string.image_settings_exclude_stereo_issues_toggle_label), excludeStereoIssues, onExcludeStereoIssuesChosen)
                    }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuToggleRow(stringResource(Res.string.image_settings_halve_left_right_toggle_label), halveLeftRightImages, onHalveLeftRightImagesChosen)
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
private fun SettingsMenuToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier.width(220.dp),
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .focusProperties { canFocus = false }
                .cursor3DClickTarget { onCheckedChange(!checked) },
        )
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
