package fr.camera3d.camera.shared

import kotlin.math.sqrt

/**
 * Portable (no android.* imports, see the sync script in sbs3Dfullscreen) heuristic for whether an
 * image is likely a side-by-side stereo photo: the two eye-halves are independent shots, so the
 * column pair straddling the vertical midline usually shows a color discontinuity, whereas an
 * ordinary single photo is usually visually continuous there. [argbAt] returns a packed ARGB int
 * for the pixel at (x, y), same encoding as android.graphics.Bitmap.getPixel, so callers on either
 * platform can adapt their own pixel source (Bitmap.getPixel / Compose ImageBitmap's PixelMap) to it.
 */
object SideBySideLikeliness {
    private const val Threshold = 50.0

    fun computeLikeliness(width: Int, height: Int, argbAt: (x: Int, y: Int) -> Int): Double {
        if (width < 2 || height == 0) return 0.0
        var result = 0.0
        for (y in 0 until height) {
            result += colorDistance(argbAt(width / 2 - 1, y), argbAt(width / 2, y))
        }
        return result / height
    }

    /**
     * Measures of computeLikeliness show:
     * - Side by side: 75, 88, 91, 204, 206, 288, 391, 404, 407
     * - NOT side by side: 5, 5, 7, 7, 8, 9, 10, 10, 10, 10, 11, 11, 11, 12, 12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 18, 20, 36
     * so a threshold at 50 is consistent.
     */
    fun isLikelySideBySide(width: Int, height: Int, argbAt: (x: Int, y: Int) -> Int): Boolean {
        if (width < 2 || height == 0) return true
        return computeLikeliness(width, height, argbAt) > Threshold
    }

    /**
     * Computes color distance between 2 pixels; not a euclidean distance because eyes are more
     * sensitive to red gaps.
     */
    private fun colorDistance(argb1: Int, argb2: Int): Double {
        val red1 = (argb1 shr 16) and 0xff
        val green1 = (argb1 shr 8) and 0xff
        val blue1 = argb1 and 0xff

        val red2 = (argb2 shr 16) and 0xff
        val green2 = (argb2 shr 8) and 0xff
        val blue2 = argb2 and 0xff

        val rMean = (red1 + red2) / 2.0
        val r = red1 - red2
        val g = green1 - green2
        val b = blue1 - blue2

        return sqrt((2 + rMean / 256) * r * r + 4 * g * g + (2 + (255 - rMean) / 256) * b * b)
    }
}
