import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manual pixel-nudge counterpart to [AutoAlign]: instead of feature-matching, the user directly
 * drags the right eye-half by ([dxFraction], [dyFraction]) - fractions of the eye-half's
 * width/height, resolution independent, same idiom as [Crop]'s CropRectFraction (see Main.kt's
 * arrow-key handling and PhotoToolsState.manualAlignOffsetX/Y). Rather than leaving a blank gap
 * where the shifted image no longer overlaps the left half, both halves are cropped to their
 * common overlapping region - the same "crop both to the valid region" approach
 * [AutoAlign.alignAndCrop] already uses, just with a plain translation instead of a scale+rotation
 * estimate.
 */
object ManualAlign {
    /** Returns null (without touching disk) if there's nothing to save, i.e. [dxFraction]/[dyFraction] are both 0. */
    fun saveManualAlign(file: File, dxFraction: Float, dyFraction: Float): File? {
        if (dxFraction == 0f && dyFraction == 0f) return null
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val dx = (dxFraction * halfW).roundToInt()
        val dy = (dyFraction * h).roundToInt()
        val leftMat = Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        val rightMat = Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        fullMat.release()

        // Clamped so cropW/cropH can never drop below 1, regardless of how far dx/dy drifted.
        val cdx = dx.coerceIn(-(halfW - 1), halfW - 1)
        val cdy = dy.coerceIn(-(h - 1), h - 1)
        val cropW = halfW - abs(cdx)
        val cropH = h - abs(cdy)
        val leftCrop = Mat(leftMat, Rect(maxOf(cdx, 0), maxOf(cdy, 0), cropW, cropH)).clone()
        val rightCrop = Mat(rightMat, Rect(maxOf(-cdx, 0), maxOf(-cdy, 0), cropW, cropH)).clone()
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
