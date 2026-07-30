import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class Screen { Welcome, ImageView }

/**
 * Holds the app's screen/navigation state and the logic to mutate it, decoupled from the
 * `Window`/`WindowState` concerns (undecorated, placement) that stay in Main.kt since those
 * are tied directly to the Window composable's lifecycle.
 */
class AppViewModel(initialFile: File?) {
    var screen by mutableStateOf(if (initialFile != null) Screen.ImageView else Screen.Welcome)
        private set
    var imageFiles by mutableStateOf(initialFile?.let { listOf(it) } ?: emptyList())
        private set
    var currentImageIndex by mutableStateOf(0)
        private set
    var language by mutableStateOf<String?>(null)
        private set

    val currentImage: File? get() = imageFiles.getOrNull(currentImageIndex)

    fun onLanguageChosen(languageTag: String?) {
        language = languageTag
    }

    fun onFilesChosen(files: List<File>) {
        imageFiles = files
        currentImageIndex = 0
        screen = Screen.ImageView
    }

    fun closeImageView() {
        screen = Screen.Welcome
    }

    fun showNextImage() {
        if (currentImageIndex < imageFiles.lastIndex) currentImageIndex++
    }

    fun showPreviousImage() {
        if (currentImageIndex > 0) currentImageIndex--
    }
}
