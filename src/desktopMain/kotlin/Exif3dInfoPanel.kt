import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.camera3d.camera.shared.Desc3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.align_auto_align_button
import sbs3dfullscreen.resources.align_correct_zoom_button
import sbs3dfullscreen.resources.align_finished_failed
import sbs3dfullscreen.resources.align_finished_success
import sbs3dfullscreen.resources.align_manual_align_button
import sbs3dfullscreen.resources.align_save_button
import sbs3dfullscreen.resources.cancel_button
import sbs3dfullscreen.resources.crop_button
import sbs3dfullscreen.resources.dialog_title_warning
import sbs3dfullscreen.resources.enter_the_picture_legend
import sbs3dfullscreen.resources.save_finished_failed
import sbs3dfullscreen.resources.save_finished_success
import sbs3dfullscreen.resources.spot_stereo_issues_button
import sbs3dfullscreen.resources.exif_updated
import sbs3dfullscreen.resources.ic_add_comment
import sbs3dfullscreen.resources.ic_image_comment
import sbs3dfullscreen.resources.ic_text_comment
import sbs3dfullscreen.resources.ok_button
import sbs3dfullscreen.resources.outline_3d_24
import sbs3dfullscreen.resources.panel_base_measurement
import sbs3dfullscreen.resources.panel_device_count
import sbs3dfullscreen.resources.panel_mode
import sbs3dfullscreen.resources.panel_trigger
import sbs3dfullscreen.resources.stereo_issue_warning_comment_hint
import sbs3dfullscreen.resources.stereo_issue_warning_comment_title
import sbs3dfullscreen.resources.stereo_issue_warning_comment_will_be_erased
import java.io.File

// Same sign convention as Playlist's titleZPercent/subtitleZPercent (negative = toward the
// viewer): -1% makes the panel read as floating just in front of the screen rather than behind it.
private const val InfoPanelShiftPercent = -0.01f

private val WarningColor = Color(0xFFFF9800)
private val OutlinedColor = Color(0xFF9E9E9E)
private val IconTextSpacing = 6.dp

private data class Exif3dSummary(val desc: Desc3d, val copyright: String, val comment: String)

// Mirrors Android's LegendIconType (fr.camera3d.camera.common.ui_components.LegendIcon): which
// bubble glyph to show for the legend button, derived the same way FullscreenViewerFragment
// derives Photo3d.legendType - a non-empty comment wins over the hasLegend flag. Named distinctly
// from GalleryScreen.kt's own file-private LegendIconType: both files share the default package,
// and two file-private top-level classes with the same name collide as JVM classes.
private enum class LegendButtonType { AddLegend, TextLegend, ImageLegend }

private fun legendButtonType(summary: Exif3dSummary): LegendButtonType = when {
    summary.comment.isNotEmpty() -> LegendButtonType.TextLegend
    summary.desc.hasLegend -> LegendButtonType.ImageLegend
    else -> LegendButtonType.AddLegend
}

/**
 * Stereo HUD toggled open/closed by pressing Shift or Ctrl over [ImageScreen]: the same 3D EXIF tags
 * FullscreenViewerFragment's characteristics panel shows on Android (favorite, stereo-issue
 * warning, legend, base/device/mode/trigger), duplicated on both halves of the screen and offset
 * by [InfoPanelShiftPercent] of half the screen width so it reads correctly in 3D - same
 * left/right-duplication technique as ComposablePortableTitleSlide.
 */
