import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences

enum class Screen { Welcome, About, Gallery, PlaylistList, PlaylistEdit, PlaylistPhotoPicker, PlaylistItem, ImageView, VideoView }

/**
 * Mirrors CameraSync3D's SlideshowViewModel.UiType: a playlist slideshow is a title slide,
 * then its photos, then an end slide - not just a bare loop over image files.
 */
enum class PlaylistSlideKind { TITLE, PHOTO, END }

private val videoExtensions = setOf("mp4", "mov", "mkv", "avi")

/**
 * One auto-align/correct-zoom attempt's outcome - see AppViewModel.alignToast. [zoomScale]/
 * [rotationDegrees] are only populated for a successful [AlignKind.AFFINE] ("Correct Zoom") run -
 * see AutoAlign.AutoAlignResult - so AlignResultToast can report what was actually detected.
 */
data class AlignToast(val success: Boolean, val token: Int, val zoomScale: Float? = null, val rotationDegrees: Float? = null)

/** One "Save" button attempt's outcome - see AppViewModel.saveToast. */
data class SaveToast(val success: Boolean, val token: Int)

/** One "Share" attempt's outcome - see AppViewModel.shareToast. Only fired on [Share.EmailResult.FAILED]
 *  (a cancelled compose window - [Share.EmailResult.CANCELLED] - is a deliberate user action, not worth a toast). */
data class ShareToast(val token: Int)

/**
 * A Next/Previous navigation blocked by an unsaved auto-align/correct-zoom preview (see
 * AppViewModel.pendingNavigation) - ImageScreen shows a Save/Discard/Cancel dialog for it instead
 * of navigating straight away, so the preview (visible only in memory - see
 * PhotoToolsState.alignedPreview) isn't silently lost.
 */
enum class PendingNavigationDirection { NEXT, PREVIOUS }

private val galleryImageExtensions = setOf("jpg", "jpeg", "mpo")

/**
 * One subdirectory (recursively found under the chosen gallery root) that contains at least one
 * image, shown as a collapsible section on GalleryScreen. [relativePath] is empty for images
 * directly inside the chosen root.
 */
data class GalleryGroup(val relativePath: String, val displayName: String, val files: List<File>)

/** Recursively scans [root] for JPEGs, grouped by the immediate subdirectory that contains them. */
private fun scanGalleryDirectory(root: File): List<GalleryGroup> =
    root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in galleryImageExtensions }
        .groupBy { it.parentFile }
        .map { (dir, files) ->
            val relativePath = dir.relativeTo(root).path.replace(File.separatorChar, '/')
            GalleryGroup(
                relativePath = relativePath,
                displayName = relativePath.ifEmpty { root.name },
                files = files.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.relativePath }

/** Persists AppViewModel.halveLeftRightImages across app restarts, same Preferences API as FileChoosers.kt's LastDirectoryPreference. */
private object HalveLeftRightImagesPreference {
    private const val Key = "halveLeftRightImages"
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)

    fun load(): Boolean = prefs.getBoolean(Key, true)

    fun save(value: Boolean) {
        prefs.putBoolean(Key, value)
    }
}

/** Persists AppViewModel.keepBestOfEachOnly across app restarts, same Preferences API as HalveLeftRightImagesPreference above. */
private object KeepBestOfEachOnlyPreference {
    private const val Key = "keepBestOfEachOnly"
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)

    fun load(): Boolean = prefs.getBoolean(Key, false)

    fun save(value: Boolean) {
        prefs.putBoolean(Key, value)
    }
}

/** Persists AppViewModel.shrinkControls across app restarts, same Preferences API as HalveLeftRightImagesPreference above. */
private object ShrinkControlsPreference {
    private const val Key = "shrinkControls"
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)

    fun load(): Boolean = prefs.getBoolean(Key, false)

    fun save(value: Boolean) {
        prefs.putBoolean(Key, value)
    }
}

/**
 * Holds the app's screen/navigation state and the logic to mutate it, decoupled from the
 * `Window`/`WindowState` concerns (undecorated, placement) that stay in Main.kt since those
 * are tied directly to the Window composable's lifecycle.
 */
