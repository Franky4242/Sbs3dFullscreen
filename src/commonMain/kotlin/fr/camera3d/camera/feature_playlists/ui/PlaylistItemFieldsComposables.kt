package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable versions of the playlist-item composables previously defined inline in
// PlaylistItemFragment.kt (ComposablePhoto, ComposableComment, ComposableCommentAltitude,
// ComposableDuration, ComposableHalfWidth, ComposablePhotoMenu): same behavior, but every
// label/dialog-title/error string comes from the caller-supplied PlaylistItemScreenStrings
// instead of stringResource(R.string.x), and the photo is loaded from a plain String uri via
// coil3.compose.SubcomposeAsyncImage instead of an android.net.Uri via coil3.compose.AsyncImage
// (Coil3 supports desktop/JVM targets), so this file has no Android dependency.
// Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import fr.camera3d.camera.common.ui_components.EditParameterComposable
import fr.camera3d.camera.common.ui_components.SwitchParameterComposable
import fr.camera3d.camera.common.ui_components.snackbar
import kotlinx.coroutines.CoroutineScope

/** Displays the playlist item's photo, full width, capped at 400dp tall. [onClick] is a no-op by default (used on Android to open the anaglyph editor; desktop has no equivalent). */
@Composable
fun ComposableItemPhoto(imageUriString: String, onClick: () -> Unit = {}) {
    val imageModifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
    SubcomposeAsyncImage(
        model = imageUriString,
        contentDescription = "Photo",
        loading = { Box(imageModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
        error = { Box(imageModifier, contentAlignment = Alignment.Center) { Icon(Icons.Filled.BrokenImage, contentDescription = null) } },
        contentScale = ContentScale.Fit,
        modifier = imageModifier.clickable { onClick() },
    )
}

/** Displays the photo's comment (shown as an overlay in the slideshow) and enables editing it. */
@Composable
fun ComposableItemComment(comment: String, onModifyComment: (String) -> Boolean, strings: PlaylistItemScreenStrings) {
    EditParameterComposable(
        parameterValue = comment,
        defaultValue = strings.pressHereToSetAComment,
        parameterName = strings.comment,
        dialogTitle = strings.editComment,
        okLabel = strings.ok,
        saveLabel = strings.saveButton,
        cancelLabel = strings.cancel,
        onDialogSave = { newComment -> onModifyComment(newComment) },
    )
}

/** Displays the comment's stereo depth shift ("altitude") and enables editing it. */
@Composable
fun ComposableItemCommentZPercent(
    commentZPercent: Float,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onModifyCommentZPercent: (Float) -> Boolean,
    strings: PlaylistItemScreenStrings,
) {
    EditParameterComposable(
        parameterValue = commentZPercent.toString(),
        suffix = "%",
        parameterName = strings.zAltitudePercent,
        documentation = strings.commentZDocumentation,
        dialogTitle = strings.editZAltitudePercent,
        okLabel = strings.ok,
        saveLabel = strings.saveButton,
        cancelLabel = strings.cancel,
        dialogKeyboardOption = KeyboardOptions(keyboardType = KeyboardType.Number),
        documentationAsInfoIcon = true,
        onDialogSave = { newValueStr ->
            val floatValue = newValueStr.toFloatOrNull()
            if (floatValue != null) {
                onModifyCommentZPercent(floatValue)
            } else {
                snackbar(scope, snackbarHostState, strings.errorZMustBeAFloat)
                true
            }
        },
    )
}

/** Displays the photo's duration override (-1 falls back to the playlist default) and enables editing it. [isManualMode] shows a warning below the field: the duration is ignored while the playlist advances manually. */
@Composable
fun ComposableItemDuration(
    durationS: Int,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onModifyDuration: (Int) -> Boolean,
    strings: PlaylistItemScreenStrings,
    isManualMode: Boolean = false,
) {
    val durationStr = if (durationS == -1) "" else durationS.toString()
    EditParameterComposable(
        parameterValue = durationStr,
        suffix = "s",
        defaultValue = strings.usePlaylistDefaultValue,
        parameterName = strings.duration,
        documentation = if (isManualMode) strings.durationManualModeWarning else "",
        dialogTitle = strings.editDuration,
        okLabel = strings.ok,
        saveLabel = strings.saveButton,
        cancelLabel = strings.cancel,
        dialogKeyboardOption = KeyboardOptions(keyboardType = KeyboardType.Number),
        onDialogSave = { newDurationStr ->
            if (newDurationStr == "") {
                onModifyDuration(-1)
            } else {
                val newDuration = newDurationStr.toIntOrNull()
                if (newDuration == null || newDuration < 1) {
                    snackbar(scope, snackbarHostState, strings.durationMustBePositive)
                    true
                } else {
                    onModifyDuration(newDuration)
                }
            }
        },
    )
}

/** Displays the "half width" switch (whether the picture is a side-by-side full or half width 3D image). */
@Composable
fun ComposableItemHalfWidth(isHalfWidth: Boolean, onModifyHalfWidth: (Boolean) -> Unit, strings: PlaylistItemScreenStrings) {
    SwitchParameterComposable(
        parameterName = strings.halfWidth,
        checked = isHalfWidth,
        documentation = strings.halfWidthDocumentation,
        onCheckedChange = onModifyHalfWidth,
    )
}

/** The playlist-item screen's 3-dot menu: delete-photo option (no confirmation dialog, matching Android). */
@Composable
fun ComposableItemMenu(onDelete: () -> Boolean, onGoBack: () -> Boolean, strings: PlaylistItemScreenStrings) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
        onClick = { expanded = true },
    ) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(strings.deletePhotoOption) },
            onClick = {
                expanded = false
                val isDeleted = onDelete()
                if (isDeleted) {
                    onGoBack()
                }
            },
        )
    }
}