@Composable
fun Exif3dInfoPanel(
    file: File,
    onExifUpdated: () -> Unit = {},
    hasAlignedPreview: Boolean = false,
    isAligning: Boolean = false,
    manualAlignMode: Boolean = false,
    hasManualOffset: Boolean = false,
    cropMode: Boolean = false,
    hasCropRect: Boolean = false,
    spotIssuesMode: Boolean = false,
    hasSpotIssueRects: Boolean = false,
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onStartManualAlign: () -> Unit = {},
    onCancelManualAlign: () -> Unit = {},
    onSaveManualAlign: () -> Unit = {},
    onStartCrop: () -> Unit = {},
    onCancelCrop: () -> Unit = {},
    onSaveCrop: () -> Unit = {},
    onStartSpotIssues: () -> Unit = {},
    onCancelSpotIssues: () -> Unit = {},
    onSaveSpotIssues: () -> Unit = {},
) {
    // Read off the UI thread and show a spinner meanwhile: EXIF I/O is normally fast, but this
    // guards against a slow/network drive stalling the toggled-open HUD from appearing at all.
    var summary by remember(file) { mutableStateOf<Exif3dSummary?>(null) }
    LaunchedEffect(file) {
        summary = withContext(Dispatchers.IO) {
            Exif3dSummary(
                desc = Exif3d.get3dCameraCharacteristics(file) ?: Desc3d(),
                copyright = Exif.getExifCopyright(file),
                comment = Exif.getExifUserComment(file) ?: "",
            )
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val onToggleFavorite: () -> Unit = onToggleFavorite@{
        val current = summary ?: return@onToggleFavorite
        val newFavorite = !current.desc.favorite
        summary = current.copy(desc = current.desc.copy(favorite = newFavorite))
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setFavoriteInExif(file, newFavorite)
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }
    val onSetWarning: (Boolean, String) -> Unit = onSetWarning@{ warning, comment ->
        val current = summary ?: return@onSetWarning
        val newDesc = if (warning) current.desc.copy(warning = true, warningComment = comment)
        else current.desc.copy(warning = false, warningComment = "")
        summary = current.copy(desc = newDesc)
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setWarningInExif(file, warning, comment.takeIf { warning })
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }
    val onSetLegend: (String) -> Unit = onSetLegend@{ text ->
        val current = summary ?: return@onSetLegend
        summary = current.copy(comment = text, desc = current.desc.copy(hasLegend = false))
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setTextLegendInExif(file, text)
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }

    // Mirrors Android's StereoIssueWarningToggleHandler: turning the warning on always asks for a
    // comment first, turning it off asks for confirmation only if a comment would be lost.
    var showWarningCommentDialog by remember(file) { mutableStateOf(false) }
    var showWarningEraseDialog by remember(file) { mutableStateOf(false) }
    val onWarningToggleRequest: (Boolean) -> Unit = { turnOn ->
        if (turnOn) {
            showWarningCommentDialog = true
        } else if (summary?.desc?.warningComment?.isNotEmpty() == true) {
            showWarningEraseDialog = true
        } else {
            onSetWarning(false, "")
        }
    }
    // No camera/OCR scan on desktop (unlike ScanLegendFragment's Acquire/Confirm/Crop steps), so
    // the legend button only ports the "type the legend text directly" path (EnterLegendScreen).
    var showLegendDialog by remember(file) { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * InfoPanelShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                Exif3dInfoPanelHalf(summary, offsetX = -shift / 2, onToggleFavorite, onWarningToggleRequest, { showLegendDialog = true }, hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, onAutoAlign, onCorrectZoom, onSaveAligned, onStartManualAlign, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                Exif3dInfoPanelHalf(summary, offsetX = shift / 2, onToggleFavorite, onWarningToggleRequest, { showLegendDialog = true }, hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, onAutoAlign, onCorrectZoom, onSaveAligned, onStartManualAlign, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues)
            }
        }
    }

    if (showWarningCommentDialog) {
        StereoIssueCommentDialog(
            initialComment = summary?.desc?.warningComment ?: "",
            onDismiss = { showWarningCommentDialog = false },
            onConfirm = { comment ->
                showWarningCommentDialog = false
                onSetWarning(true, comment)
            },
        )
    }
    if (showWarningEraseDialog) {
        AlertDialog(
            onDismissRequest = { showWarningEraseDialog = false },
            title = { Text(stringResource(Res.string.dialog_title_warning)) },
            text = { Text(stringResource(Res.string.stereo_issue_warning_comment_will_be_erased)) },
            confirmButton = {
                TextButton(onClick = { showWarningEraseDialog = false; onSetWarning(false, "") }) {
                    Text(stringResource(Res.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningEraseDialog = false }) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
    if (showLegendDialog) {
        LegendTextDialog(
            initialText = summary?.comment ?: "",
            onDismiss = { showLegendDialog = false },
            onConfirm = { text ->
                showLegendDialog = false
                onSetLegend(text)
            },
        )
    }
}

@Composable
private fun StereoIssueCommentDialog(initialComment: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(initialComment) { mutableStateOf(initialComment) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.stereo_issue_warning_comment_title)) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(Res.string.stereo_issue_warning_comment_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(Res.string.ok_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel_button)) } },
    )
}

@Composable
private fun LegendTextDialog(initialText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.enter_the_picture_legend)) },
        text = { TextField(value = text, onValueChange = { text = it }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(Res.string.ok_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel_button)) } },
    )
}