class AppViewModel(initialFile: File?) {
    var screen by mutableStateOf(
        when {
            initialFile == null -> Screen.Welcome
            initialFile.extension.lowercase() in videoExtensions -> Screen.VideoView
            else -> Screen.ImageView
        }
    )
        private set
    var imageFiles by mutableStateOf(initialFile?.let { listOf(it) } ?: emptyList())
        private set
    var currentImageIndex by mutableStateOf(0)
        private set
    var language by mutableStateOf<String?>(null)
        private set
    // Mirrors CameraSync3D's useNewOpenCv5 toggle: SIFT+USAC_MAGSAC instead of ORB+RANSAC for
    // auto-align. Not persisted to disk, same as language before a selection is made.
    var useNewOpenCv5 by mutableStateOf(false)
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo that isn't the highest raw/edited version in its group
    // (see GalleryScreen.kt's bestVersionsOnly). Persisted (KeepBestOfEachOnlyPreference below)
    // since it's a durable viewing preference, not tied to the current session - unlike useNewOpenCv5.
    var keepBestOfEachOnly by mutableStateOf(KeepBestOfEachOnlyPreference.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo whose EXIF3D "favorite" flag isn't set (see
    // Exif3d.getFavoriteFromExif). Combines with keepBestOfEachOnly/excludeStereoIssues (see
    // visiblePhotos) - not persisted to disk, same as keepBestOfEachOnly.
    var favoritesOnly by mutableStateOf(false)
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo whose EXIF3D "warning" (stereo issue) flag is set (see
    // Exif3d.getWarningFromExif). Combines with keepBestOfEachOnly/favoritesOnly (see
    // visiblePhotos) - not persisted to disk, same as keepBestOfEachOnly.
    var excludeStereoIssues by mutableStateOf(false)
        private set
    // Toggled from ImageScreen's settings menu: when true, the combined L+R photo is squeezed
    // horizontally by 2 before display, matching the input a Half-SBS 3D monitor expects (each eye
    // half already at full native resolution in the source file, so the whole frame must be
    // squeezed to the monitor's native width for its own hardware to unsqueeze per eye) - see
    // ImageScreen.kt's StereoImage. Unlike useNewOpenCv5, this is persisted
    // (HalveLeftRightImagesPreference below) since it depends on the user's monitor, not the
    // current viewing session, and defaults to on to match the common Half-SBS setup.
    var halveLeftRightImages by mutableStateOf(HalveLeftRightImagesPreference.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, every UI control overlay (the settings
    // menu icon/panel, the raw/edited label, the info panel, and every confirmation dialog) is
    // squeezed horizontally by 2 and, for dialogs, duplicated per half - the same treatment
    // halveLeftRightImages gives the photo itself. A Half-SBS 3D monitor's hardware unsqueezes the
    // whole frame per eye, so without this, controls that aren't part of the photo would read
    // stretched 2x wide - see ShrinkControls.kt. Persisted (ShrinkControlsPreference below) for the
    // same reason as halveLeftRightImages: it depends on the user's monitor, not the current
    // viewing session. Defaults to off since it's a new opt-in control, unlike
    // halveLeftRightImages which defaults on to match the common Half-SBS setup.
    var shrinkControls by mutableStateOf(ShrinkControlsPreference.load())
        private set
    // Only set when imageFiles came from a playlist with isAutomated=true; drives the
    // auto-advance timer in Main.kt. Plain file selections never auto-advance.
    var isAutomatedPlaylist by mutableStateOf(false)
        private set
    var slideshowIntervalMs by mutableStateOf(10_000L)
        private set
    // True while an auto-align/correct-zoom/save task is running on a background thread - drives
    // the disabled state of AlignButtonsRow's three buttons so a task can't be re-triggered (or
    // overlap with another one) before it finishes.
    var isAligning by mutableStateOf(false)
        private set

    // Set by showNextImage/showPreviousImage instead of navigating immediately when an
    // auto-align/correct-zoom preview is pending (see photoTools.alignedPreview) - drives
    // ImageScreen's Save/Discard/Cancel dialog. Cleared by confirmSaveAndNavigate/
    // discardAlignedPreviewAndNavigate/cancelPendingNavigation.
    var pendingNavigation by mutableStateOf<PendingNavigationDirection?>(null)
        private set

    // The three mutually-exclusive per-photo edit tools (manual align, crop, spot stereo issues)
    // plus the pending auto-align/correct-zoom preview - see PhotoToolsState's own doc comment for
    // why this is a single object rather than nine separate mutableStateOf properties here.
    val photoTools = PhotoToolsState()

    // Bumped on every auto-align/correct-zoom attempt (success or failure) so ImageScreen's toast
    // can (re)trigger even when the same outcome repeats back-to-back - see applyAlignedPreview.
    var alignToast by mutableStateOf<AlignToast?>(null)
        private set
    private var alignToastCounter = 0
    // Bumped on every "Save" attempt (success or failure) so ImageScreen's toast can (re)trigger
    // even when the same outcome repeats back-to-back - see performSaveAligned.
    var saveToast by mutableStateOf<SaveToast?>(null)
        private set
    private var saveToastCounter = 0
    // True while performShare's file prep + Simple MAPI call is running, so the settings-menu
    // Share dialog's choices can't be triggered a second time before the first finishes (mirrors
    // isAligning's guard, but kept separate since sharing doesn't touch photoTools state at all).
    var isSharing by mutableStateOf(false)
        private set
    // Bumped only on a real Share failure (Share.EmailResult.FAILED) - see ShareToast's doc for
    // why success/cancellation don't trigger this.
    var shareToast by mutableStateOf<ShareToast?>(null)
        private set
    private var shareToastCounter = 0
    // The playlist currently open in the PlaylistEdit screen (name/photos/etc.), null otherwise.
    var editingPlaylist by mutableStateOf<Playlist?>(null)
        private set
    // Index into editingPlaylist.photos of the photo open in the PlaylistItem screen, null otherwise.
    var editingPlaylistItemIndex by mutableStateOf<Int?>(null)
        private set
    // Images found (non-recursively) in the directory chosen on the PlaylistPhotoPicker screen.
    var photoPickerFiles by mutableStateOf<List<File>>(emptyList())
        private set
    // Subset of photoPickerFiles currently ticked, added to editingPlaylist on confirm.
    var photoPickerSelectedFiles by mutableStateOf<Set<File>>(emptySet())
        private set
    // Playlists found under playlistsRoot, shown on the PlaylistList screen.
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    // True while PlaylistEdit/ImageView was entered from the PlaylistList screen (as opposed to
    // Welcome directly), so closing them returns to PlaylistList (refreshed) instead of Welcome.
    private var enteredFromPlaylistList by mutableStateOf(false)
    // The playlist currently playing in ImageView (title/photos/end slides), null when ImageView
    // shows a plain file selection instead - mirrors CameraSync3D's SlideshowViewModel.playlist.
    var playingPlaylist by mutableStateOf<Playlist?>(null)
        private set
    // The directory currently open on the Gallery screen, null otherwise.
    var galleryRoot by mutableStateOf<File?>(null)
        private set
    // Subdirectories (recursively) under galleryRoot that contain at least one image, one per
    // collapsible section on GalleryScreen.
    var galleryGroups by mutableStateOf<List<GalleryGroup>>(emptyList())
        private set
    // Which GalleryGroup.relativePath sections are currently expanded - all expanded by default
    // right after a scan, collapsible individually from there.
    var expandedGalleryGroups by mutableStateOf<Set<String>>(emptySet())
        private set
    // True while ImageView was entered from GalleryScreen, so closing it returns there
    // (instead of Welcome/PlaylistList) - mirrors enteredFromPlaylistList.
    private var enteredFromGallery by mutableStateOf(false)
    // Set on returning from ImageView to Gallery, to whichever photo was actually on screen -
    // which may differ from the one originally tapped if Left/Right was used inside ImageView.
    // GalleryScreen consumes this to scroll that photo back into view instead of leaving the
    // list wherever it happened to be scrolled to.
    var galleryScrollTarget by mutableStateOf<File?>(null)
        private set

    val currentImage: File? get() = imageFiles.getOrNull(currentImageIndex)

    // currentImageIndex ranges over -1 (title slide) .. imageFiles.size (end slide) while a
    // playlist is playing, and 0..imageFiles.lastIndex for a plain file selection.
    val playlistSlideKind: PlaylistSlideKind?
        get() = playingPlaylist?.let {
            when {
                currentImageIndex < 0 -> PlaylistSlideKind.TITLE
                currentImageIndex >= imageFiles.size -> PlaylistSlideKind.END
                else -> PlaylistSlideKind.PHOTO
            }
        }

    fun onLanguageChosen(languageTag: String?) {
        language = languageTag
    }

    fun onUseNewOpenCv5Chosen(value: Boolean) {
        useNewOpenCv5 = value
    }

    fun onKeepBestOfEachOnlyChosen(value: Boolean) {
        keepBestOfEachOnly = value
        KeepBestOfEachOnlyPreference.save(value)
        if (value) snapToVisiblePhoto()
    }

    fun onFavoritesOnlyChosen(value: Boolean) {
        favoritesOnly = value
        if (value) snapToVisiblePhoto()
    }

    fun onExcludeStereoIssuesChosen(value: Boolean) {
        excludeStereoIssues = value
        if (value) snapToVisiblePhoto()
    }

    fun onHalveLeftRightImagesChosen(value: Boolean) {
        halveLeftRightImages = value
        HalveLeftRightImagesPreference.save(value)
    }

    fun onShrinkControlsChosen(value: Boolean) {
        shrinkControls = value
        ShrinkControlsPreference.save(value)
    }

    /** Whether any of keepBestOfEachOnly/favoritesOnly/excludeStereoIssues is currently on. */
    private val anyPhotoFilterActive: Boolean
        get() = keepBestOfEachOnly || favoritesOnly || excludeStereoIssues

    /**
     * The subset of [files] that passes every currently-active filter (keepBestOfEachOnly/
     * favoritesOnly/excludeStereoIssues combined with AND) - used by showNextImage/
     * showPreviousImage/advanceSlideshow/snapToVisiblePhoto to skip filtered-out photos.
     */
    private fun visiblePhotos(files: List<File>): Set<File> {
        var visible: Set<File> = files.toSet()
        if (keepBestOfEachOnly) visible = visible intersect bestVersionsOnly(files)
        if (favoritesOnly) visible = visible.filterTo(mutableSetOf(), Exif3d::getFavoriteFromExif)
        if (excludeStereoIssues) visible = visible.filterTo(mutableSetOf()) {
            !Exif3d.getWarningFromExif(it) && !rawEditedLabel(it).startsWith("stereo_issues")
        }
        return visible
    }

    /**
     * If the currently shown photo doesn't pass every active filter, jumps to the nearest one that
     * does (forward first, then backward) so turning a filter on never leaves a filtered-out photo
     * on screen.
     */
    private fun snapToVisiblePhoto() {
        val index = currentImageIndex
        if (index !in imageFiles.indices) return
        val visible = visiblePhotos(imageFiles)
        if (imageFiles[index] in visible) return
        val target = (index + 1..imageFiles.lastIndex).firstOrNull { imageFiles[it] in visible }
            ?: (index - 1 downTo 0).firstOrNull { imageFiles[it] in visible }
        if (target != null) {
            currentImageIndex = target
            photoTools.resetAll()
        }
    }

    fun onFilesChosen(files: List<File>) {
        playingPlaylist = null
        imageFiles = files
        currentImageIndex = 0
        isAutomatedPlaylist = false
        photoTools.resetAll()
        if (anyPhotoFilterActive) snapToVisiblePhoto()
        screen = if (files.firstOrNull()?.extension?.lowercase() in videoExtensions) {
            Screen.VideoView
        } else {
            Screen.ImageView
        }
    }

    /** Recursively scans [folder] for images and switches to the Gallery screen. */
    fun openGallery(folder: File) {
        galleryRoot = folder
        val groups = scanGalleryDirectory(folder)
        galleryGroups = groups
        expandedGalleryGroups = groups.map { it.relativePath }.toSet()
        screen = Screen.Gallery
    }

    fun closeGallery() {
        galleryRoot = null
        galleryGroups = emptyList()
        expandedGalleryGroups = emptySet()
        screen = Screen.Welcome
    }

    fun openAbout() {
        screen = Screen.About
    }

    fun closeAbout() {
        screen = Screen.Welcome
    }

    /** Clears galleryScrollTarget once GalleryScreen has scrolled to it, so a later return to the
     *  same photo (null -> file) still re-triggers the scroll instead of being a no-op change. */
    fun consumeGalleryScrollTarget() {
        galleryScrollTarget = null
    }

    fun toggleGalleryGroup(relativePath: String) {
        expandedGalleryGroups = if (relativePath in expandedGalleryGroups) {
            expandedGalleryGroups - relativePath
        } else {
            expandedGalleryGroups + relativePath
        }
    }

    /** Opens [group]'s photo at [index] fullscreen; Left/Right then navigate that group only. */
    fun openGalleryImage(group: GalleryGroup, index: Int) {
        playingPlaylist = null
        imageFiles = group.files
        currentImageIndex = index
        isAutomatedPlaylist = false
        photoTools.resetAll()
        if (anyPhotoFilterActive) snapToVisiblePhoto()
        enteredFromGallery = true
        screen = Screen.ImageView
    }

    fun onPlaylistChosen(playlist: Playlist, files: List<File>, isAutomated: Boolean, intervalMs: Long) {
        playingPlaylist = playlist
        imageFiles = files
        currentImageIndex = -1 // start on the title slide
        isAutomatedPlaylist = isAutomated
        slideshowIntervalMs = intervalMs
        photoTools.resetAll()
        screen = Screen.ImageView
    }

    /** Applies a finished auto-align/correct-zoom attempt's result and (re)triggers [alignToast]. */
    fun applyAlignedPreview(result: AutoAlign.AutoAlignResult?, kind: AlignKind? = null) {
        photoTools.applyAlignedPreview(result, kind)
        alignToastCounter++
        alignToast = AlignToast(
            success = result != null,
            token = alignToastCounter,
            zoomScale = result?.zoomScale,
            rotationDegrees = result?.rotationDegrees,
        )
    }

    /** Enters manual-align mode for the currently shown photo - see Main.kt's arrow-key handling. */
    fun startManualAlign() {
        if (isAligning) return
        photoTools.startManualAlign()
    }

    /** Enters crop mode for the currently shown photo - see Exif3dInfoPanel's Crop button. */
    fun startCrop() {
        if (isAligning) return
        photoTools.startCrop()
    }

    /** Records the rectangle drawn in ImageScreen's onCropDragEnd, switching to the review phase. */
    fun finalizeCropRect(rect: CropRectFraction) {
        photoTools.finalizeCropRect(rect)
    }

    /** Discards the crop tool (drawn rectangle or not) without touching disk. */
    fun cancelCrop() {
        photoTools.cancelCrop()
    }

    /**
     * Writes the pending crop rectangle to disk via [Crop.saveCrop] (which reuses
     * [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step every save path uses)
     * and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveManualAlign].
     */
    suspend fun performSaveCrop() {
        if (isAligning || !photoTools.cropMode) return
        val file = currentImage
        val rect = photoTools.cropRect ?: return
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { Crop.saveCrop(file, rect) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, token = saveToastCounter)
            photoTools.cancelCrop()
            if (saved == null) return
            Analytics.logEvent("crop_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Enters "spot stereo issues" mode for the currently shown photo - see Exif3dInfoPanel's button. */
    fun startSpotIssues() {
        if (isAligning) return
        photoTools.startSpotIssues()
    }

    /** Appends one rectangle drawn in ImageScreen's onSpotIssueDragEnd - the tool stays active so
     *  further rectangles can be drawn, unlike [finalizeCropRect]'s single-rectangle review phase. */
    fun addSpotIssueRect(rect: IssueRectFraction) {
        photoTools.addSpotIssueRect(rect)
    }

    /** Discards the "spot stereo issues" tool (drawn rectangles or not) without touching disk. */
    fun cancelSpotIssues() {
        photoTools.cancelSpotIssues()
    }

    /**
     * Writes the pending rectangles to disk via [SpotStereoIssues.saveSpotIssues] (which reuses
     * [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step every save path uses)
     * and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveCrop].
     */
    suspend fun performSaveSpotIssues() {
        if (isAligning || !photoTools.spotIssuesMode) return
        val file = currentImage
        val rects = photoTools.spotIssueRects
        if (rects.isEmpty()) return
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { SpotStereoIssues.saveSpotIssues(file, rects) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, token = saveToastCounter)
            photoTools.cancelSpotIssues()
            if (saved == null) return
            Analytics.logEvent("spot_issues_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Adds a whole-pixel delta to the pending manual-align offset - see Main.kt's tick loop. */
    fun nudgeManualAlign(dx: Int, dy: Int) {
        photoTools.nudgeManualAlign(dx, dy)
    }

    /** Discards the pending manual-align offset without touching disk. */
    fun cancelManualAlign() {
        photoTools.cancelManualAlign()
    }

    /**
     * Writes the pending manual-align offset to disk via [ManualAlign.saveManualAlign] (which
     * reuses [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step performSaveAligned
     * uses) and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveAligned].
     */
    suspend fun performSaveManualAlign() {
        if (isAligning || !photoTools.manualAlignMode) return
        val file = currentImage
        val dx = photoTools.manualAlignOffsetX
        val dy = photoTools.manualAlignOffsetY
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { ManualAlign.saveManualAlign(file, dx, dy) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, token = saveToastCounter)
            photoTools.cancelManualAlign()
            if (saved == null) return
            Analytics.logEvent("align_save", mapOf("mode" to "manual_align"))
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /**
     * Runs auto-align/correct-zoom for [file] on a background thread (rather than blocking the UI
     * thread like the old synchronous call did) and applies the result when done. [isAligning]
     * is true for the whole duration so AlignButtonsRow's buttons can disable themselves and avoid
     * a second task overlapping this one.
     */
    suspend fun performAutoAlign(file: File, kind: AlignKind) {
        if (isAligning) return
        isAligning = true
        try {
            val result = withContext(Dispatchers.IO) { AutoAlign.autoAlign(file, kind, useNewOpenCv5) }
            applyAlignedPreview(result, kind)
        } finally {
            isAligning = false
        }
    }

    /**
     * Redoes the pending align against the original file (not the in-memory preview, to avoid
     * re-compressing an already-lossy preview - mirrors CameraSync3D's save flow, which reapplies
     * the pending align to the original at save time instead of re-encoding the cached preview)
     * and writes it to disk under a new filename, then inserts the new file right after the
     * current one and jumps to it. Runs on a background thread and keeps [isAligning] true for the
     * duration, same treatment as [performAutoAlign].
     */
    suspend fun performSaveAligned() {
        if (isAligning) return
        val file = currentImage
        val kind = photoTools.pendingAlignKind
        isAligning = true
        try {
            val saved = if (file != null && kind != null) {
                withContext(Dispatchers.IO) { AutoAlign.saveAligned(file, kind, useNewOpenCv5) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, token = saveToastCounter)
            if (saved == null) return
            kind?.let {
                val mode = if (it == AlignKind.HOMOGRAPHY) "auto_align" else "correct_zoom_issues"
                Analytics.logEvent("align_save", mapOf("mode" to mode))
            }
            photoTools.resetAll()
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Which eye-half to keep as a standalone 2D photo when deleting an unedited stereo pair - see [performDeleteCurrentImage]. */
    enum class KeepHalfSide { LEFT, RIGHT }

    /**
     * Deletes the currently shown photo from disk. With [keepHalf] set (InfoPanel's delete tool
     * offers this only for an unedited "_raw" photo - see GalleryScreen.kt's rawEditedLabel), that
     * eye-half is first saved as a standalone 2D JPEG via [KeepHalf.saveHalf] (same file-naming/
     * EXIF-copy step every save path uses) and swapped into the current slot, triggering [saveToast]
     * like performSaveCrop/performSaveManualAlign/etc.; with [keepHalf] null the photo is simply
     * removed from [imageFiles] and disk with no replacement - same shape as [deletePlaylistItem] -
     * landing on whichever photo is now at the same index (the previous one if the deletion emptied
     * the tail), or closing the viewer if the list becomes empty. No toast for this path: the photo
     * disappearing from the list is its own feedback, and a failed disk delete (e.g. a locked file)
     * simply leaves the photo in place rather than desyncing the list from disk.
     */
    suspend fun performDeleteCurrentImage(keepHalf: KeepHalfSide? = null) {
        if (isAligning) return
        val file = currentImage ?: return
        isAligning = true
        try {
            if (keepHalf != null) {
                val saved = withContext(Dispatchers.IO) { KeepHalf.saveHalf(file, keepHalf == KeepHalfSide.LEFT) }
                saveToastCounter++
                saveToast = SaveToast(success = saved != null, token = saveToastCounter)
                if (saved == null) return
                withContext(Dispatchers.IO) { file.delete() }
                imageFiles = imageFiles.toMutableList().apply { this[currentImageIndex] = saved }
                photoTools.resetAll()
                Analytics.logEvent("photo_delete", mapOf("keep_half" to keepHalf.name.lowercase()))
            } else {
                val deleted = withContext(Dispatchers.IO) { file.delete() }
                if (!deleted) return
                val newFiles = imageFiles.toMutableList().apply { removeAt(currentImageIndex) }
                imageFiles = newFiles
                if (newFiles.isEmpty()) {
                    closeImageView()
                } else {
                    currentImageIndex = currentImageIndex.coerceAtMost(newFiles.lastIndex)
                    photoTools.resetAll()
                }
                Analytics.logEvent("photo_delete", mapOf("keep_half" to "none"))
            }
        } finally {
            isAligning = false
        }
    }

    /**
     * Prepares the currently shown photo per [type] (see Share.prepareShareFile) and hands it to
     * the default email program (Share.shareViaEmail), both off the UI thread since Simple MAPI's
     * MAPI_DIALOG blocks until the compose window is sent or dismissed. [isSharing] guards against
     * a second Share attempt overlapping this one, same shape as [isAligning] elsewhere.
     */
    suspend fun performShare(type: Share.ShareType) {
        if (isSharing) return
        val file = currentImage ?: return
        isSharing = true
        try {
            val prepared = withContext(Dispatchers.IO) { Share.prepareShareFile(file, type) }
            val result = if (prepared != null) {
                withContext(Dispatchers.IO) { Share.shareViaEmail(prepared) }
            } else {
                Share.EmailResult.FAILED
            }
            if (result == Share.EmailResult.SENT) {
                Analytics.logEvent("share", mapOf("type" to type.name.lowercase()))
            }
            if (result == Share.EmailResult.FAILED) {
                shareToastCounter++
                shareToast = ShareToast(shareToastCounter)
            }
        } finally {
            isSharing = false
        }
    }

    private val playlistsRoot: File
        get() = File(File(System.getProperty("user.home"), "Pictures"), "sbs3dFullscreen")

    private fun loadPlaylistsFromRoot(): List<Playlist> {
        val storage = DesktopPlaylistStorage(playlistsRoot)
        val dirs = playlistsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.sortedBy { it.name.lowercase() }.map { Playlist.loadPlaylist(storage, it.name) }
    }

    /** Scans playlistsRoot for playlist folders and switches to the PlaylistList screen. */
    fun openPlaylistList() {
        playlists = loadPlaylistsFromRoot()
        enteredFromPlaylistList = false
        screen = Screen.PlaylistList
    }

    /** Re-scans playlistsRoot without leaving the PlaylistList screen. */
    fun refreshPlaylistList() {
        playlists = loadPlaylistsFromRoot()
    }

    fun closePlaylistList() {
        screen = Screen.Welcome
    }

    /** Opens the given playlist (picked from the list screen) in the editor. */
    fun openPlaylistForEdit(playlist: Playlist) {
        editingPlaylist = playlist
        enteredFromPlaylistList = true
        screen = Screen.PlaylistEdit
    }

    /** Starts the slideshow directly for the given playlist (picked from the list screen). */
    fun playPlaylist(playlist: Playlist) {
        editingPlaylist = null
        enteredFromPlaylistList = true
        val files = playlist.photos.map { playlistItemFile(it.imageUriString) }
        onPlaylistChosen(playlist, files, playlist.isAutomated, playlist.defaultDurationS * 1000)
    }

    /** Where closing PlaylistEdit/ImageView should land, refreshing the list if it's the target. */
    private fun returnFromChildScreen(): Screen {
        val target = if (enteredFromPlaylistList) Screen.PlaylistList else Screen.Welcome
        enteredFromPlaylistList = false
        if (target == Screen.PlaylistList) playlists = loadPlaylistsFromRoot()
        return target
    }

    /**
     * Copies an externally-selected playlist folder (as created/synced by CameraSync3D, or a plain
     * folder of JPEGs) into playlistsRoot and opens it for editing, mirroring startCreatePlaylist's
     * create-then-edit flow. Returns false without copying anything if a playlist with the same
     * folder name already exists in the root.
     */
    fun importPlaylistFolder(folder: File): Boolean {
        // Sanitized like startCreatePlaylist's dirName, so an imported folder can never end up
        // with a different on-disk name (e.g. spaces) than a playlist created directly from the
        // same display name would get - otherwise the two could coexist as distinct directories
        // that both normalize to the same Playlist.getDirName(), which broke the PlaylistList's
        // LazyColumn item keys (duplicate key crash).
        val destination = File(playlistsRoot, sanitizedDirName(folder.name))
        if (destination.exists()) return false
        playlistsRoot.mkdirs()
        folder.copyRecursively(destination)

        val storage = DesktopPlaylistStorage(playlistsRoot)
        val playlist = Playlist.loadPlaylist(storage, destination.name)
        editingPlaylist = playlist
        screen = Screen.PlaylistEdit
        Analytics.logEvent("playlist_created", mapOf("source" to "import"))
        return true
    }

    /** Whether a new playlist named [name] wouldn't collide with an existing one already on disk. */
    fun canCreatePlaylist(name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return true
        return !File(playlistsRoot, sanitizedDirName(trimmedName)).exists()
    }

    /**
     * Creates a playlist folder under Pictures/sbs3dFullscreen/{name} and switches to the
     * PlaylistEdit screen for it, mirroring CameraSync3D's create-then-add-photos flow.
     * Assumes the caller already checked [canCreatePlaylist]; if the sanitized name still
     * collides with an existing playlist, that existing playlist is reopened instead.
     */
    fun startCreatePlaylist(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        // Strips characters that are invalid in Windows folder names; the display name (with
        // spaces/accents intact) is kept separately in the Playlist itself.
        val dirName = sanitizedDirName(trimmedName)
        val folder = File(playlistsRoot, dirName)
        folder.mkdirs()

        val storage = DesktopPlaylistStorage(playlistsRoot)
        val playlist = if (storage.indexFileExists(dirName)) {
            Playlist.loadPlaylist(storage, dirName)
        } else {
            Playlist(name = trimmedName, absolutePath = folder.absolutePath).also { it.save(storage) }
                .also { Analytics.logEvent("playlist_created", mapOf("source" to "new")) }
        }
        editingPlaylist = playlist
        screen = Screen.PlaylistEdit
    }

    /** Scans [folder] (non-recursively) for JPEGs and opens the PlaylistPhotoPicker screen on them. */
    fun openPlaylistPhotoPicker(folder: File) {
        photoPickerFiles = folder.listFiles { f -> f.isFile && f.extension.lowercase() in galleryImageExtensions }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        photoPickerSelectedFiles = emptySet()
        screen = Screen.PlaylistPhotoPicker
    }

    fun togglePlaylistPhotoPickerSelection(file: File) {
        photoPickerSelectedFiles = if (file in photoPickerSelectedFiles) {
            photoPickerSelectedFiles - file
        } else {
            photoPickerSelectedFiles + file
        }
    }

    /** Discards the in-progress picker selection and returns to PlaylistEdit. */
    fun closePlaylistPhotoPicker() {
        photoPickerFiles = emptyList()
        photoPickerSelectedFiles = emptySet()
        screen = Screen.PlaylistEdit
    }

    /** Adds the ticked photos to the playlist being edited, then returns to PlaylistEdit. */
    fun confirmPlaylistPhotoPickerSelection() {
        addPhotosToEditingPlaylist(photoPickerSelectedFiles.toList())
        closePlaylistPhotoPicker()
    }

    /** Copies the given files into the playlist being edited and appends them to its index. */
    fun addPhotosToEditingPlaylist(files: List<File>) {
        val playlist = editingPlaylist ?: return
        val folder = File(playlist.absolutePath)
        val storage = DesktopPlaylistStorage(folder.parentFile ?: folder)
        val copiedItems = files.map { src ->
            val dest = File(folder, src.name)
            if (src.canonicalFile != dest.canonicalFile) {
                src.copyTo(dest, overwrite = true)
            }
            // toPath().toUri() (not File.toURI()) - on Windows, File.toURI() emits the ambiguous
            // "file:/C:/..." single-slash form, which coil3's Uri parser mis-parses: it treats the
            // drive letter's ':' as a second scheme separator and drops "C:" from the path, so the
            // thumbnail fails to load. Path.toUri() emits the unambiguous "file:///C:/..." form.
            PlaylistItem(dest.name, dest.toPath().toUri().toString())
        }
        val updatedPlaylist = playlist.copy(photos = playlist.photos + copiedItems)
        updatedPlaylist.save(storage)
        editingPlaylist = updatedPlaylist
        if (copiedItems.isNotEmpty()) {
            Analytics.logEvent("playlist_photos_added", mapOf("count" to copiedItems.size))
        }
    }

    /** Starts the slideshow for the playlist currently open in the PlaylistEdit screen. */
    fun playEditingPlaylist() {
        val playlist = editingPlaylist ?: return
        val files = playlist.photos.map { playlistItemFile(it.imageUriString) }
        onPlaylistChosen(playlist, files, playlist.isAutomated, playlist.defaultDurationS * 1000)
    }

    /**
     * Sanitizes a playlist name into the folder name it would get, mirroring startCreatePlaylist.
     * Must also strip spaces to match Playlist.getDirName() (shared with Android), which strips
     * spaces from the folder name when deriving the dir name used to read/write the index file -
     * otherwise the folder created here and the one save()/load() target end up different.
     */
    private fun sanitizedDirName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").replace(" ", "").ifEmpty { "Playlist" }

    /** Whether newName's folder doesn't collide with another playlist already on disk. */
    fun canRenamePlaylist(newName: String): Boolean {
        val playlist = editingPlaylist ?: return true
        if (newName.equals(playlist.name, ignoreCase = true)) return true
        val root = File(playlist.absolutePath).parentFile ?: return true
        return !File(root, sanitizedDirName(newName)).exists()
    }

    /** Renames the playlist being edited: moves its folder on disk and updates the index. */
    fun modifyPlaylistName(newName: String): Boolean {
        val playlist = editingPlaylist ?: return false
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty() || !canRenamePlaylist(trimmedName)) return false
        val currentFolder = File(playlist.absolutePath)
        val root = currentFolder.parentFile ?: return false
        val newFolder = File(root, sanitizedDirName(trimmedName))
        if (newFolder != currentFolder && !currentFolder.renameTo(newFolder)) return false
        saveEditingPlaylist(playlist.copy(name = trimmedName, absolutePath = newFolder.absolutePath))
        return true
    }

    /** Updates the slideshow default duration between slides, in seconds (1..60, like CameraSync3D). */
    fun modifyDefaultDuration(newDurationS: Long): Boolean {
        val playlist = editingPlaylist ?: return false
        if (newDurationS !in 1..60) return false
        saveEditingPlaylist(playlist.copy(defaultDurationS = newDurationS))
        return true
    }

    fun modifyIsAutomated(newValue: Boolean): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(isAutomated = newValue))
        return true
    }

    fun modifySubtitle(newValue: String): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(subtitle = newValue))
        return true
    }

    fun modifyTitleZPercent(newValue: Float): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(titleZPercent = newValue))
        return true
    }

    fun modifySubtitleZPercent(newValue: Float): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(subtitleZPercent = newValue))
        return true
    }

    fun modifyTitleStyle(newValue: TextStyleConfig): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(titleStyle = newValue))
        return true
    }

    fun modifySubtitleStyle(newValue: TextStyleConfig): Boolean {
        saveEditingPlaylist((editingPlaylist ?: return false).copy(subtitleStyle = newValue))
        return true
    }

    /** Applies a fully-reordered photo list (e.g. after drag-and-drop) and saves to disk. */
    fun applyPhotosReorder(newPhotos: List<PlaylistItem>) {
        saveEditingPlaylist((editingPlaylist ?: return).copy(photos = newPhotos))
    }

    /** Opens the given photo (picked from the PlaylistEdit screen's list) in the PlaylistItem screen. */
    fun openPlaylistItem(index: Int) {
        editingPlaylistItemIndex = index
        screen = Screen.PlaylistItem
    }

    fun closePlaylistItem() {
        editingPlaylistItemIndex = null
        screen = Screen.PlaylistEdit
    }

    /** Applies [transform] to the photo open in the PlaylistItem screen and saves to disk. */
    private fun modifyEditingItem(transform: (PlaylistItem) -> PlaylistItem): Boolean {
        val playlist = editingPlaylist ?: return false
        val index = editingPlaylistItemIndex ?: return false
        val photo = playlist.photos.getOrNull(index) ?: return false
        val newPhotos = playlist.photos.toMutableList().apply { this[index] = transform(photo) }.toList()
        saveEditingPlaylist(playlist.copy(photos = newPhotos))
        return true
    }

    fun modifyItemComment(newValue: String): Boolean = modifyEditingItem { it.copy(comment = newValue) }

    fun modifyItemCommentZPercent(newValue: Float): Boolean = modifyEditingItem { it.copy(commentZPercent = newValue) }

    fun modifyItemDuration(newValue: Int): Boolean = modifyEditingItem { it.copy(durationS = newValue) }

    fun modifyItemHalfWidth(newValue: Boolean) {
        modifyEditingItem { it.copy(isHalfWidth = newValue) }
    }

    /** Deletes the photo open in the PlaylistItem screen: removes it from the playlist and from disk. */
    fun deletePlaylistItem(): Boolean {
        val playlist = editingPlaylist ?: return false
        val index = editingPlaylistItemIndex ?: return false
        val photo = playlist.photos.getOrNull(index) ?: return false
        val newPhotos = playlist.photos.toMutableList().apply { removeAt(index) }.toList()
        saveEditingPlaylist(playlist.copy(photos = newPhotos))
        playlistItemFile(photo.imageUriString).delete()
        return true
    }

    /** Deletes the playlist being edited (folder and all) from disk. */
    fun deletePlaylist(): Boolean {
        val playlist = editingPlaylist ?: return false
        val deleted = File(playlist.absolutePath).deleteRecursively()
        if (deleted) {
            editingPlaylist = null
        }
        return deleted
    }

    private fun saveEditingPlaylist(updated: Playlist) {
        val folder = File(updated.absolutePath)
        val storage = DesktopPlaylistStorage(folder.parentFile ?: folder)
        updated.save(storage)
        editingPlaylist = updated
        Analytics.logEvent("playlist_saved")
    }

    fun closePlaylistEdit() {
        editingPlaylist = null
        screen = returnFromChildScreen()
    }

    fun closeImageView() {
        // A slideshow started from PlaylistEdit's "Play" button returns there instead of Welcome/PlaylistList.
        playingPlaylist = null
        // Otherwise the stale toast re-flashes on next open: leaving ImageView recreates the whole
        // Window (see Main.kt's key(undecorated)), which resets StereoToast's remembered visibility
        // but not this still-non-null trigger, so its LaunchedEffect fires again on first composition.
        alignToast = null
        saveToast = null
        shareToast = null
        screen = when {
            editingPlaylist != null -> Screen.PlaylistEdit
            enteredFromGallery -> {
                enteredFromGallery = false
                galleryScrollTarget = currentImage
                Screen.Gallery
            }
            else -> returnFromChildScreen()
        }
    }

    /**
     * Advances to the next photo, or - while playing a playlist - into/out of the title and end
     * slides: TITLE -> first photo -> ... -> last photo -> END -> (wraps back to) TITLE, mirroring
     * CameraSync3D's SlideshowViewModel.nextPhoto()/play() "relaunch" behavior.
     */
    /** Navigates forward, or asks first (see [pendingNavigation]) if an auto-align/correct-zoom
     *  preview is pending for the current photo - it would otherwise be silently discarded by
     *  [navigateNext]'s [PhotoToolsState.resetAll] call. */
    fun showNextImage() {
        if (photoTools.alignedPreview != null) {
            pendingNavigation = PendingNavigationDirection.NEXT
            return
        }
        navigateNext()
    }

    /** Same guard as [showNextImage], for the Previous direction. */
    fun showPreviousImage() {
        if (photoTools.alignedPreview != null) {
            pendingNavigation = PendingNavigationDirection.PREVIOUS
            return
        }
        navigatePrevious()
    }

    /** Logs one "picture viewed" event per photo landed on while a playlist is playing (title/end
     *  slides don't count) - shared by navigateNext/navigatePrevious/advanceSlideshow so manual
     *  navigation and the automated timer are counted the same way. */
    private fun trackPlaylistPhotoViewedIfApplicable() {
        if (playingPlaylist != null && playlistSlideKind == PlaylistSlideKind.PHOTO) {
            Analytics.logEvent("playlist_photo_viewed")
        }
    }

    private fun navigateNext() {
        val upperBound = if (playingPlaylist != null) imageFiles.size else imageFiles.lastIndex
        if (currentImageIndex >= upperBound) {
            if (playingPlaylist != null && currentImageIndex == imageFiles.size) {
                currentImageIndex = -1 // replay: END -> TITLE
                photoTools.resetAll()
            }
            return
        }
        var nextIndex = currentImageIndex + 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (nextIndex < upperBound && imageFiles[nextIndex] !in visible) nextIndex++
            // Nothing further to skip to before the boundary (last photo / end slide) - stay put
            // rather than land on a filtered-out photo, which is what let raw/edited pairs both
            // stay browsable at the end of a list.
            if (nextIndex < imageFiles.size && imageFiles[nextIndex] !in visible) return
        }
        currentImageIndex = nextIndex
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }

    private fun navigatePrevious() {
        val lowerBound = if (playingPlaylist != null) -1 else 0
        if (currentImageIndex <= lowerBound) return
        var previousIndex = currentImageIndex - 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (previousIndex > lowerBound && imageFiles[previousIndex] !in visible) previousIndex--
            if (previousIndex >= 0 && imageFiles[previousIndex] !in visible) return
        }
        currentImageIndex = previousIndex
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }

    /** Dismisses [pendingNavigation]'s dialog without navigating or touching the pending preview. */
    fun cancelPendingNavigation() {
        pendingNavigation = null
    }

    /** Discards the pending auto-align/correct-zoom preview (without writing it to disk) and
     *  carries out the navigation that was waiting on [pendingNavigation]. */
    fun discardAlignedPreviewAndNavigate() {
        val direction = pendingNavigation ?: return
        pendingNavigation = null
        photoTools.resetAll()
        when (direction) {
            PendingNavigationDirection.NEXT -> navigateNext()
            PendingNavigationDirection.PREVIOUS -> navigatePrevious()
        }
    }

    /** Writes the pending auto-align/correct-zoom preview to disk (see [performSaveAligned]) and,
     *  only if that succeeds, carries out the navigation that was waiting on [pendingNavigation] -
     *  a failed save leaves the preview and the dialog's trigger in place so the user can retry. */
    suspend fun confirmSaveAlignedAndNavigate() {
        val direction = pendingNavigation ?: return
        performSaveAligned()
        if (photoTools.alignedPreview != null) return // save failed - stay put, let saveToast report it
        pendingNavigation = null
        when (direction) {
            PendingNavigationDirection.NEXT -> navigateNext()
            PendingNavigationDirection.PREVIOUS -> navigatePrevious()
        }
    }

    /** Advances to the next photo (or the end slide) - used by the playlist auto-advance timer only. */
    fun advanceSlideshow() {
        if (imageFiles.isEmpty() || currentImageIndex >= imageFiles.size) return
        var nextIndex = currentImageIndex + 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (nextIndex < imageFiles.size && imageFiles[nextIndex] !in visible) nextIndex++
        }
        currentImageIndex = nextIndex
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }
}
