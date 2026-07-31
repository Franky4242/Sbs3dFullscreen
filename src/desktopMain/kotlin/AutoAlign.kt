import androidx.compose.ui.graphics.ImageBitmap
import fr.camera3d.camera.feature_edit.autoalign.detectFeaturesFromColorMats
import fr.camera3d.camera.feature_edit.autoalign.estimateMatrix
import fr.camera3d.camera.feature_edit.autoalign.extractParametersFromMatrix
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desktop wrapper around the Mat-in/Mat-out core synced from CameraSync3D
 * (feature_edit/autoalign/AutoAlignCore.kt): loads OpenCV's native library, does File <-> Mat
 * I/O (Android's org.opencv.android.Utils.bitmapToMat/matToBitmap equivalent), and re-derives the
 * warp+crop math from Android's autoAlign2d in Mat/Rect terms (that function itself wasn't synced -
 * only the feature-detection/matrix-estimation core was - since it's saturated with Bitmap calls).
 *
 * NOT independently compile-verified: this file needs the vendored OpenCV 5 jar (libs/opencv/,
 * see its README) to compile at all, and no Maven-hosted OpenCV distribution exposes OpenCV 5's
 * org.opencv.features/org.opencv.geometry packages to test against ahead of time. Build once
 * OpenCV is vendored and fix forward if the real API differs from what's assumed here.
 */
object AutoAlign {
    @Volatile
    private var openCvLoaded = false

    private fun ensureOpenCvLoaded() {
        if (!openCvLoaded) {
            synchronized(this) {
                if (!openCvLoaded) {
                    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                    openCvLoaded = true
                }
            }
        }
    }

    /** Decodes a file straight to a 4-channel Mat (BGRA) regardless of source format/channel count. */
    private fun fileToMat(file: File): Mat {
        ensureOpenCvLoaded()
        val decoded = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        val withAlpha = Mat()
        Imgproc.cvtColor(decoded, withAlpha, Imgproc.COLOR_BGR2BGRA)
        decoded.release()
        return withAlpha
    }

    private fun matToImageBitmap(mat: Mat): ImageBitmap {
        val buf = MatOfByte()
        Imgcodecs.imencode(".png", mat, buf)
        val bytes = buf.toArray()
        buf.release()
        return bytes.decodeToImageBitmap()
    }

    /**
     * Splits a side-by-side stereo JPEG into left/right Mats, auto-aligns them (ORB + RANSAC
     * affine, mirroring Android's autoalign2d.kt), and returns the aligned pair recombined
     * side-by-side - or null if not enough features were found to align on.
     */
    fun autoAlignSideBySide(file: File): ImageBitmap? {
        val fullMat = fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val leftMat = Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        val rightMat = Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        fullMat.release()

        try {
            val (leftFeatures, rightFeatures) = detectFeaturesFromColorMats(leftMat, rightMat)
            val (kp1, des1) = leftFeatures
            val (kp2, des2) = rightFeatures
            if (des1.empty() || des2.empty()) {
                kp1.release(); des1.release(); kp2.release(); des2.release()
                return null
            }

            val m = estimateMatrix(kp1, des1, kp2, des2)
            kp1.release(); des1.release(); kp2.release(); des2.release()
            if (m == null) return null

            val (scale, rotation) = extractParametersFromMatrix(m)
            m.release()

            val (alignedLeft, alignedRight) = alignAndCrop(leftMat, rightMat, scale, rotation)
            val combined = Mat()
            Core.hconcat(listOf(alignedLeft, alignedRight), combined)
            alignedLeft.release()
            alignedRight.release()

            val result = matToImageBitmap(combined)
            combined.release()
            return result
        } finally {
            leftMat.release()
            rightMat.release()
        }
    }

    /**
     * Aligns left/right by reducing the bigger one (zoom + rotation) and cropping the other -
     * re-derivation of Android's autoAlign2d (feature_edit/autoalign/autoalign2d.kt) in Mat/Rect
     * terms instead of Bitmap, since that function itself is Bitmap-saturated and wasn't synced.
     */
    private fun alignAndCrop(leftMat: Mat, rightMat: Mat, scale: Float, rotation: Float): Pair<Mat, Mat> {
        val applyToLeft = scale >= 1f
        val reduceFactor = if (applyToLeft) 1.0 / scale.toDouble() else scale.toDouble()
        val angle = if (applyToLeft) -rotation.toDouble() else rotation.toDouble()

        val srcMat = if (applyToLeft) leftMat else rightMat
        val w = srcMat.width().toDouble()
        val h = srcMat.height().toDouble()

        val rotMat = Geometry.getRotationMatrix2D(Point(w / 2, h / 2), angle, reduceFactor)
        val dstMat = Mat(srcMat.size(), srcMat.type())
        Imgproc.warpAffine(srcMat, dstMat, rotMat, srcMat.size())
        rotMat.release()

        // Largest axis-aligned inscribed rectangle in the rotated content - see autoalign2d.kt's
        // autoAlign2d for the derivation of this formula.
        val thetaRad = abs(Math.toRadians(angle))
        val cosT = cos(thetaRad)
        val sinT = sin(thetaRad)
        val cos2T = cos(2 * thetaRad)

        val cropW: Int
        val cropH: Int
        if (cos2T > 0.001 &&
            reduceFactor * (w * cosT - h * sinT) / cos2T > 0 &&
            reduceFactor * (h * cosT - w * sinT) / cos2T > 0
        ) {
            cropW = (reduceFactor * (w * cosT - h * sinT) / cos2T).toInt()
            cropH = (reduceFactor * (h * cosT - w * sinT) / cos2T).toInt()
        } else {
            val r = reduceFactor * minOf(w, h) / sqrt(2.0)
            cropW = r.toInt()
            cropH = r.toInt()
        }

        val cropX = ((w - cropW) / 2).toInt().coerceAtLeast(0)
        val cropY = ((h - cropH) / 2).toInt().coerceAtLeast(0)
        val finalCropW = minOf(cropW, srcMat.width() - cropX).coerceAtLeast(1)
        val finalCropH = minOf(cropH, srcMat.height() - cropY).coerceAtLeast(1)

        val croppedTransformed = Mat(dstMat, Rect(cropX, cropY, finalCropW, finalCropH)).clone()
        dstMat.release()

        val otherMat = if (applyToLeft) rightMat else leftMat
        val otherCropX = ((otherMat.width() - finalCropW) / 2).coerceAtLeast(0)
        val otherCropY = ((otherMat.height() - finalCropH) / 2).coerceAtLeast(0)
        val croppedOther = Mat(otherMat, Rect(otherCropX, otherCropY, finalCropW, finalCropH)).clone()

        return if (applyToLeft) Pair(croppedTransformed, croppedOther) else Pair(croppedOther, croppedTransformed)
    }
}