@Composable
private fun Exif3dInfoPanelHalf(
    summary: Exif3dSummary?,
    offsetX: Dp,
    onToggleFavorite: () -> Unit,
    onWarningToggleRequest: (Boolean) -> Unit,
    onLegendClick: () -> Unit,
    hasAlignedPreview: Boolean,
    isAligning: Boolean,
    manualAlignMode: Boolean,
    hasManualOffset: Boolean,
    cropMode: Boolean,
    hasCropRect: Boolean,
    spotIssuesMode: Boolean,
    hasSpotIssueRects: Boolean,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
    onStartManualAlign: () -> Unit,
    onCancelManualAlign: () -> Unit,
    onSaveManualAlign: () -> Unit,
    onStartCrop: () -> Unit,
    onCancelCrop: () -> Unit,
    onSaveCrop: () -> Unit,
    onStartSpotIssues: () -> Unit,
    onCancelSpotIssues: () -> Unit,
    onSaveSpotIssues: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, bottom = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(12.dp),
        ) {
            if (summary == null) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                return@Box
            }
            Column {
                // Hidden while cropping, spotting stereo issues, or manually nudging: the panel
                // then only needs to show Cancel/Save (see AlignButtonsRow below) - the
                // favorite/warning/legend icons, 3D info, comment and copyright are just noise
                // while the user is focused on the rectangle/nudge, and this row is
                // width-constrained to half the screen (see Exif3dInfoPanel's BoxWithConstraints)
                // so dropping it also gives AlignButtonsRow more room.
                if (!cropMode && !manualAlignMode && !spotIssuesMode) {
                    Exif3dInfoPanelContent(summary, onToggleFavorite, onWarningToggleRequest, onLegendClick)
                }
                AlignButtonsRow(hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, onAutoAlign, onCorrectZoom, onSaveAligned, onStartManualAlign, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues)
            }
        }
    }
}

