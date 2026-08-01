import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.icon
import sbs3dfullscreen.resources.playlist_add_photos_dialog_title
import java.awt.FileDialog
import java.io.File
import java.io.FilenameFilter
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) = application {
    // Windows launches the app with the file path as an argument when it's opened
    // via a file association (double-click, "Open with sbs3Dfullscreen", etc.).
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }
    val windowState = rememberWindowState(
        placement = if (initialFile != null) WindowPlacement.Maximized else WindowPlacement.Floating
    )
    val viewModel = remember { AppViewModel(initialFile) }
    val focusRequester = remember { FocusRequester() }
    val undecorated = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView

    // Remembers the window's placement/size/position from just before entering the
    // undecorated+Maximized "fullscreen" mode, so Escape can restore it exactly instead of
    // just flipping placement back to Floating (which left the window sized to fill the
    // screen, i.e. still looking fullscreen, just with borders).
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    var previousSize by remember { mutableStateOf(windowState.size) }
    var previousPosition by remember { mutableStateOf(windowState.position) }

    // undecorated can only be set before the window's peer is created, so the
    // whole Window is disposed and recreated (via key()) whenever it changes.
    key(undecorated) {
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "sbs3Dfullscreen",
            undecorated = undecorated,
            icon = painterResource(Res.drawable.icon),
        ) {
            LaunchedEffect(viewModel.screen) {
                focusRequester.requestFocus()
            }

            CompositionLocalProvider(LocalAppLocale provides viewModel.language) {
                // stringResource() re-reads Locale.getDefault() when it's newly composed,
                // so the subtree must be recreated (via key()) whenever the language changes.
                key(viewModel.language) {
                    MaterialTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    val inViewer = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView
                                    if (!inViewer || event.type != KeyEventType.KeyDown) {
                                        false
                                    } else when (event.key) {
                                        Key.Escape -> {
                                            windowState.placement = previousPlacement
                                            windowState.size = previousSize
                                            windowState.position = previousPosition
                                            viewModel.closeImageView()
                                            true
                                        }
                                        Key.Spacebar, Key.DirectionRight -> {
                                            viewModel.showNextImage()
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            viewModel.showPreviousImage()
                                            true
                                        }
                                        Key.A -> {
                                            // Synchronous on purpose for this first pass: ORB
                                            // feature detection is fast enough (well under a
                                            // second) on typical photos that a brief UI pause is
                                            // an acceptable trade for not adding threading here.
                                            // Only meaningful for still images, not video.
                                            if (viewModel.screen == Screen.ImageView) {
                                                viewModel.currentImage?.let { file ->
                                                    viewModel.applyAlignedPreview(AutoAlign.autoAlignSideBySide(file))
                                                }
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            // Undecorated + Maximized ("borderless fullscreen") rather than
                            // WindowPlacement.Fullscreen: on Windows, Fullscreen puts the window
                            // into real OS exclusive full-screen mode (GraphicsDevice.fullScreenWindow),
                            // which Windows auto-minimizes if the window loses focus - e.g. when the
                            // 3D monitor's own "activate 3D" popup steals focus. Maximized is a normal
                            // window, so it just deactivates instead of getting iconified.
                            fun enterFullscreen() {
                                previousPlacement = windowState.placement
                                previousSize = windowState.size
                                previousPosition = windowState.position
                                windowState.placement = WindowPlacement.Maximized
                            }

                            when (viewModel.screen) {
                                Screen.Welcome -> WelcomeScreen(
                                    window = window,
                                    language = viewModel.language,
                                    onLanguageChosen = viewModel::onLanguageChosen,
                                    onFilesChosen = { files ->
                                        viewModel.onFilesChosen(files)
                                        enterFullscreen()
                                    },
                                    onImportPlaylist = { folder ->
                                        viewModel.importPlaylistFolder(folder)
                                    },
                                    onOpenPlaylistList = { viewModel.openPlaylistList() },
                                )

                                Screen.PlaylistList -> PlaylistsScreen(
                                    playlists = viewModel.playlists,
                                    onBack = { viewModel.closePlaylistList() },
                                    onRefresh = { viewModel.refreshPlaylistList() },
                                    onOpenPlaylist = { playlist -> viewModel.openPlaylistForEdit(playlist) },
                                    onPlayPlaylist = { playlist ->
                                        viewModel.playPlaylist(playlist)
                                        enterFullscreen()
                                    },
                                    onCreatePlaylist = { name ->
                                        viewModel.startCreatePlaylist(name)
                                    },
                                    canCreatePlaylist = viewModel::canCreatePlaylist,
                                )

                                Screen.PlaylistEdit -> viewModel.editingPlaylist?.let { playlist ->
                                    val addPhotosDialogTitle = stringResource(Res.string.playlist_add_photos_dialog_title)
                                    PlaylistScreen(
                                        playlist = playlist,
                                        onAddPhotos = {
                                            val dialog = FileDialog(window as? java.awt.Frame, addPhotosDialogTitle, FileDialog.LOAD)
                                            dialog.filenameFilter = FilenameFilter { _, name ->
                                                val lower = name.lowercase()
                                                lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                                            }
                                            dialog.isMultipleMode = true
                                            dialog.isVisible = true
                                            val files = dialog.files
                                            if (files.isNotEmpty()) {
                                                viewModel.addPhotosToEditingPlaylist(files.toList())
                                            }
                                        },
                                        onPlay = {
                                            viewModel.playEditingPlaylist()
                                            enterFullscreen()
                                        },
                                        onBack = { viewModel.closePlaylistEdit() },
                                        onEditName = viewModel::modifyPlaylistName,
                                        canRenamePlaylist = viewModel::canRenamePlaylist,
                                        onModifyDefaultDuration = viewModel::modifyDefaultDuration,
                                        onModifyIsAutomated = viewModel::modifyIsAutomated,
                                        onModifySubtitle = viewModel::modifySubtitle,
                                        onModifyTitleZPercent = viewModel::modifyTitleZPercent,
                                        onModifySubtitleZPercent = viewModel::modifySubtitleZPercent,
                                        onReorderPhotos = viewModel::applyPhotosReorder,
                                        onDelete = viewModel::deletePlaylist,
                                    )
                                }

                                Screen.ImageView -> {
                                    if (viewModel.isAutomatedPlaylist) {
                                        LaunchedEffect(viewModel.currentImageIndex, viewModel.imageFiles) {
                                            delay(viewModel.slideshowIntervalMs.milliseconds)
                                            viewModel.advanceSlideshow()
                                        }
                                    }
                                    viewModel.currentImage?.let { file ->
                                        ImageScreen(file, overrideBitmap = viewModel.alignedPreview)
                                    }
                                }

                                Screen.VideoView -> viewModel.currentImage?.let { file ->
                                    VideoScreen(file)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
