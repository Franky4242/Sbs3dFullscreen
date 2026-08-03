import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File

@Composable
fun ImageScreen(
    file: File,
    overrideBitmap: ImageBitmap? = null,
    showInfoPanel: Boolean = false,
    hasAlignedPreview: Boolean = false,
    alignToast: AlignToast? = null,
    saveToast: SaveToast? = null,
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
) {
    val fileBitmap = remember(file) {
        file.readBytes().decodeToImageBitmap()
    }
    val imageBitmap = overrideBitmap ?: fileBitmap

    // Hosted here rather than inside Exif3dInfoPanel so the toast survives Shift/Ctrl being
    // released, which removes Exif3dInfoPanel (and any state it holds) from composition.
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
