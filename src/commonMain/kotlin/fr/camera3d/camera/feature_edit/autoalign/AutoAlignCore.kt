package fr.camera3d.camera.feature_edit.autoalign

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

import org.opencv.core.*
import org.opencv.core.MatOfKeyPoint
import org.opencv.imgproc.Imgproc
import org.opencv.features.ORB
import org.opencv.features.SIFT
import org.opencv.features.DescriptorMatcher
import org.opencv.geometry.Geometry
import java.util.ArrayList
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mat-in/Mat-out core of the stereo auto-align algorithms (autoAlign2d / autoAlignHomography).
 * Platform-specific Bitmap/BufferedImage <-> Mat conversion and OpenCV native loading stay in
 * each platform's own wrapper; this file only ever touches org.opencv.* types.
 */

/**
 * Detects keypoints and descriptors for a color (RGBA) left/right Mat pair.
 * Uses ORB (binary descriptors) by default, or OpenCV 5's SIFT (float descriptors) when
 * [useNewOpenCv5] is true — SIFT is more distinctive on low-texture/repetitive stereo scenes,
 * at the cost of extra CPU time.
 */
fun detectFeaturesFromColorMats(leftMat: Mat, rightMat: Mat, useNewOpenCv5: Boolean = false): Pair<Pair<MatOfKeyPoint, Mat>, Pair<MatOfKeyPoint, Mat>> {
    val grayLeft = Mat()
    val grayRight = Mat()
    Imgproc.cvtColor(leftMat, grayLeft, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.cvtColor(rightMat, grayRight, Imgproc.COLOR_RGBA2GRAY)

    val detector = if (useNewOpenCv5) SIFT.create(5000) else ORB.create(5000)

    val kp1 = MatOfKeyPoint()
    val des1 = Mat()
    detector.detectAndCompute(grayLeft, Mat(), kp1, des1)

    val kp2 = MatOfKeyPoint()
    val des2 = Mat()
    detector.detectAndCompute(grayRight, Mat(), kp2, des2)

    grayLeft.release()
    grayRight.release()

    return Pair(Pair(kp1, des1), Pair(kp2, des2))
}

/**
 * Estimates the alignment matrix between image 1 and 2
 * using keypoint descriptors using planar operations: rotation, scale & translation.
 * @param kp1: Keypoints for image 1
 * @param des1: Descriptors for image 1
 * @param kp2: Keypoints for image 2
 * @param des2: Descriptors for image 2
 * @param useNewOpenCv5: when true, matches SIFT's float descriptors (L2 norm) and fits with
 * OpenCV 5's USAC_MAGSAC estimator instead of plain RANSAC (more accurate, more outlier-robust)
 * @return M: 3x3 transformation matrix
 */
fun estimateMatrix(kp1: MatOfKeyPoint, des1: Mat, kp2: MatOfKeyPoint, des2: Mat, useNewOpenCv5: Boolean = false): Mat? {
    // 1. Match descriptors: Brute Force L2 for SIFT's float descriptors, Hamming for ORB's binary ones
    val matcher = DescriptorMatcher.create(if (useNewOpenCv5) DescriptorMatcher.FLANNBASED else DescriptorMatcher.BRUTEFORCE_HAMMING)
    val matches = MatOfDMatch()
    matcher.match(des1, des2, matches)

    // 2. Sort matches by distance and take top 200
    val matchesList = matches.toList()
    val sortedMatches = matchesList.sortedBy { it.distance }
    val goodMatches = if (sortedMatches.size > 200) sortedMatches.take(200) else sortedMatches

    if (goodMatches.size < 4) {
        return null
    }

    // 3. Extract matched points
    val listOfKp1 = kp1.toList()
    val listOfKp2 = kp2.toList()

    val pts1List = ArrayList<Point>()
    val pts2List = ArrayList<Point>()

    for (match in goodMatches) {
        pts1List.add(listOfKp1[match.queryIdx].pt)
        pts2List.add(listOfKp2[match.trainIdx].pt)
    }

    val pts1 = MatOfPoint2f()
    val pts2 = MatOfPoint2f()
    pts1.fromList(pts1List)
    pts2.fromList(pts2List)

    // 4. Estimate affine transform (4 degrees of freedom: translation, rotation, scale)
    val inliers = Mat()
    val m = Geometry.estimateAffinePartial2D(pts2, pts1, inliers, if (useNewOpenCv5) Geometry.USAC_MAGSAC else Geometry.RANSAC)

    // Clean up
    pts1.release()
    pts2.release()
    inliers.release()

    // If estimation failed, M will be empty
    if (m.empty()) {
        m.release()
        return null
    }

    return m
}

fun extractParametersFromMatrix(transformationMatrix: Mat): Pair<Float, Float> {
    val m00 = transformationMatrix.get(0, 0)[0]
    val m10 = transformationMatrix.get(1, 0)[0]

    // Calculate scale: sqrt(a^2 + b^2)
    val scale = sqrt(m00.pow(2.0) + m10.pow(2.0)).toFloat()

    // Calculate rotation in degrees: atan2(sin_component, cos_component)
    val rotation = Math.toDegrees(atan2(m10, m00)).toFloat()
    return Pair(scale, rotation)
}

/**
 * Computes the largest axis-aligned rectangle guaranteed to lie inside the quadrilateral
 * formed by projecting the right image corners through H into the left image space,
 * clipped to the left image bounds.
 */
fun computeValidCropRect(H: Mat, w: Double, h: Double): Rect? {
    val srcCorners = MatOfPoint2f(
        Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)
    )
    val dstCorners = MatOfPoint2f()
    Core.perspectiveTransform(srcCorners, dstCorners, H)
    srcCorners.release()

    val pts = dstCorners.toList()
    dstCorners.release()
    if (pts.size < 4) return null

    // Split corners into left/right halves (by x) then top/bottom (by y)
    val sorted = pts.sortedBy { it.x }
    val leftPts = sorted.take(2).sortedBy { it.y }   // [TL, BL]
    val rightPts = sorted.drop(2).sortedBy { it.y }  // [TR, BR]

    // Inner bounding box: guaranteed to be inside the (possibly tilted) quadrilateral
    val xLeft  = maxOf(leftPts[0].x,  leftPts[1].x ).coerceAtLeast(0.0)
    val xRight = minOf(rightPts[0].x, rightPts[1].x).coerceAtMost(w)
    val yTop   = maxOf(leftPts[0].y,  rightPts[0].y).coerceAtLeast(0.0)
    val yBottom= minOf(leftPts[1].y,  rightPts[1].y).coerceAtMost(h)

    if (xLeft >= xRight || yTop >= yBottom) return null

    return Rect(
        xLeft.toInt(), yTop.toInt(),
        (xRight - xLeft).toInt(), (yBottom - yTop).toInt()
    )
}