@Composable
private fun AlignButtonsRow(
    hasAlignedPreview: Boolean,
    isAligning: Boolean,
    manualAlignMode: Boolean,
    hasManualOffset: Boolean,
    cropMode: Boolean,
    hasCropRect: Boolean,
    spotIssuesMode: Boolean,
    hasSpotIssueRects: Boolean,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
    onStartManualAlign: () -> Unit,
    onCancelManualAlign: () -> Unit,
    onSaveManualAlign: () -> Unit,
    onStartCrop: () -> Unit,
    onCancelCrop: () -> Unit,
    onSaveCrop: () -> Unit,
    onStartSpotIssues: () -> Unit,
    onCancelSpotIssues: () -> Unit,
    onSaveSpotIssues: () -> Unit,
) {
    // Material3's default disabled Button colors (a low-alpha tint of MaterialTheme's onSurface,
    // meant for a light/dark *theme surface* background) go nearly invisible against this panel's
    // own Color.Black.copy(alpha = 0.5f) background - MaterialTheme here is the default light
    // scheme, so disabled text ends up dark-on-dark. Every Button below gets this override instead,
    // so a disabled Save/Cancel/etc. still reads as clearly present (dim, but visible), matching
    // "greyed out, not hidden" for every tool's Save button while nothing's been drawn/moved yet.
    val panelButtonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = Color.White.copy(alpha = 0.15f),
        disabledContentColor = Color.White.copy(alpha = 0.6f),
    )
    // FlowRow (not Row) + widthIn(max = ...) so this wraps onto multiple lines instead of
    // overflowing/clipping past the panel's edge - there are up to 6 buttons in the "no tool
    // active" state (Auto Align/Correct Zoom/Manual Align/Save/Crop/Spot stereo issues), too many
    // to fit on one line within the half-screen-constrained panel (see Exif3dInfoPanel's
    // BoxWithConstraints). Item-to-item spacing (both within and between lines) comes from
    // horizontalArrangement/verticalArrangement, so the individual Spacer(8.dp) calls the old
    // single-Row layout needed between buttons are gone.
    FlowRow(
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 340.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // canFocus = false for the same reason as the favorite icon above: this HUD can be
        // toggled closed by pressing Shift/Ctrl again, so a click stealing keyboard focus would
        // break Escape/arrow key handling on Main.kt's root Box as soon as it does.
        // Auto Align/Correct Zoom/the auto-align Save button are hidden entirely (not just
        // disabled) while manual-align mode OR crop mode OR spot-issues mode is active - not only
        // are they irrelevant then (see AppViewModel.manualAlignMode/cropMode/spotIssuesMode), but
        // this row is width-constrained (see widthIn above), and too many buttons at once would
        // still push the Cancel/Save pair onto its own line.
        if (!manualAlignMode && !cropMode && !spotIssuesMode) {
            // Disabled for the whole duration of a running auto-align/correct-zoom/save task (see
            // AppViewModel.isAligning) so a click can't re-trigger or overlap it.
            Button(
                onClick = onAutoAlign,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onAutoAlign() },
            ) {
                Text(stringResource(Res.string.align_auto_align_button))
            }
            Button(
                onClick = onCorrectZoom,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCorrectZoom() },
            ) {
                Text(stringResource(Res.string.align_correct_zoom_button))
            }
        }
        if (!cropMode && !spotIssuesMode) {
            Button(
                onClick = onStartManualAlign,
                enabled = !isAligning && !manualAlignMode,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning && !manualAlignMode) onStartManualAlign() },
            ) {
                Text(stringResource(Res.string.align_manual_align_button))
            }
        }
        if (!manualAlignMode && !cropMode && !spotIssuesMode) {
            Button(
                onClick = onSaveAligned,
                enabled = hasAlignedPreview && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasAlignedPreview && !isAligning) onSaveAligned() },
            ) {
                Text(stringResource(Res.string.align_save_button))
            }
        }
        // Cancel/Save pair for the in-progress manual nudge - arrow keys move the right half while
        // this is up (see Main.kt's onPreviewKeyEvent); Save is only enabled once something has
        // actually moved, so an accidental click can't write out an identical duplicate file.
        if (manualAlignMode) {
            Button(
                onClick = onCancelManualAlign,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelManualAlign() },
            ) {
                Text(stringResource(Res.string.cancel_button))
            }
            Button(
                onClick = onSaveManualAlign,
                enabled = hasManualOffset && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasManualOffset && !isAligning) onSaveManualAlign() },
            ) {
                Text(stringResource(Res.string.align_save_button))
            }
        }
        if (!manualAlignMode && !cropMode && !spotIssuesMode) {
            Button(
                onClick = onStartCrop,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onStartCrop() },
            ) {
                Text(stringResource(Res.string.crop_button))
            }
        }
        // Cancel/Save pair for the crop tool - Save is only enabled once a rectangle has actually
        // been drawn (see AppViewModel.cropRect), same "nothing to commit yet" gating as the
        // manual-align pair above.
        if (cropMode) {
            Button(
                onClick = onCancelCrop,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelCrop() },
            ) {
                Text(stringResource(Res.string.cancel_button))
            }
            Button(
                onClick = onSaveCrop,
                enabled = hasCropRect && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasCropRect && !isAligning) onSaveCrop() },
            ) {
                Text(stringResource(Res.string.align_save_button))
            }
        }
        if (!manualAlignMode && !cropMode && !spotIssuesMode) {
            Button(
                onClick = onStartSpotIssues,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onStartSpotIssues() },
            ) {
                Text(stringResource(Res.string.spot_stereo_issues_button))
            }
        }
        // Cancel/Save pair for "Spot stereo issues" - Save is only enabled once at least one
        // rectangle has been drawn (see AppViewModel.spotIssueRects), same "nothing to commit yet"
        // gating as the crop/manual-align pairs above. Unlike the crop pair, the tool stays active
        // (rather than moving to review-only) after each rectangle so further ones can be drawn.
        if (spotIssuesMode) {
            Button(
                onClick = onCancelSpotIssues,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelSpotIssues() },
            ) {
                Text(stringResource(Res.string.cancel_button))
            }
            Button(
                onClick = onSaveSpotIssues,
                enabled = hasSpotIssueRects && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasSpotIssueRects && !isAligning) onSaveSpotIssues() },
            ) {
                Text(stringResource(Res.string.align_save_button))
            }
        }
        if (isAligning) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Exif3dInfoPanelContent(
    summary: Exif3dSummary,
    onToggleFavorite: () -> Unit,
    onWarningToggleRequest: (Boolean) -> Unit,
    onLegendClick: () -> Unit,
) {
    val desc = summary.desc
    val has3dData = desc.baseMm != -1 || desc.triggerMode.isNotEmpty() || desc.extMode.isNotEmpty() || desc.deviceCount != 2
    Column(horizontalAlignment = Alignment.Start) {
        // Mirrors Android's FavoriteAndStereoIssueWarningAndLegendIconBar. clickable() makes an
        // icon focusable by default; since this HUD can be toggled closed by pressing Shift/Ctrl
        // again, letting a click steal keyboard focus away from Main.kt's root Box breaks all key
        // handling (Escape included) once it does and this panel - along with the now-focused
        // icon - leaves composition.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (desc.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (desc.favorite) Color.Red else Color.White,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable(onClick = onToggleFavorite)
                    .cursor3DClickTarget(onToggleFavorite),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = if (desc.warning) WarningColor else OutlinedColor,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable { onWarningToggleRequest(!desc.warning) }
                    .cursor3DClickTarget { onWarningToggleRequest(!desc.warning) },
            )
            Spacer(Modifier.width(4.dp))
            val legendPainter = when (legendButtonType(summary)) {
                LegendButtonType.AddLegend -> painterResource(Res.drawable.ic_add_comment)
                LegendButtonType.TextLegend -> painterResource(Res.drawable.ic_text_comment)
                LegendButtonType.ImageLegend -> painterResource(Res.drawable.ic_image_comment)
            }
            Icon(
                painter = legendPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable(onClick = onLegendClick)
                    .cursor3DClickTarget(onLegendClick),
            )
        }
        if (has3dData) {
            val threeDIconSize = 22.dp
            val lines = listOfNotNull(
                desc.baseMm.takeIf { it != -1 }?.let { stringResource(Res.string.panel_base_measurement, "%.1f".format(it / 10f)) },
                stringResource(Res.string.panel_device_count, desc.deviceCount),
                desc.extMode.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.panel_mode, it) },
                desc.triggerMode.takeIf { it.isNotEmpty() && desc.deviceCount != 1 }?.let { stringResource(Res.string.panel_trigger, it) },
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(Res.drawable.outline_3d_24),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = IconTextSpacing).size(threeDIconSize),
                    )
                    ShadowedText(lines.first())
                }
                lines.drop(1).forEach { line -> ShadowedText(line, modifier = Modifier.padding(start = threeDIconSize + IconTextSpacing)) }
            }
        }
        if (summary.comment.isNotEmpty()) {
            val commentIconSize = 22.dp
            val commentLines = summary.comment.split("\n")
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_text_comment),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = IconTextSpacing).offset(y = 3.dp).size(commentIconSize),
                    )
                    ShadowedText(commentLines.first())
                }
                commentLines.drop(1).forEach { line -> ShadowedText(line, modifier = Modifier.padding(start = commentIconSize + IconTextSpacing)) }
            }
        } else if (desc.hasLegend) {
            Icon(painter = painterResource(Res.drawable.ic_image_comment), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (desc.warning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WarningColor,
                    modifier = Modifier.padding(end = IconTextSpacing).size(22.dp),
                )
                if (desc.warningComment.isNotEmpty()) {
                    ShadowedText(desc.warningComment)
                }
            }
        }
        if (summary.copyright.isNotEmpty()) {
            ShadowedText("© ${summary.copyright}")
        }
    }
}

