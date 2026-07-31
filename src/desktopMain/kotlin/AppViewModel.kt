import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import fr.camera3d.camera.feature_playlists.domain.Playlist
import java.io.File

enum class Screen { Welcome, ImageView, VideoView }

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

    val currentImage: File? get() = imageFiles.getOrNull(currentImageIndex)

    fun onLanguageChosen(languageTag: String?) {
        language = languageTag
    }

    fun onFilesChosen(files: List<File>) {
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

    fun onPlaylistChosen(files: List<File>, isAutomated: Boolean, intervalMs: Long) {
        imageFiles = files
        currentImageIndex = 0
        isAutomatedPlaylist = isAutomated
        slideshowIntervalMs = intervalMs
        alignedPreview = null
        screen = Screen.ImageView
    }

    fun applyAlignedPreview(bitmap: ImageBitmap?) {
        alignedPreview = bitmap
    }

    /**
     * Loads a playlist folder (as created/synced by CameraSync3D, or a plain folder of JPEGs) and
     * switches to it, honoring isAutomated/defaultDurationS for auto-advance.
     */
    fun onPlaylistFolderChosen(folder: File) {
        val storage = DesktopPlaylistStorage(folder.parentFile ?: folder)
        val playlist = Playlist.loadPlaylist(storage, folder.name)
        val files = playlist.photos.map { playlistItemFile(it.imageUriString) }
        onPlaylistChosen(files, playlist.isAutomated, playlist.defaultDurationS * 1000)
    }

    fun closeImageView() {
        screen = Screen.Welcome
    }

    fun showNextImage() {
        if (currentImageIndex < imageFiles.lastIndex) {
            currentImageIndex++
            alignedPreview = null
        }
    }

    fun showPreviousImage() {
        if (currentImageIndex > 0) {
            currentImageIndex--
            alignedPreview = null
        }
    }

    /** Advances looping back to the first photo - used by the playlist auto-advance timer only. */
    fun advanceSlideshow() {
        if (imageFiles.isEmpty()) return
        currentImageIndex = (currentImageIndex + 1) % imageFiles.size
        alignedPreview = null
    }
}
