import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.io.File

/**
 * A user-drawn crop rectangle, in fractions (0..1) of a single eye-half's width/height - resolution
 * independent, so it survives round-tripping through screen pixels (see ImageScreen.kt's
 * onCropDragEnd) and applies identically to both the left and right half when saved.
 */
data class CropRectFraction(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Counterpart to [ManualAlign]/[AutoAlign] for the crop tool (see Exif3dInfoPanel's Crop button):
 * crops both eye-halves to the *same* relative [CropRectFraction] - unlike ManualAlign's per-half
 * offset, a plain crop must stay symmetric between the two eyes or the stereo pair would no longer
 * agree - then hconcats them back together, same "write result" step every align pipeline uses.
 */
object Crop {
    fun saveCrop(file: File, rect: CropRectFraction): File? {
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val leftMat = Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        val rightMat = Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        fullMat.release()

        val rx = (rect.x * halfW).toInt().coerceIn(0, halfW - 1)
        val ry = (rect.y * h).toInt().coerceIn(0, h - 1)
        val rw = (rect.width * halfW).toInt().coerceIn(1, halfW - rx)
        val rh = (rect.height * h).toInt().coerceIn(1, h - ry)

        val leftCrop = Mat(leftMat, Rect(rx, ry, rw, rh)).clone()
        val rightCrop = Mat(rightMat, Rect(rx, ry, rw, rh)).clone()
        leftMat.release()
        rightMat.release()

        val combined = Mat()
        Core.hconcat(listOf(leftCrop, rightCrop), combined)
        leftCrop.release()
        rightCrop.release()

        val destFile = AutoAlign.writeAlignedResult(file, combined)
        combined.release()
        return destFile
    }
}
