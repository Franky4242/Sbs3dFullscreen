import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import sbs3dfullscreen.resources.align_save_button
import sbs3dfullscreen.resources.save_finished_failed
import sbs3dfullscreen.resources.save_finished_success
import sbs3dfullscreen.resources.exif_updated
import sbs3dfullscreen.resources.ic_image_comment
import sbs3dfullscreen.resources.ic_text_comment
import sbs3dfullscreen.resources.outline_3d_24
import sbs3dfullscreen.resources.panel_base_measurement
import sbs3dfullscreen.resources.panel_device_count
import sbs3dfullscreen.resources.panel_mode
import sbs3dfullscreen.resources.panel_trigger
import java.io.File

// Same sign convention as Playlist's titleZPercent/subtitleZPercent (negative = toward the
// viewer): -1% makes the panel read as floating just in front of the screen rather than behind it.
private const val InfoPanelShiftPercent = -0.01f

private val WarningColor = Color(0xFFFF9800)
private val IconTextSpacing = 6.dp

private data class Exif3dSummary(val desc: Desc3d, val copyright: String, val comment: String)

/**
 * Read-only stereo HUD shown while Shift/Ctrl is held over [ImageScreen]: the same 3D EXIF tags
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
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
) {
    // Read off the UI thread and show a spinner meanwhile: EXIF I/O is normally fast, but this
    // guards against a slow/network drive stalling the held-Shift/Ctrl HUD from appearing at all.
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * InfoPanelShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                Exif3dInfoPanelHalf(summary, offsetX = -shift / 2, onToggleFavorite, hasAlignedPreview, onAutoAlign, onCorrectZoom, onSaveAligned)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                Exif3dInfoPanelHalf(summary, offsetX = shift / 2, onToggleFavorite, hasAlignedPreview, onAutoAlign, onCorrectZoom, onSaveAligned)
            }
        }
    }
}

@Composable
private fun Exif3dInfoPanelHalf(
    summary: Exif3dSummary?,
    offsetX: Dp,
    onToggleFavorite: () -> Unit,
    hasAlignedPreview: Boolean,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
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
                Exif3dInfoPanelContent(summary, onToggleFavorite)
                AlignButtonsRow(hasAlignedPreview, onAutoAlign, onCorrectZoom, onSaveAligned)
            }
        }
    }
}

@Composable
private fun AlignButtonsRow(
    hasAlignedPreview: Boolean,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
) {
    Row(modifier = Modifier.padding(top = 8.dp)) {
        // canFocus = false for the same reason as the favorite icon above: this HUD only exists
        // while Shift/Ctrl is held, so a click stealing keyboard focus would break Escape/arrow
        // key handling on Main.kt's root Box as soon as the modifier is released.
        Button(onClick = onAutoAlign, modifier = Modifier.focusProperties { canFocus = false }) {
            Text(stringResource(Res.string.align_auto_align_button))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onCorrectZoom, modifier = Modifier.focusProperties { canFocus = false }) {
            Text(stringResource(Res.string.align_correct_zoom_button))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSaveAligned, enabled = hasAlignedPreview, modifier = Modifier.focusProperties { canFocus = false }) {
            Text(stringResource(Res.string.align_save_button))
        }
    }
}

@Composable
private fun Exif3dInfoPanelContent(summary: Exif3dSummary, onToggleFavorite: () -> Unit) {
    val desc = summary.desc
    val has3dData = desc.baseMm != -1 || desc.triggerMode.isNotEmpty() || desc.extMode.isNotEmpty() || desc.deviceCount != 2
    Column(horizontalAlignment = Alignment.Start) {
        Icon(
            imageVector = if (desc.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = if (desc.favorite) Color.Red else Color.White,
            // clickable() makes the icon focusable by default; since this HUD only exists while
            // Shift/Ctrl is held, letting a click steal keyboard focus away from Main.kt's root
            // Box breaks all key handling (Escape included) as soon as the modifier is released
            // and this panel - along with the now-focused icon - leaves composition.
            modifier = Modifier.size(28.dp).focusProperties { canFocus = false }.clickable(onClick = onToggleFavorite),
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(Res.drawable.ic_text_comment),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(end = IconTextSpacing).offset(y = 3.dp).size(22.dp),
                )
                ShadowedText(summary.comment)
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
 * timer via [StereoToast]. Unlike [Exif3dInfoPanel] this is meant to survive Shift/Ctrl being
 * released, so callers should host [updateToken] outside the Shift/Ctrl-gated composition (see
 * ImageScreen.kt).
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
 * hosting rationale as [ExifUpdatedToast]: it must survive Shift/Ctrl being released, so callers
 * host it outside the Shift/Ctrl-gated composition (see ImageScreen.kt).
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
 * timer via [StereoToast]. Same hosting rationale as [ExifUpdatedToast]: it must survive
 * Shift/Ctrl being released, so callers host it outside the Shift/Ctrl-gated composition (see
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
