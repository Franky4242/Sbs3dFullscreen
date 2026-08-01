import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import fr.camera3d.camera.common.ui_components.SwitchParameterComposable
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.ui.ComposableEditDefaultDuration
import fr.camera3d.camera.feature_playlists.ui.ComposableEditDefaultDurationDialog
import fr.camera3d.camera.feature_playlists.ui.ComposableEditName
import fr.camera3d.camera.feature_playlists.ui.ComposableEditSubtitle
import fr.camera3d.camera.feature_playlists.ui.ComposableEditSubtitleZPercent
import fr.camera3d.camera.feature_playlists.ui.ComposableEditTitleZPercent
import fr.camera3d.camera.feature_playlists.ui.ComposablePlaylistItem
import fr.camera3d.camera.feature_playlists.ui.ComposablePlaylistMenu
import fr.camera3d.camera.feature_playlists.ui.PlaylistScreenStrings
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// Number of header items (name/title-z/subtitle/subtitle-z/automated-switch/duration) placed
// before the photo items in the LazyColumn - kept in sync with PlaylistFragment.kt's Android
// counterpart so the drag-to-reorder index math (from/to - PLAYLIST_HEADER_ITEM_COUNT) matches.
private const val PLAYLIST_HEADER_ITEM_COUNT = 6

/**
 * Playlist editor screen: same fields/behavior as CameraSync3D's PlaylistFragment, built from the
 * same shared composables (ComposableEditName, ComposableEditSubtitle, ComposableEditTitleZPercent,
 * ComposableEditSubtitleZPercent, ComposableEditDefaultDuration(Dialog), ComposablePlaylistItem,
 * ComposablePlaylistMenu - synced from Android via tools/sync-from-android.ps1) so the two apps
 * can't silently drift apart on what a "playlist" contains.
 */
