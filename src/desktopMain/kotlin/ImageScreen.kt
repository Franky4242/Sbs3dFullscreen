import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.image_settings_keep_best_of_each_toggle_label
import sbs3dfullscreen.resources.image_settings_menu_content_description
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
    alignToast: AlignToast? = null,
    saveToast: SaveToast? = null,
    keepBestOfEachOnly: Boolean = false,
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit = {},
) {
    val fileBitmap = remember(file) {
        file.readBytes().decodeToImageBitmap()
    }
    val imageBitmap = overrideBitmap ?: fileBitmap

    // Hosted here rather than inside Exif3dInfoPanel so the toast survives the panel being
    // toggled closed (pressing Shift/Ctrl again), which removes Exif3dInfoPanel (and any state
    // it holds) from composition.
    var exifUpdateToken by remember(file) { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        RawEditedLabelOverlay(file)
        SettingsMenuOverlay(keepBestOfEachOnly, onKeepBestOfEachOnlyChosen)
        if (showInfoPanel) {
            Exif3dInfoPanel(
                file,
                onExifUpdated = { exifUpdateToken++ },
                hasAlignedPreview = hasAlignedPreview,
                onAutoAlign = onAutoAlign,
                onCorrectZoom = onCorrectZoom,
                onSaveAligned = onSaveAligned,
            )
        }
        ExifUpdatedToast(exifUpdateToken)
        AlignResultToast(alignToast)
        SaveResultToast(saveToast)
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
private fun SettingsMenuOverlay(keepBestOfEachOnly: Boolean, onKeepBestOfEachOnlyChosen: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * SettingsMenuShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = -shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, onKeepBestOfEachOnlyChosen)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, onKeepBestOfEachOnlyChosen)
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
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
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
                    .clickable(onClick = onToggleExpanded),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(Res.string.image_settings_menu_content_description),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusProperties { canFocus = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.image_settings_keep_best_of_each_toggle_label),
                        style = TextStyle(color = Color.White, fontSize = 14.sp),
                        modifier = Modifier.width(220.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = keepBestOfEachOnly,
                        onCheckedChange = onKeepBestOfEachOnlyChosen,
                        modifier = Modifier.focusProperties { canFocus = false },
                    )
                }
            }
        }
    }
}
