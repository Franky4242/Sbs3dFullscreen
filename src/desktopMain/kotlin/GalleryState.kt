import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

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

/**
 * Holds GalleryScreen's state (the scanned directory, its collapsible groups, and which photo to
 * scroll back into view on return) plus the logic to mutate it - pulled out of AppViewModel for
 * the same reason PhotoToolsState was: a self-contained cluster of fields/methods used from
 * exactly one place. Owned by AppViewModel as `gallery`. AppViewModel itself still owns the actual
 * Screen transitions (Screen.Gallery/Screen.ImageView) and the cross-cutting nav state (imageFiles/
 * currentImageIndex) touched when opening a photo from the gallery, since those aren't
 * gallery-specific - see AppViewModel.openGallery/openGalleryImage/closeImageView.
 */
class GalleryState {
    var root by mutableStateOf<File?>(null)
        private set
    var groups by mutableStateOf<List<GalleryGroup>>(emptyList())
        private set
    var expandedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    // True while ImageView was entered from GalleryScreen - see AppViewModel.closeImageView,
    // which routes back here instead of Welcome/PlaylistList.
    var enteredFromGallery by mutableStateOf(false)
        private set

    // Set on returning from ImageView to Gallery, to whichever photo was actually on screen -
    // which may differ from the one originally tapped if Left/Right was used inside ImageView.
    // GalleryScreen consumes this (consumeScrollTarget) to scroll that photo back into view
    // instead of leaving the list wherever it happened to be scrolled to.
    var scrollTarget by mutableStateOf<File?>(null)
        private set

    /** Recursively scans [folder] for images - see AppViewModel.openGallery. */
    fun open(folder: File) {
        root = folder
        val scanned = scanGalleryDirectory(folder)
        groups = scanned
        expandedGroups = scanned.map { it.relativePath }.toSet()
    }

    fun close() {
        root = null
        groups = emptyList()
        expandedGroups = emptySet()
    }

    fun toggleGroup(relativePath: String) {
        expandedGroups = if (relativePath in expandedGroups) expandedGroups - relativePath else expandedGroups + relativePath
    }

    /** Clears [scrollTarget] once GalleryScreen has scrolled to it, so a later return to the same
     *  photo (null -> file) still re-triggers the scroll instead of being a no-op change. */
    fun consumeScrollTarget() {
        scrollTarget = null
    }

    /** Marks ImageView as entered from Gallery - see AppViewModel.openGalleryImage. */
    fun markEntered() {
        enteredFromGallery = true
    }

    /** Marks the return trip to Gallery, recording [target] to scroll back to - see AppViewModel.closeImageView. */
    fun markReturnedTo(target: File?) {
        enteredFromGallery = false
        scrollTarget = target
    }
}
