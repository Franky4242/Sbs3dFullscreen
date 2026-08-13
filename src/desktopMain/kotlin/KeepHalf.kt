import org.opencv.core.Mat
import org.opencv.core.Rect
import java.io.File

/**
 * Counterpart to [Crop] for InfoPanel's delete tool "keep left/right as 2D" choice (see
 * AppViewModel.performDeleteCurrentImage): crops [file]'s side-by-side stereo photo to a single
 * eye-half and saves it as a standalone 2D JPEG, reusing [AutoAlign.writeAlignedResult] for the
 * same file-naming/EXIF-copy step every other save path uses - just without [Crop]'s final hconcat
 * back into a stereo pair, since the result here is meant to stay a single flat photo.
 */
object KeepHalf {
    fun saveHalf(file: File, keepLeft: Boolean): File? {
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val half = if (keepLeft) {
            Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        } else {
            Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        }
        fullMat.release()

        val suffix = if (keepLeft) "2d_left" else "2d_right"
        val destFile = AutoAlign.writeAlignedResult(file, half, suffix)
        half.release()
        return destFile
    }
}
