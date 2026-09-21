import androidx.compose.ui.graphics.ImageBitmap
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * A point the user clicked on one eye-half of the photo, as a fraction (0..1) of that eye-half's
 * own width/height - resolution independent, same idiom as [CropRectFraction]/[IssueRectFraction].
 */
data class PointFraction(val x: Float, val y: Float)

/**
 * Click-matching-points counterpart to [ManualAlign]'s keyboard nudge (see Exif3dInfoPanel's
 * "Manual Align" submenu): with exactly one point per eye, the correction the user wants is a pure
 * translation - the same shape [ManualAlign] already computes from arrow-key nudges - so
 * [saveClickAlign] just derives a fractional (dx, dy) from the two points and delegates to
 * [ManualAlign.saveManualAlign] rather than duplicating its crop/write pipeline. The delta is
 * `left - right` because [ManualAlign.computeAlignedCrops] aligns a point at left-local x=L with
 * one at right-local x=R when dxFraction = L - R (verified against its Rect offset math - a
 * positive cdx crops L pixels off the left half's origin while leaving the right half's origin
 * alone, so the two points land on the same output x); same for y/dyFraction.
 */
object ClickAlign {
    fun saveClickAlign(file: File, left: PointFraction, right: PointFraction): File? =
        ManualAlign.saveManualAlign(file, left.x - right.x, left.y - right.y)

    /**
     * The Shift-held live preview (see CLAUDE.md's Stereo/SBS note): in half-width display mode,
     * [Split] shows what the two eyes will actually look like once cropped to their common overlap
     * - the real per-eye split, in place of the flat left/right comparison layout the click tool
     * normally shows while picking points. In full-width mode, [Anaglyph] combines the two aligned
     * crops into one red/cyan image instead - full-width mode already shows the two eyes properly
     * split (that's the tool's normal layout there), so the anaglyph is the more useful alignment
     * check.
     */
    sealed class Preview {
        data class Split(val left: ImageBitmap, val right: ImageBitmap) : Preview()
        data class Anaglyph(val bitmap: ImageBitmap) : Preview()
    }

    /** Returns null if the two points coincide (nothing to align) or the file can't be decoded. */
    fun computePreview(file: File, left: PointFraction, right: PointFraction, halveLeftRightImages: Boolean): Preview? {
        val (leftCrop, rightCrop) = ManualAlign.computeAlignedCrops(file, left.x - right.x, left.y - right.y) ?: return null
        return if (halveLeftRightImages) {
            val preview = Preview.Split(AutoAlign.matToImageBitmap(leftCrop), AutoAlign.matToImageBitmap(rightCrop))
            leftCrop.release()
            rightCrop.release()
            preview
        } else {
            val leftBgr = Mat()
            Imgproc.cvtColor(leftCrop, leftBgr, Imgproc.COLOR_BGRA2BGR)
            leftCrop.release()
            val rightBgr = Mat()
            Imgproc.cvtColor(rightCrop, rightBgr, Imgproc.COLOR_BGRA2BGR)
            rightCrop.release()
            val anaglyph = AutoAlign.mergeAnaglyphBgr(leftBgr, rightBgr)
            leftBgr.release()
            rightBgr.release()
            val preview = Preview.Anaglyph(AutoAlign.matToImageBitmap(anaglyph))
            anaglyph.release()
            preview
        }
    }
}
