import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.icon
import sbs3dfullscreen.resources.playlist_add_photos_dialog_title
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) = application {
    // Windows launches the app with the file path as an argument when it's opened
    // via a file association (double-click, "Open with sbs3dFullscreen", etc.).
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }
    val windowState = rememberWindowState(
        placement = if (initialFile != null) WindowPlacement.Maximized else WindowPlacement.Floating
    )
    val viewModel = remember { AppViewModel(initialFile) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Hoisted above key(undecorated) below (which disposes/recreates the whole Window subtree,
    // including anything remembered inside GalleryScreen) so the scroll position survives
    // Gallery -> ImageView -> Gallery round-trips.
    val galleryListState = rememberLazyListState()
    val undecorated = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView

    // Remembers the window's placement/size/position from just before entering the
    // undecorated+Maximized "fullscreen" mode, so Escape can restore it exactly instead of
    // just flipping placement back to Floating (which left the window sized to fill the
    // screen, i.e. still looking fullscreen, just with borders).
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    var previousSize by remember { mutableStateOf(windowState.size) }
    var previousPosition by remember { mutableStateOf(windowState.position) }

    // Toggled open/closed by ImageScreen each time Shift or Ctrl is pressed (see
    // onPreviewKeyEvent below).
    var showImageInfoPanel by remember { mutableStateOf(false) }

    // Currently-held direction keys during manual-align mode, key -> press-start timestamp (ms).
    // Plain (non-Compose-state) maps: read/written from onPreviewKeyEvent and the tick loop below,
    // never need to trigger recomposition on their own.
    val manualAlignKeyPressStart = remember { mutableMapOf<Key, Long>() }
    val manualAlignKeyRemainder = remember { mutableMapOf<Key, Float>() }
    val manualAlignDirectionKeys = remember { setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) }

    // Drives the accelerating nudge (1px/s, 10px/s after 5s continuously held - see AlignButtonsRow's
    // Manual Align button) for whichever direction keys onPreviewKeyEvent below is currently
    // tracking in manualAlignKeyPressStart, independent of the OS's own key-repeat rate/timing.
    LaunchedEffect(viewModel.photoTools.manualAlignMode) {
        if (!viewModel.photoTools.manualAlignMode) {
            manualAlignKeyPressStart.clear()
            manualAlignKeyRemainder.clear()
            return@LaunchedEffect
        }
        var lastTickMs = System.currentTimeMillis()
        while (true) {
            delay(50.milliseconds)
            val now = System.currentTimeMillis()
            val dtSeconds = (now - lastTickMs) / 1000f
            lastTickMs = now
            var dx = 0
            var dy = 0
            for ((key, pressedAtMs) in manualAlignKeyPressStart) {
                val heldMs = now - pressedAtMs
                val ratePxPerSec = if (heldMs < 5000) 1f else 10f
                val accumulated = (manualAlignKeyRemainder[key] ?: 0f) + ratePxPerSec * dtSeconds
                val wholePixels = accumulated.toInt()
                manualAlignKeyRemainder[key] = accumulated - wholePixels
                if (wholePixels == 0) continue
                when (key) {
                    Key.DirectionRight -> dx += wholePixels
                    Key.DirectionLeft -> dx -= wholePixels
                    Key.DirectionDown -> dy += wholePixels
                    Key.DirectionUp -> dy -= wholePixels
                    else -> {}
                }
            }
            if (dx != 0 || dy != 0) viewModel.nudgeManualAlign(dx, dy)
        }
    }

    // True from the moment enterFullscreen() is called until whatever's being entered is ready to
    // show (the first photo's decode, a video's first frame, or immediately for a playlist's title
    // slide) - drives FullscreenLoadingOverlay. Hoisted above key(undecorated) so it survives the
    // Window recreation that happens at the same time.
    var isEnteringFullscreen by remember { mutableStateOf(false) }
    var enteredFullscreenAtMs by remember { mutableStateOf(0L) }

    // Photo decode is often fast enough (well under a second for typical camera JPEGs) that
    // clearing isEnteringFullscreen the instant it's done makes the overlay flash for only a
    // frame or two - imperceptible rather than reassuring. Keeping it up for at least this long
    // (padding out the remainder with a delay if the real work finished sooner) makes it read as
    // a deliberate "opening..." message instead of a glitch.
    val minFullscreenLoadingMs = 500L
    val finishEnteringFullscreen = {
        val remaining = minFullscreenLoadingMs - (System.currentTimeMillis() - enteredFullscreenAtMs)
        if (remaining > 0) {
            coroutineScope.launch {
                delay(remaining.milliseconds)
                isEnteringFullscreen = false
            }
        } else {
            isEnteringFullscreen = false
        }
        Unit
    }

    // Shared by the Escape key handler below and ImageScreen's settings-menu "exit fullscreen"
    // item, so both paths restore the window the same way.
    val exitFullscreen = {
        windowState.placement = previousPlacement
        windowState.size = previousSize
        windowState.position = previousPosition
        showImageInfoPanel = false
        isEnteringFullscreen = false
        viewModel.closeImageView()
    }

    // undecorated can only be set before the window's peer is created, so the
    // whole Window is disposed and recreated (via key()) whenever it changes.
    key(undecorated) {
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Fullscreen3D",
            undecorated = undecorated,
            icon = painterResource(Res.drawable.icon),
        ) {
            LaunchedEffect(viewModel.screen) {
                focusRequester.requestFocus()
            }

            // Clears isEnteringFullscreen for the paths that don't decode a photo (a playlist's
            // title/end slide, or a video - VideoScreen shows its own black screen until the first
            // frame arrives). The photo path instead clears it via ImageScreen's onImageLoaded.
            LaunchedEffect(viewModel.screen, viewModel.playlistSlideKind) {
                if (isEnteringFullscreen &&
                    (viewModel.screen == Screen.VideoView ||
                        (viewModel.screen == Screen.ImageView && viewModel.playlistSlideKind != null &&
                            viewModel.playlistSlideKind != PlaylistSlideKind.PHOTO))
                ) {
                    finishEnteringFullscreen()
                }
            }

            CompositionLocalProvider(LocalAppLocale provides viewModel.language) {
                // stringResource() re-reads Locale.getDefault() when it's newly composed,
                // so the subtree must be recreated (via key()) whenever the language changes.
                key(viewModel.language) {
                    AppTheme {
                        val inViewer = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (viewModel.photoTools.manualAlignMode) {
                                        // Arrow keys nudge the pending offset (see the tick-loop
                                        // LaunchedEffect above) instead of navigating; Escape
                                        // cancels the alignment instead of exiting fullscreen (resets
                                        // the offset, stays in fullscreen); Shift/Ctrl still toggles
                                        // the info panel as usual (harmless - it doesn't touch the
                                        // pending offset or navigate away); every other key-down is
                                        // swallowed so nothing else - navigation, auto-align's "A"
                                        // shortcut - can mutate state out from under the pending
                                        // offset while it's active.
                                        if (event.key in manualAlignDirectionKeys) {
                                            when (event.type) {
                                                KeyEventType.KeyDown -> manualAlignKeyPressStart.getOrPut(event.key) { System.currentTimeMillis() }
                                                KeyEventType.KeyUp -> {
                                                    manualAlignKeyPressStart.remove(event.key)
                                                    manualAlignKeyRemainder.remove(event.key)
                                                }
                                                else -> {}
                                            }
                                            true
                                        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                            viewModel.cancelManualAlign()
                                            true
                                        } else if (event.type == KeyEventType.KeyDown &&
                                            (event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
                                                event.key == Key.CtrlLeft || event.key == Key.CtrlRight)
                                        ) {
                                            showImageInfoPanel = !showImageInfoPanel
                                            true
                                        } else {
                                            event.type == KeyEventType.KeyDown
                                        }
                                    } else if (viewModel.photoTools.cropMode) {
                                        // Same swallow-everything-except-Escape/Shift/Ctrl treatment
                                        // as manualAlignMode above: Escape cancels the crop tool
                                        // (discards any drawn rectangle) instead of exiting
                                        // fullscreen, and nothing else - navigation, auto-align's
                                        // "A" shortcut - should be able to mutate state while a
                                        // crop rectangle is pending Save/Cancel.
                                        when (event.type) {
                                            KeyEventType.KeyDown if event.key == Key.Escape -> {
                                                viewModel.cancelCrop()
                                                true
                                            }

                                            KeyEventType.KeyDown if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
                                                    event.key == Key.CtrlLeft || event.key == Key.CtrlRight)
                                                -> {
                                                showImageInfoPanel = !showImageInfoPanel
                                                true
                                            }

                                            else -> {
                                                event.type == KeyEventType.KeyDown
                                            }
                                        }
                                    } else if (viewModel.photoTools.spotIssuesMode) {
                                        // Same swallow-everything-except-Escape/Shift/Ctrl treatment
                                        // as cropMode above: Escape cancels the "Spot stereo issues"
                                        // tool (discards any drawn rectangles) instead of exiting
                                        // fullscreen.
                                        when (event.type) {
                                            KeyEventType.KeyDown if event.key == Key.Escape -> {
                                                viewModel.cancelSpotIssues()
                                                true
                                            }

                                            KeyEventType.KeyDown if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
                                                    event.key == Key.CtrlLeft || event.key == Key.CtrlRight)
                                                -> {
                                                showImageInfoPanel = !showImageInfoPanel
                                                true
                                            }

                                            else -> {
                                                event.type == KeyEventType.KeyDown
                                            }
                                        }
                                    } else {
                                        // Toggles on the key-down of Shift/Ctrl itself (not on every
                                        // event where one happens to be held as a modifier), so a
                                        // press opens the panel and the next press closes it again -
                                        // holding the key no longer matters.
                                        if (inViewer && event.type == KeyEventType.KeyDown &&
                                            (event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
                                                event.key == Key.CtrlLeft || event.key == Key.CtrlRight)
                                        ) {
                                            showImageInfoPanel = !showImageInfoPanel
                                        }
                                        if (!inViewer || event.type != KeyEventType.KeyDown) {
                                            false
                                        } else when (event.key) {
                                            Key.Escape -> {
                                                exitFullscreen()
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
                                                // Runs on a background thread via performAutoAlign
                                                // (same path as AlignButtonsRow's Correct zoom button),
                                                // which also no-ops if a task is already running.
                                                // Only meaningful for still images, not video.
                                                if (viewModel.screen == Screen.ImageView) {
                                                    viewModel.currentImage?.let { file ->
                                                        coroutineScope.launch {
                                                            viewModel.performAutoAlign(file, AlignKind.AFFINE)
                                                        }
                                                    }
                                                }
                                                true
                                            }
                                            else -> false
                                        }
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
                                isEnteringFullscreen = true
                                enteredFullscreenAtMs = System.currentTimeMillis()
                            }

                            when (viewModel.screen) {
                                Screen.Welcome -> WelcomeScreen(
                                    window = window,
                                    language = viewModel.language,
                                    onLanguageChosen = viewModel::onLanguageChosen,
                                    useNewOpenCv5 = viewModel.useNewOpenCv5,
                                    onUseNewOpenCv5Chosen = viewModel::onUseNewOpenCv5Chosen,
                                    onFilesChosen = { files ->
                                        viewModel.onFilesChosen(files)
                                        enterFullscreen()
                                    },
                                    onImportPlaylist = { folder ->
                                        viewModel.importPlaylistFolder(folder)
                                    },
                                    onOpenPlaylistList = { viewModel.openPlaylistList() },
                                    onOpenGallery = { folder -> viewModel.openGallery(folder) },
                                    onOpenAbout = { viewModel.openAbout() },
                                )

                                Screen.About -> AboutScreen(onBack = { viewModel.closeAbout() })

                                Screen.Gallery -> GalleryScreen(
                                    groups = viewModel.galleryGroups,
                                    expandedGroups = viewModel.expandedGalleryGroups,
                                    listState = galleryListState,
                                    scrollTarget = viewModel.galleryScrollTarget,
                                    onScrollTargetConsumed = { viewModel.consumeGalleryScrollTarget() },
                                    onToggleGroup = { path -> viewModel.toggleGalleryGroup(path) },
                                    onOpenImage = { group, index ->
                                        viewModel.openGalleryImage(group, index)
                                        enterFullscreen()
                                    },
                                    onBack = { viewModel.closeGallery() },
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
                                            val folder = chooseDirectory(window = window, title = addPhotosDialogTitle)
                                            if (folder != null) {
                                                viewModel.openPlaylistPhotoPicker(folder)
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
                                        onOpenPlaylistItem = { index -> viewModel.openPlaylistItem(index) },
                                    )
                                }

                                Screen.PlaylistPhotoPicker -> PlaylistPhotoPickerScreen(
                                    files = viewModel.photoPickerFiles,
                                    selectedFiles = viewModel.photoPickerSelectedFiles,
                                    onToggleSelection = { file -> viewModel.togglePlaylistPhotoPickerSelection(file) },
                                    onConfirm = { viewModel.confirmPlaylistPhotoPickerSelection() },
                                    onBack = { viewModel.closePlaylistPhotoPicker() },
                                )

                                Screen.PlaylistItem -> {
                                    val playlist = viewModel.editingPlaylist
                                    val index = viewModel.editingPlaylistItemIndex
                                    val photo = if (playlist != null && index != null) playlist.photos.getOrNull(index) else null
                                    photo?.let {
                                        PlaylistItemScreen(
                                            photo = it,
                                            isPlaylistManual = playlist != null && !playlist.isAutomated,
                                            onBack = { viewModel.closePlaylistItem() },
                                            onModifyComment = viewModel::modifyItemComment,
                                            onModifyCommentZPercent = viewModel::modifyItemCommentZPercent,
                                            onModifyDuration = viewModel::modifyItemDuration,
                                            onModifyHalfWidth = viewModel::modifyItemHalfWidth,
                                            onDelete = viewModel::deletePlaylistItem,
                                        )
                                    }
                                }

                                Screen.ImageView -> when (viewModel.playlistSlideKind) {
                                    PlaylistSlideKind.TITLE -> viewModel.playingPlaylist?.let { PlaylistTitleScreen(it) }
                                    PlaylistSlideKind.END -> PlaylistEndScreen()
                                    PlaylistSlideKind.PHOTO, null -> {
                                        if (viewModel.isAutomatedPlaylist) {
                                            LaunchedEffect(viewModel.currentImageIndex, viewModel.imageFiles) {
                                                delay(viewModel.slideshowIntervalMs.milliseconds)
                                                viewModel.advanceSlideshow()
                                            }
                                        }
                                        viewModel.currentImage?.let { file ->
                                            ImageScreen(
                                                file,
                                                overrideBitmap = viewModel.photoTools.alignedPreview,
                                                showInfoPanel = showImageInfoPanel,
                                                hasAlignedPreview = viewModel.photoTools.alignedPreview != null,
                                                isAligning = viewModel.isAligning,
                                                alignToast = viewModel.alignToast,
                                                saveToast = viewModel.saveToast,
                                                shareToast = viewModel.shareToast,
                                                keepBestOfEachOnly = viewModel.keepBestOfEachOnly,
                                                favoritesOnly = viewModel.favoritesOnly,
                                                excludeStereoIssues = viewModel.excludeStereoIssues,
                                                halveLeftRightImages = viewModel.halveLeftRightImages,
                                                manualAlignMode = viewModel.photoTools.manualAlignMode,
                                                manualAlignOffsetX = viewModel.photoTools.manualAlignOffsetX,
                                                manualAlignOffsetY = viewModel.photoTools.manualAlignOffsetY,
                                                cropMode = viewModel.photoTools.cropMode,
                                                cropRect = viewModel.photoTools.cropRect,
                                                spotIssuesMode = viewModel.photoTools.spotIssuesMode,
                                                spotIssueRects = viewModel.photoTools.spotIssueRects,
                                                pendingNavigation = viewModel.pendingNavigation,
                                                onConfirmSaveAlignedAndNavigate = {
                                                    coroutineScope.launch { viewModel.confirmSaveAlignedAndNavigate() }
                                                },
                                                onDiscardAlignedPreviewAndNavigate = viewModel::discardAlignedPreviewAndNavigate,
                                                onCancelPendingNavigation = viewModel::cancelPendingNavigation,
                                                onKeepBestOfEachOnlyChosen = viewModel::onKeepBestOfEachOnlyChosen,
                                                onFavoritesOnlyChosen = viewModel::onFavoritesOnlyChosen,
                                                onExcludeStereoIssuesChosen = viewModel::onExcludeStereoIssuesChosen,
                                                onHalveLeftRightImagesChosen = viewModel::onHalveLeftRightImagesChosen,
                                                onExitFullscreen = exitFullscreen,
                                                onNextImage = viewModel::showNextImage,
                                                onPreviousImage = viewModel::showPreviousImage,
                                                onToggleInfoPanel = { showImageInfoPanel = !showImageInfoPanel },
                                                onShareChosen = { type ->
                                                    coroutineScope.launch { viewModel.performShare(type) }
                                                },
                                                onAutoAlign = {
                                                    coroutineScope.launch {
                                                        viewModel.performAutoAlign(file, AlignKind.HOMOGRAPHY)
                                                    }
                                                },
                                                onCorrectZoom = {
                                                    coroutineScope.launch {
                                                        viewModel.performAutoAlign(file, AlignKind.AFFINE)
                                                    }
                                                },
                                                onSaveAligned = {
                                                    coroutineScope.launch { viewModel.performSaveAligned() }
                                                },
                                                onStartManualAlign = viewModel::startManualAlign,
                                                onCancelManualAlign = viewModel::cancelManualAlign,
                                                onSaveManualAlign = {
                                                    coroutineScope.launch { viewModel.performSaveManualAlign() }
                                                },
                                                onStartCrop = viewModel::startCrop,
                                                onCropRectFinalized = viewModel::finalizeCropRect,
                                                onCancelCrop = viewModel::cancelCrop,
                                                onSaveCrop = {
                                                    coroutineScope.launch { viewModel.performSaveCrop() }
                                                },
                                                onStartSpotIssues = viewModel::startSpotIssues,
                                                onSpotIssueRectAdded = viewModel::addSpotIssueRect,
                                                onCancelSpotIssues = viewModel::cancelSpotIssues,
                                                onSaveSpotIssues = {
                                                    coroutineScope.launch { viewModel.performSaveSpotIssues() }
                                                },
                                                onDeleteCurrentImage = {
                                                    coroutineScope.launch { viewModel.performDeleteCurrentImage() }
                                                },
                                                onDeleteKeepingLeft = {
                                                    coroutineScope.launch { viewModel.performDeleteCurrentImage(AppViewModel.KeepHalfSide.LEFT) }
                                                },
                                                onDeleteKeepingRight = {
                                                    coroutineScope.launch { viewModel.performDeleteCurrentImage(AppViewModel.KeepHalfSide.RIGHT) }
                                                },
                                                onImageLoaded = finishEnteringFullscreen,
                                            )
                                        }
                                    }
                                }

                                Screen.VideoView -> viewModel.currentImage?.let { file ->
                                    VideoScreen(file)
                                }
                            }

                            if (isEnteringFullscreen && inViewer) {
                                FullscreenLoadingOverlay()
                            }
                        }
                    }
                }
            }
        }
    }
}
