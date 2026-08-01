import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import java.io.File

enum class Screen { Welcome, PlaylistList, PlaylistEdit, PlaylistItem, ImageView, VideoView }

/**
 * Mirrors CameraSync3D's SlideshowViewModel.UiType: a playlist slideshow is a title slide,
 * then its photos, then an end slide - not just a bare loop over image files.
 */
enum class PlaylistSlideKind { TITLE, PHOTO, END }

private val videoExtensions = setOf("mp4", "mov", "mkv", "avi")

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
    // Only set when imageFiles came from a playlist with isAutomated=true; drives the
    // auto-advance timer in Main.kt. Plain file selections never auto-advance.
    var isAutomatedPlaylist by mutableStateOf(false)
        private set
    var slideshowIntervalMs by mutableStateOf(10_000L)
        private set
    // Ephemeral auto-align result for the current image only (not persisted to disk) - cleared
    // on every navigation so it never sticks to the wrong photo.
    var alignedPreview by mutableStateOf<ImageBitmap?>(null)
        private set
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

    fun onFilesChosen(files: List<File>) {
        playingPlaylist = null
        imageFiles = files
        currentImageIndex = 0
        isAutomatedPlaylist = false
        alignedPreview = null
        screen = if (files.firstOrNull()?.extension?.lowercase() in videoExtensions) {
            Screen.VideoView
        } else {
            Screen.ImageView
        }
    }

    fun onPlaylistChosen(playlist: Playlist, files: List<File>, isAutomated: Boolean, intervalMs: Long) {
        playingPlaylist = playlist
        imageFiles = files
        currentImageIndex = -1 // start on the title slide
        isAutomatedPlaylist = isAutomated
        slideshowIntervalMs = intervalMs
        alignedPreview = null
        screen = Screen.ImageView
    }

    fun applyAlignedPreview(bitmap: ImageBitmap?) {
        alignedPreview = bitmap
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
        screen = if (editingPlaylist != null) Screen.PlaylistEdit else returnFromChildScreen()
    }

    /**
     * Advances to the next photo, or - while playing a playlist - into/out of the title and end
     * slides: TITLE -> first photo -> ... -> last photo -> END -> (wraps back to) TITLE, mirroring
     * CameraSync3D's SlideshowViewModel.nextPhoto()/play() "relaunch" behavior.
     */
    fun showNextImage() {
        val upperBound = if (playingPlaylist != null) imageFiles.size else imageFiles.lastIndex
        if (currentImageIndex < upperBound) {
            currentImageIndex++
            alignedPreview = null
        } else if (playingPlaylist != null && currentImageIndex == imageFiles.size) {
            currentImageIndex = -1 // replay: END -> TITLE
            alignedPreview = null
        }
    }

    fun showPreviousImage() {
        val lowerBound = if (playingPlaylist != null) -1 else 0
        if (currentImageIndex > lowerBound) {
            currentImageIndex--
            alignedPreview = null
        }
    }

    /** Advances to the next photo (or the end slide) - used by the playlist auto-advance timer only. */
    fun advanceSlideshow() {
        if (imageFiles.isEmpty() || currentImageIndex >= imageFiles.size) return
        currentImageIndex++
        alignedPreview = null
    }
}
