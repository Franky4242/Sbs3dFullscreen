import java.awt.Dimension
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Multi-select file picker for the given extensions.
 *
 * Uses Swing's JFileChooser rather than java.awt.FileDialog: FileDialog opens Windows' legacy
 * comdlg32 common dialog, which isn't itself per-monitor DPI aware. Since this app's process is
 * (the JDK 9+ default), Windows bitmap-stretches that dialog to the monitor's scale, producing
 * blurry/mis-sized toolbar icons and oversized thumbnail padding - a jpackage/JVM limitation with
 * no supported override hook. JFileChooser is rendered entirely by Swing, so it always scales
 * correctly, at the cost of generic file-type icons instead of native photo thumbnails - the
 * [ImagePreviewAccessory] below adds a manual preview panel to compensate.
 */
fun chooseFiles(window: java.awt.Window, title: String, extensions: Array<String>): List<File> {
    val chooser = JFileChooser(lastFilesDirectory.getOrPictures())
    chooser.dialogTitle = title
    chooser.isMultiSelectionEnabled = true
    chooser.fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { "*.$it" }, *extensions)
    chooser.accessory = ImagePreviewAccessory(chooser)
    val approved = chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION
    lastFilesDirectory.save(chooser.currentDirectory)
    return if (approved) {
        chooser.selectedFiles.toList()
    } else {
        emptyList()
    }
}

/**
 * Single-folder picker (e.g. GalleryScreen's "Open 3D image directory"), remembering the last
 * folder chosen independently of [chooseFiles]'s own memory.
 */
fun chooseDirectory(window: java.awt.Window, title: String): File? {
    val chooser = JFileChooser(lastGalleryDirectory.getOrPictures())
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    val approved = chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION
    lastGalleryDirectory.save(chooser.selectedFile ?: chooser.currentDirectory)
    return if (approved) chooser.selectedFile else null
}

private val lastFilesDirectory = LastDirectoryPreference("lastDirectory")
private val lastGalleryDirectory = LastDirectoryPreference("lastGalleryDirectory")

/**
 * Remembers the last folder browsed under a given [key], defaulting to the user's Pictures folder
 * on first launch. Falls back gracefully (Pictures, then home) if a remembered folder no longer
 * exists - e.g. it was since deleted or was on a removable/network drive that isn't mounted.
 */
private class LastDirectoryPreference(private val key: String) {
    private val prefs = Preferences.userNodeForPackage(LastDirectoryPreference::class.java)

    fun getOrPictures(): File {
        val saved = prefs.get(key, null)?.let(::File)
        val pictures = File(System.getProperty("user.home"), "Pictures")
        val home = File(System.getProperty("user.home"))
        return sequenceOf(saved, pictures, home).filterNotNull().firstOrNull { it.isDirectory } ?: home
    }

    fun save(directory: File?) {
        if (directory != null && directory.isDirectory) {
            prefs.put(key, directory.absolutePath)
        }
    }
}

/** Side panel showing a scaled preview of the currently-selected image; blank for non-image files (e.g. videos). */
private class ImagePreviewAccessory(chooser: JFileChooser) : JPanel() {
    private val label = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }
    private var loadSeq = 0

    init {
        preferredSize = Dimension(180, 180)
        add(label)
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY) {
            updatePreview(chooser.selectedFile)
        }
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILES_CHANGED_PROPERTY) {
            updatePreview(chooser.selectedFiles?.firstOrNull())
        }
    }

    private fun updatePreview(file: File?) {
        val seq = ++loadSeq
        label.icon = null
        label.text = null
        if (file == null || !file.isFile) return
        // Decoding full-res JPEGs off the EDT keeps the dialog responsive while browsing.
        Thread {
            val image = runCatching { ImageIO.read(file) }.getOrNull()
            SwingUtilities.invokeLater {
                if (seq != loadSeq) return@invokeLater
                if (image != null) {
                    label.icon = ImageIcon(image.scaledToFit(160, 160))
                } else {
                    label.text = "No preview"
                }
            }
        }.start()
    }
}

private fun BufferedImage.scaledToFit(maxWidth: Int, maxHeight: Int): Image {
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height, 1.0)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return getScaledInstance(w, h, Image.SCALE_SMOOTH)
}
