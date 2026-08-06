import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * A user-drawn "stereo issue" rectangle, in fractions (0..1) of a single eye-half's width/height -
 * same resolution-independent representation as [CropRectFraction], so it survives round-tripping
 * through screen pixels (see ImageScreen.kt's onSpotIssueDragEnd) and applies identically to both
 * the left and right half when saved.
 */
data class IssueRectFraction(val x: Float, val y: Float, val width: Float, val height: Float)

// Hot pink, BGRA order (the Mat SpotStereoIssues.saveSpotIssues draws into is BGRA - see
// AutoAlign.fileToMat) - matches ImageScreen.kt's SpotIssuePinkColor Compose preview.
private val PinkBgra = Scalar(180.0, 20.0, 255.0, 255.0)

// Same sign convention and value as ImageScreen.kt's SpotIssueOverlayShiftPercent (the live
// drawing/review preview overlay) - baking the same -1% shift into the saved pixels keeps the
// saved photo's rectangles matching what was already previewed, and reads as floating just in
// front of the screen rather than pinned flat to the photo's own depth at that spot (see
// CLAUDE.md's "Stereo (SBS) display and depth-shift overlays" note).
private const val ShiftPercent = -0.01f

/**
 * Counterpart to [Crop]/[ManualAlign]/[AutoAlign] for the "Spot stereo issues" tool (see
 * Exif3dInfoPanel's "Spot stereo issues" button): unlike those tools, this doesn't warp or cut the
 * photo, it just permanently draws pink rectangles onto both eye-halves - offset apart by
 * [ShiftPercent] of half the photo's width, same left/right-duplication-plus-shift technique every
 * other overlay in this codebase uses, just baked into pixels instead of drawn live - so the
 * rectangles read as floating in front of the photo rather than pinned flat to whatever depth the
 * photo itself has at that spot. Then writes the result via the same [AutoAlign.writeAlignedResult]
 * step every save path uses, under this tool's own "_stereo_issuesN" suffix (rather than the
 * generic "_editedN" the other tools use) so these files are distinguishable at a glance.
 */
object SpotStereoIssues {
    fun saveSpotIssues(file: File, rects: List<IssueRectFraction>): File? {
        if (rects.isEmpty()) return null
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        // Scales with resolution so the rectangle reads clearly on both a modest camera JPEG and a
        // very high-resolution one, rather than a fixed pixel width that'd be imperceptible on the
        // latter or too thick on the former.
        val thickness = (h * 0.005).toInt().coerceAtLeast(4)
        // Same -shift/2 (left) / +shift/2 (right) split every other overlay in this codebase uses
        // (see e.g. ImageScreen.kt's SpotIssueRectsOverlay) - shift is negative here, so the left
        // copy (-shift/2, positive) moves right and the right copy (+shift/2, negative) moves left,
        // i.e. the two copies converge, which reads as popping toward the viewer.
        val shiftPx = halfW * ShiftPercent
        val leftOffsetPx = (-shiftPx / 2).toInt()
        val rightOffsetPx = (shiftPx / 2).toInt()

        for (rect in rects) {
            val rx = (rect.x * halfW).toInt().coerceIn(0, halfW - 1)
            val ry = (rect.y * h).toInt().coerceIn(0, h - 1)
            val rw = (rect.width * halfW).toInt().coerceIn(1, halfW - rx)
            val rh = (rect.height * h).toInt().coerceIn(1, h - ry)
            val leftX = (rx + leftOffsetPx).coerceIn(0, halfW - rw)
            val rightX = (halfW + rx + rightOffsetPx).coerceIn(halfW, w - rw)

            Imgproc.rectangle(fullMat, Point(leftX.toDouble(), ry.toDouble()), Point((leftX + rw).toDouble(), (ry + rh).toDouble()), PinkBgra, thickness)
            Imgproc.rectangle(fullMat, Point(rightX.toDouble(), ry.toDouble()), Point((rightX + rw).toDouble(), (ry + rh).toDouble()), PinkBgra, thickness)
        }

        val destFile = AutoAlign.writeAlignedResult(file, fullMat, suffix = "stereo_issues")
        fullMat.release()
        return destFile
    }
}