@Composable
private fun ShadowedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = Color.White,
            fontSize = 22.sp,
            shadow = Shadow(color = Color.Black, blurRadius = 3f, offset = Offset(2f, 2f)),
        ),
    )
}

/**
 * Shared shell for the toasts below: fades [content] in when [trigger] changes to a non-null
 * value, holds it for 1.5s, then fades out - duplicated on both halves and offset by [shiftPercent]
 * of half the screen width like [Exif3dInfoPanel] so it reads correctly in 3D (same sign
 * convention: negative brings it toward the viewer). [trigger] doubles as both the "is something
 * to show" flag and the [LaunchedEffect] restart key, so passing a fresh (non-equal) value
 * re-flashes the toast even if the outcome is the same as last time (e.g. two failures in a row).
 */
@Composable
private fun <T : Any> StereoToast(trigger: T?, shiftPercent: Float = InfoPanelShiftPercent, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfWidth = maxWidth / 2
            val shift = halfWidth * shiftPercent
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) { StereoToastHalf(offsetX = -shift / 2, content) }
                Box(Modifier.fillMaxSize().weight(1f)) { StereoToastHalf(offsetX = shift / 2, content) }
            }
        }
    }
}

@Composable
private fun StereoToastHalf(offsetX: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().offset(x = offsetX),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            content()
        }
    }
}

