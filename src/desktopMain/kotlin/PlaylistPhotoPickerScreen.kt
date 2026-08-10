import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.gallery_empty
import sbs3dfullscreen.resources.playlist_back_button
import sbs3dfullscreen.resources.playlist_photo_picker_confirm_button
import sbs3dfullscreen.resources.playlist_photo_picker_screen_title
import sbs3dfullscreen.resources.playlist_photo_picker_selected_count
import java.io.File

/**
 * Grid of the images found in the directory picked from PlaylistScreen's "Add photos" FAB
 * (see AppViewModel.openPlaylistPhotoPicker). Each thumbnail carries a checkbox so several photos
 * can be ticked before a single confirm adds them all to the playlist being edited, mirroring
 * CameraSync3D's GalleryPickerFragment/GalleryPickerScreen (same top-bar "N selected" + confirm
 * button, same checkbox-over-thumbnail layout) - the desktop app has no MediaStore-backed photo
 * repository to browse, so the grid here is scoped to a single chosen directory instead. Reuses
 * GalleryScreen.kt's GalleryThumbnail (decode + raw/edited label + favorite/legend row + stereo
 * warning badge) so the two grids stay visually identical instead of drifting apart.
 */
@Composable
fun PlaylistPhotoPickerScreen(
    files: List<File>,
    selectedFiles: Set<File>,
    onToggleSelection: (File) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.playlist_photo_picker_screen_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {
            if (selectedFiles.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.playlist_photo_picker_selected_count, selectedFiles.size),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Button(
                onClick = onConfirm,
                enabled = selectedFiles.isNotEmpty(),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(stringResource(Res.string.playlist_photo_picker_confirm_button))
            }
        },
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.gallery_empty))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = (maxWidth / (thumbnailWidth + thumbnailSpacing)).toInt().coerceAtLeast(1)
                    val rows = files.chunked(columns)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(thumbnailSpacing),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        itemsIndexed(rows, key = { rowIndex, _ -> "row:$rowIndex" }) { _, rowFiles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(thumbnailSpacing)) {
                                rowFiles.forEach { file ->
                                    val isSelected = file in selectedFiles
                                    GalleryThumbnail(file = file, onClick = { onToggleSelection(file) }) {
                                        // A background square behind the checkbox keeps it legible
                                        // over bright/white photo areas.
                                        Box(modifier = Modifier.align(Alignment.TopStart)) {
                                            Spacer(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .align(Alignment.Center)
                                                    .background(MaterialTheme.colorScheme.background)
                                            )
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { onToggleSelection(file) },
                                            )
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
