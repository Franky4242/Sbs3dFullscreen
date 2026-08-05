import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences

enum class Screen { Welcome, Gallery, PlaylistList, PlaylistEdit, PlaylistItem, ImageView, VideoView }

/**
 * Mirrors CameraSync3D's SlideshowViewModel.UiType: a playlist slideshow is a title slide,
 * then its photos, then an end slide - not just a bare loop over image files.
 */
enum class PlaylistSlideKind { TITLE, PHOTO, END }

private val videoExtensions = setOf("mp4", "mov", "mkv", "avi")

/** One auto-align/correct-zoom attempt's outcome - see AppViewModel.alignToast. */
data class AlignToast(val success: Boolean, val token: Int)

/** One "Save" button attempt's outcome - see AppViewModel.saveToast. */
data class SaveToast(val success: Boolean, val token: Int)

private val galleryImageExtensions = setOf("jpg", "jpeg")

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
    // (see GalleryScreen.kt's bestVersionsOnly). Not persisted to disk, same as useNewOpenCv5.
    var keepBestOfEachOnly by mutableStateOf(false)
        private set
    // Toggled from ImageScreen's settings menu: when true, the combined L+R photo is squeezed
    // horizontally by 2 before display, matching the input a Half-SBS 3D monitor expects (each eye
    // half already at full native resolution in the source file, so the whole frame must be
    // squeezed to the monitor's native width for its own hardware to unsqueeze per eye) - see
    // ImageScreen.kt's StereoImage. Unlike keepBestOfEachOnly/useNewOpenCv5, this is persisted
    // (HalveLeftRightImagesPreference below) since it depends on the user's monitor, not the
    // current viewing session, and defaults to on to match the common Half-SBS setup.
    var halveLeftRightImages by mutableStateOf(HalveLeftRightImagesPreference.load())
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

    // Ephemeral auto-align result for the current image only (not persisted to disk) - cleared
    // on every navigation so it never sticks to the wrong photo.
    var alignedPreview by mutableStateOf<ImageBitmap?>(null)
        private set
    // Which algorithm produced alignedPreview - needed so Save knows what to redo against the
    // original file (see performSaveAligned).
    var pendingAlignKind by mutableStateOf<AlignKind?>(null)
        private set
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
    // The playlist currently open in the PlaylistEdit screen (name/photos/etc.), null otherwise.
    var editingPlaylist by mutableStateOf<Playlist?>(null)
        private set
    // Index into editingPlaylist.photos of the photo open in the PlaylistItem screen, null otherwise.
    var editingPlaylistItemIndex by mutableStateOf<Int?>(null)
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
        if (value) snapToBestVersion()
    }

    fun onHalveLeftRightImagesChosen(value: Boolean) {
        halveLeftRightImages = value
        HalveLeftRightImagesPreference.save(value)
    }

    /**
     * If the currently shown photo isn't the best version in its group, jumps to the nearest one
     * (forward first, then backward) so turning "keep best of each" on never leaves a filtered-out
     * photo on screen.
     */
    private fun snapToBestVersion() {
        val index = currentImageIndex
        if (index !in imageFiles.indices) return
        val best = bestVersionsOnly(imageFiles)
        if (imageFiles[index] in best) return
        val target = (index + 1..imageFiles.lastIndex).firstOrNull { imageFiles[it] in best }
            ?: (index - 1 downTo 0).firstOrNull { imageFiles[it] in best }
        if (target != null) {
            currentImageIndex = target
            clearAlignedPreview()
        }
    }

    fun onFilesChosen(files: List<File>) {
        playingPlaylist = null
        imageFiles = files
        currentImageIndex = 0
        isAutomatedPlaylist = false
        clearAlignedPreview()
        if (keepBestOfEachOnly) snapToBestVersion()
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
        clearAlignedPreview()
        if (keepBestOfEachOnly) snapToBestVersion()
        enteredFromGallery = true
        screen = Screen.ImageView
    }

    fun onPlaylistChosen(playlist: Playlist, files: List<File>, isAutomated: Boolean, intervalMs: Long) {
        playingPlaylist = playlist
        imageFiles = files
        currentImageIndex = -1 // start on the title slide
        isAutomatedPlaylist = isAutomated
        slideshowIntervalMs = intervalMs
        clearAlignedPreview()
        screen = Screen.ImageView
    }

    fun applyAlignedPreview(bitmap: ImageBitmap?, kind: AlignKind? = null) {
        alignedPreview = bitmap
        pendingAlignKind = if (bitmap != null) kind else null
        alignToastCounter++
        alignToast = AlignToast(success = bitmap != null, token = alignToastCounter)
    }

    private fun clearAlignedPreview() {
        alignedPreview = null
        pendingAlignKind = null
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
            val bitmap = withContext(Dispatchers.IO) { AutoAlign.autoAlign(file, kind, useNewOpenCv5) }
            applyAlignedPreview(bitmap, kind)
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
        val kind = pendingAlignKind
        isAligning = true
        try {
            val saved = if (file != null && kind != null) {
                withContext(Dispatchers.IO) { AutoAlign.saveAligned(file, kind, useNewOpenCv5) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, token = saveToastCounter)
            if (saved == null) return
            clearAlignedPreview()
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
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
        }
        editingPlaylist = playlist
        screen = Screen.PlaylistEdit
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
    fun showNextImage() {
        val upperBound = if (playingPlaylist != null) imageFiles.size else imageFiles.lastIndex
        if (currentImageIndex >= upperBound) {
            if (playingPlaylist != null && currentImageIndex == imageFiles.size) {
                currentImageIndex = -1 // replay: END -> TITLE
                clearAlignedPreview()
            }
            return
        }
        var nextIndex = currentImageIndex + 1
        if (keepBestOfEachOnly) {
            val best = bestVersionsOnly(imageFiles)
            while (nextIndex < upperBound && imageFiles[nextIndex] !in best) nextIndex++
            // Nothing further to skip to before the boundary (last photo / end slide) - stay put
            // rather than land on a filtered-out photo, which is what let raw/edited pairs both
            // stay browsable at the end of a list.
            if (nextIndex < imageFiles.size && imageFiles[nextIndex] !in best) return
        }
        currentImageIndex = nextIndex
        clearAlignedPreview()
    }

    fun showPreviousImage() {
        val lowerBound = if (playingPlaylist != null) -1 else 0
        if (currentImageIndex <= lowerBound) return
        var previousIndex = currentImageIndex - 1
        if (keepBestOfEachOnly) {
            val best = bestVersionsOnly(imageFiles)
            while (previousIndex > lowerBound && imageFiles[previousIndex] !in best) previousIndex--
            if (previousIndex >= 0 && imageFiles[previousIndex] !in best) return
        }
        currentImageIndex = previousIndex
        clearAlignedPreview()
    }

    /** Advances to the next photo (or the end slide) - used by the playlist auto-advance timer only. */
    fun advanceSlideshow() {
        if (imageFiles.isEmpty() || currentImageIndex >= imageFiles.size) return
        var nextIndex = currentImageIndex + 1
        if (keepBestOfEachOnly) {
            val best = bestVersionsOnly(imageFiles)
            while (nextIndex < imageFiles.size && imageFiles[nextIndex] !in best) nextIndex++
        }
        currentImageIndex = nextIndex
        clearAlignedPreview()
    }
}
