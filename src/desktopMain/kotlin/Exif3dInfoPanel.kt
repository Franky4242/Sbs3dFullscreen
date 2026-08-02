import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Warning
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
fun Exif3dInfoPanel(file: File, onExifUpdated: () -> Unit = {}) {
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
            Box(Modifier.fillMaxSize().weight(1f)) { Exif3dInfoPanelHalf(summary, offsetX = -shift / 2, onToggleFavorite) }
            Box(Modifier.fillMaxSize().weight(1f)) { Exif3dInfoPanelHalf(summary, offsetX = shift / 2, onToggleFavorite) }
        }
    }
}

@Composable
private fun Exif3dInfoPanelHalf(summary: Exif3dSummary?, offsetX: Dp, onToggleFavorite: () -> Unit) {
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
            Exif3dInfoPanelContent(summary, onToggleFavorite)
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
 * Brief confirmation flashed after an EXIF write (e.g. toggling favorite) completes. Bump
 * [updateToken] (e.g. increment a counter) each time a write finishes to (re)start the fade-out
 * timer. Duplicated on both halves and offset like [Exif3dInfoPanel] so it reads correctly in 3D;
 * unlike that panel it's meant to survive Shift/Ctrl being released, so callers should host
 * [updateToken] outside the Shift/Ctrl-gated composition (see ImageScreen.kt).
 */
@Composable
fun ExifUpdatedToast(updateToken: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(updateToken) {
        if (updateToken == 0) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfWidth = maxWidth / 2
            val shift = halfWidth * InfoPanelShiftPercent
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) { ExifUpdatedToastHalf(offsetX = -shift / 2) }
                Box(Modifier.fillMaxSize().weight(1f)) { ExifUpdatedToastHalf(offsetX = shift / 2) }
            }
        }
    }
}

@Composable
private fun ExifUpdatedToastHalf(offsetX: Dp) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(Res.string.exif_updated), color = Color.White, fontSize = 18.sp)
        }
    }
}
