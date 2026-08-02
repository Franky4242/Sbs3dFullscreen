import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Multi-select file picker for the given extensions.
 *
 * Uses Swing's JFileChooser rather than java.awt.FileDialog: FileDialog opens Windows' legacy
 * comdlg32 common dialog, which isn't itself per-monitor DPI aware. Since this app's process is
 * (the JDK 9+ default), Windows bitmap-stretches that dialog to the monitor's scale, producing
 * blurry/mis-sized toolbar icons and oversized thumbnail padding - a jpackage/JVM limitation with
 * no supported override hook. JFileChooser is rendered entirely by Swing, so it always scales
 * correctly, at the cost of generic file-type icons instead of native photo thumbnails.
 */
fun chooseFiles(window: java.awt.Window, title: String, extensions: Array<String>): List<File> {
    val chooser = JFileChooser()
    chooser.dialogTitle = title
    chooser.isMultiSelectionEnabled = true
    chooser.fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { "*.$it" }, *extensions)
    return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFiles.toList()
    } else {
        emptyList()
    }
}