/**
 * Brief confirmation flashed after an EXIF write (e.g. toggling favorite) completes. Bump
 * [updateToken] (e.g. increment a counter) each time a write finishes to (re)start the fade-out
 * timer via [StereoToast]. Unlike [Exif3dInfoPanel] this is meant to survive the panel being
 * toggled closed, so callers should host [updateToken] outside the Shift/Ctrl-toggled composition
 * (see ImageScreen.kt).
 */
@Composable
fun ExifUpdatedToast(updateToken: Int, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = updateToken.takeIf { it != 0 }, shiftPercent = shiftPercent) {
        Text(stringResource(Res.string.exif_updated), color = Color.White, fontSize = 18.sp)
    }
}

/**
 * Brief confirmation flashed after an auto-align/correct-zoom attempt (triggered from either the
 * Exif3dInfoPanel buttons or the "A" keyboard shortcut in Main.kt) finishes, success or failure -
 * bump AppViewModel.alignToast's token to (re)start the fade-out timer via [StereoToast]. Same
 * hosting rationale as [ExifUpdatedToast]: it must survive the panel being toggled closed, so
 * callers host it outside the Shift/Ctrl-toggled composition (see ImageScreen.kt).
 */
@Composable
fun AlignResultToast(toast: AlignToast?, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = toast, shiftPercent = shiftPercent) {
        val message = if (toast?.success == true) {
            stringResource(Res.string.align_finished_success)
        } else {
            stringResource(Res.string.align_finished_failed)
        }
        Text(message, color = if (toast?.success == true) Color.White else WarningColor, fontSize = 18.sp)
    }
}

/**
 * Brief confirmation flashed after a "Save" button click (see AlignButtonsRow's onSaveAligned)
 * finishes, success or failure - bump AppViewModel.saveToast's token to (re)start the fade-out
 * timer via [StereoToast]. Same hosting rationale as [ExifUpdatedToast]: it must survive the
 * panel being toggled closed, so callers host it outside the Shift/Ctrl-toggled composition (see
 * ImageScreen.kt).
 */
@Composable
fun SaveResultToast(toast: SaveToast?, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = toast, shiftPercent = shiftPercent) {
        val message = if (toast?.success == true) {
            stringResource(Res.string.save_finished_success)
        } else {
            stringResource(Res.string.save_finished_failed)
        }
        Text(message, color = if (toast?.success == true) Color.White else WarningColor, fontSize = 18.sp)
    }
}