@Composable
fun PlaylistScreen(
    playlist: Playlist,
    onAddPhotos: () -> Unit,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onEditName: (String) -> Boolean,
    canRenamePlaylist: (String) -> Boolean,
    onModifyDefaultDuration: (Long) -> Boolean,
    onModifyIsAutomated: (Boolean) -> Boolean,
    onModifySubtitle: (String) -> Boolean,
    onModifyTitleZPercent: (Float) -> Boolean,
    onModifySubtitleZPercent: (Float) -> Boolean,
    onReorderPhotos: (List<PlaylistItem>) -> Unit,
    onDelete: () -> Boolean,
    onOpenPlaylistItem: (Int) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val strings = PlaylistScreenStrings(
        ok = stringResource(Res.string.ok_button),
        saveButton = stringResource(Res.string.playlist_save_button),
        cancel = stringResource(Res.string.cancel_button),
        name = stringResource(Res.string.playlist_name_label),
        editPlaylistName = stringResource(Res.string.playlist_edit_name_dialog_title),
        playlistNameAlreadyExists = stringResource(Res.string.playlist_name_already_exists),
        noModificationMade = stringResource(Res.string.playlist_no_modification_made),
        nameCannotBeEmpty = stringResource(Res.string.playlist_name_cannot_be_empty),
        playlistRenamed = stringResource(Res.string.playlist_renamed),
        cannotSavePlaylist = stringResource(Res.string.playlist_cannot_save),
        errorWhileSavingPlaylist = stringResource(Res.string.playlist_error_while_saving),
        subtitle = stringResource(Res.string.playlist_subtitle_label),
        pressHereToSetASubtitle = stringResource(Res.string.playlist_subtitle_placeholder),
        editSubtitle = stringResource(Res.string.playlist_edit_subtitle_dialog_title),
        titleZAltitudePercent = stringResource(Res.string.playlist_title_z_label),
        titleZ = stringResource(Res.string.playlist_title_z_documentation),
        editTitleZAltitudePercent = stringResource(Res.string.playlist_edit_title_z_dialog_title),
        errorZMustBeAFloat = stringResource(Res.string.playlist_error_z_must_be_float),
        subtitleZAltitudePercent = stringResource(Res.string.playlist_subtitle_z_label),
        subtitleZ = stringResource(Res.string.playlist_subtitle_z_documentation),
        editSubtitleZAltitudePercent = stringResource(Res.string.playlist_edit_subtitle_z_dialog_title),
        automatedSlideshow = stringResource(Res.string.playlist_automated_slideshow),
        defaultDuration = stringResource(Res.string.playlist_default_duration_label),
        modifySlideshowDefaultDurationBetweenImages = stringResource(Res.string.playlist_modify_default_duration_dialog_title),
        defaultDurationShouldBe0And60000Ms = stringResource(Res.string.playlist_default_duration_range_error),
        deletePlaylistOption = stringResource(Res.string.playlist_delete_option),
        deletePlaylistConfirmTitle = stringResource(Res.string.playlist_delete_confirm_title),
        deletePlaylistConfirmOk = stringResource(Res.string.playlist_delete_confirm_ok),
        deletePlaylistConfirmMessage = stringResource(Res.string.playlist_delete_confirm_message),
    )

    // Hoisted like on Android: the duration row is the last header item before the photo list, so
    // a dialog owned by the row itself could be silently dismissed if the row isn't recomposed.
    var showDurationDialog by rememberSaveable { mutableStateOf(false) }
    var durationTextFieldValue by rememberSaveable { mutableStateOf("") }

    // Local working list for live drag visual feedback; kept in sync with playlist.photos when no
    // drag is in progress (same pattern as Android's ComposablePlaylistFragment).
    var workingPhotos by remember { mutableStateOf(playlist.photos) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.photos) {
        if (!isDragging) workingPhotos = playlist.photos
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            val fromIndex = from.index - PLAYLIST_HEADER_ITEM_COUNT
            val toIndex = to.index - PLAYLIST_HEADER_ITEM_COUNT
            workingPhotos = workingPhotos.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        },
    )

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = playlist.name,
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
            ComposablePlaylistMenu(onDelete, { onBack(); true }, playlist.name, playlist.photos.size, strings = strings)
        },
        bottomBar = {},
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = onPlay, shape = CircleShape) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.playlist_play_button))
                }
                FloatingActionButton(onClick = onAddPhotos, shape = CircleShape) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.playlist_add_photos_dialog_title))
                }
            }
        },
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            BoxWithConstraints {
                val imageWidth = maxWidth / 2
                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    item { ComposableEditName(playlist.name, snackbarHostState, scope, onEditName, canRenamePlaylist, strings = strings) }
                    item {
                        Box(Modifier.padding(start = 16.dp)) {
                            ComposableEditTitleZPercent(playlist.titleZPercent, snackbarHostState, scope, onModifyTitleZPercent, strings = strings)
                        }
                    }
                    item { ComposableEditSubtitle(playlist.subtitle, onModifySubtitle, strings = strings) }
                    item {
                        Box(Modifier.padding(start = 16.dp)) {
                            ComposableEditSubtitleZPercent(playlist.subtitleZPercent, snackbarHostState, scope, onModifySubtitleZPercent, strings = strings)
                        }
                    }
                    item {
                        SwitchParameterComposable(
                            parameterName = strings.automatedSlideshow,
                            checked = playlist.isAutomated,
                            documentation = "",
                            onCheckedChange = { onModifyIsAutomated(it) },
                        )
                    }
                    item {
                        Box(Modifier.padding(start = 16.dp)) {
                            ComposableEditDefaultDuration(
                                duration = playlist.defaultDurationS,
                                enabled = playlist.isAutomated,
                                strings = strings,
                                onClick = {
                                    durationTextFieldValue = playlist.defaultDurationS.toString()
                                    showDurationDialog = true
                                },
                            )
                        }
                    }
                    itemsIndexed(workingPhotos, key = { _, photo -> photo.filename }) { index, photo ->
                        ReorderableItem(reorderState, key = photo.filename) { dragging ->
                            val elevation = if (dragging) 8.dp else 4.dp
                            ComposablePlaylistItem(
                                index = index,
                                photo = photo,
                                shadowElevation = elevation,
                                onOpenPlaylistItem = onOpenPlaylistItem,
                                imageWidth = imageWidth,
                                imageHeight = 200.dp,
                                reorderModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = { isDragging = true },
                                    onDragStopped = {
                                        isDragging = false
                                        onReorderPhotos(workingPhotos)
                                    },
                                ),
                            )
                        }
                    }
                }
                if (showDurationDialog) {
                    ComposableEditDefaultDurationDialog(
                        currentDurationS = playlist.defaultDurationS,
                        durationTextFieldValue = durationTextFieldValue,
                        onDurationTextFieldValueChange = { durationTextFieldValue = it },
                        onDismissRequestCb = { showDurationDialog = false },
                        onSave = onModifyDefaultDuration,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        strings = strings,
                    )
                }
            }
        },
    )
}
