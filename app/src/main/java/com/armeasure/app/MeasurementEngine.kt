package com.armeasure.app

import kotlin.math.sqrt

/**
 * Pure computation: 3D distance and polygon area.
 * Zero Android dependencies — fully testable in JVM unit tests.
 */
object MeasurementEngine {

    /**
     * Rotate a 3D point (x, y, z) by pitch and roll angles (radians).
     * Used to compensate camera orientation change between two measurement points.
     * Pitch rotates around X axis (vertical tilt), roll rotates around Y axis (horizontal tilt).
     * @return rotated (x, y, z)
     */
    fun rotatePoint(x: Float, y: Float, z: Float, pitchRad: Float, rollRad: Float): Triple<Float, Float, Float> {
        val cp = Math.cos(pitchRad.toDouble()).toFloat()
        val sp = Math.sin(pitchRad.toDouble()).toFloat()
        val cr = Math.cos(rollRad.toDouble()).toFloat()
        val sr = Math.sin(rollRad.toDouble()).toFloat()
        // Rotate by pitch around X: y' = y*cp - z*sp, z' = y*sp + z*cp
        val y1 = y * cp - z * sp
        val z1 = y * sp + z * cp
        // Rotate by roll around Y: x' = x*cr + z1*sr, z'' = -x*sr + z1*cr
        val x1 = x * cr + z1 * sr
        val z2 = -x * sr + z1 * cr
        return Triple(x1, y1, z2)
    }

    /**
     * Compute 3D distance between two points with IMU rotation compensation.
     * Un-rotates point2 back to point1's camera frame before computing distance.
     * @param deltaPitchRad pitch change from point1 to point2 (radians)
     * @param deltaRollRad roll change from point1 to point2 (radians)
     * @return distance in cm
     */
    fun compute3DDistanceIntrinsicRotated(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int,
        deltaPitchRad: Float, deltaRollRad: Float
    ): Float {
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]
        val ix1 = x1 / viewW * imgW; val iy1 = y1 / viewH * imgH
        val ix2 = x2 / viewW * imgW; val iy2 = y2 / viewH * imgH
        val wx1 = d1 * (ix1 - cx) / fx
        val wy1 = d1 * (iy1 - cy) / fy
        val wz1 = d1
        val wx2 = d2 * (ix2 - cx) / fx
        val wy2 = d2 * (iy2 - cy) / fy
        val wz2 = d2
        // Un-rotate point2 to point1's frame
        val (rx2, ry2, rz2) = rotatePoint(wx2, wy2, wz2, -deltaPitchRad, -deltaRollRad)
        return sqrt((wx1 - rx2) * (wx1 - rx2) + (wy1 - ry2) * (wy1 - ry2) + (wz1 - rz2) * (wz1 - rz2))
    }

    fun compute3DDistanceRotated(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double,
        deltaPitchRad: Float, deltaRollRad: Float
    ): Float {
        val clampFactor = AppConstants.FOV_TAN_CLAMP.toDouble()
        val hfov = Math.toRadians(hfovDeg)
        val vfov = Math.toRadians(vfovDeg)
        val nx1 = ((x1 / viewW - 0.5f) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val ny1 = ((0.5f - y1 / viewH) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val nx2 = ((x2 / viewW - 0.5f) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val ny2 = ((0.5f - y2 / viewH) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val wx1 = d1 * Math.tan(nx1 * hfov / 2).toFloat()
        val wy1 = d1 * Math.tan(ny1 * vfov / 2).toFloat()
        val wz1 = d1
        val wx2 = d2 * Math.tan(nx2 * hfov / 2).toFloat()
        val wy2 = d2 * Math.tan(ny2 * vfov / 2).toFloat()
        val wz2 = d2
        val (rx2, ry2, rz2) = rotatePoint(wx2, wy2, wz2, -deltaPitchRad, -deltaRollRad)
        return sqrt((wx1 - rx2) * (wx1 - rx2) + (wy1 - ry2) * (wy1 - ry2) + (wz1 - rz2) * (wz1 - rz2))
    }

    /**
     * Compute 3D distance using camera intrinsic calibration (fx, fy, cx, cy).
     * More accurate than FOV approximation, especially away from image center.
     * @return distance in cm
     */
    fun compute3DDistanceIntrinsic(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int
    ): Float {
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]

        // Map screen coords to image coords (intrinsics are in image pixel space)
        val ix1 = x1 / viewW * imgW; val iy1 = y1 / viewH * imgH
        val ix2 = x2 / viewW * imgW; val iy2 = y2 / viewH * imgH

        // Pixel → 3D: (d * (px - cx) / fx, d * (py - cy) / fy, d)
        val wx1 = d1 * (ix1 - cx) / fx
        val wy1 = d1 * (iy1 - cy) / fy
        val wx2 = d2 * (ix2 - cx) / fx
        val wy2 = d2 * (iy2 - cy) / fy

        val dx = wx1 - wx2
        val dy = wy1 - wy2
        val dz = d1 - d2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * #10: Compute 3D distance between two screen points given their depths.
     * FOV input is clamped to avoid tan() blowup at screen edges.
     * @return distance in cm
     */
    fun compute3DDistance(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double
    ): Float {
        val nx1 = (x1 / viewW - 0.5f) * 2f
        val ny1 = (0.5f - y1 / viewH) * 2f
        val nx2 = (x2 / viewW - 0.5f) * 2f
        val ny2 = (0.5f - y2 / viewH) * 2f

        val hfov = Math.toRadians(hfovDeg)
        val vfov = Math.toRadians(vfovDeg)

        // #10: Clamp angle to avoid tan() blowup at screen edges
        val clampFactor = AppConstants.FOV_TAN_CLAMP.toDouble()
        val px1 = d1 * Math.tan(nx1.toDouble().coerceIn(-clampFactor, clampFactor) * hfov / 2).toFloat()
        val py1 = d1 * Math.tan(ny1.toDouble().coerceIn(-clampFactor, clampFactor) * vfov / 2).toFloat()
        val px2 = d2 * Math.tan(nx2.toDouble().coerceIn(-clampFactor, clampFactor) * hfov / 2).toFloat()
        val py2 = d2 * Math.tan(ny2.toDouble().coerceIn(-clampFactor, clampFactor) * vfov / 2).toFloat()

        val dx = px1 - px2
        val dy = py1 - py2
        val dz = d1 - d2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Properly propagate depth uncertainty through the 3D distance formula.
     *
     * For intrinsic projection:
     *   dist² = d1²[((ix1-cx)/fx)² + ((iy1-cy)/fy)² + 1] + d2²[((ix2-cx)/fx)² + ((iy2-cy)/fy)² + 1]
     *           - 2*d1*d2*[((ix1-cx)(ix2-cx)/fx²) + ((iy1-cy)(iy2-cy)/fy²) + 1]
     *
     * ∂dist/∂d1 = (d1*A - d2*B) / dist
     * ∂dist/∂d2 = (d2*C - d1*B) / dist
     *
     * where A = 1 + ((ix1-cx)/fx)² + ((iy1-cy)/fy)²
     *       C = 1 + ((ix2-cx)/fx)² + ((iy2-cy)/fy)²
     *       B = 1 + ((ix1-cx)(ix2-cx)/fx²) + ((iy1-cy)(iy2-cy)/fy²)
     *
     * σ_dist² = (∂dist/∂d1)² σ₁² + (∂dist/∂d2)² σ₂²
     *
     * @return uncertainty in cm
     */
    fun compute3DDistanceUncertaintyIntrinsic(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        sigma1: Float, sigma2: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int
    ): Float {
        if (sigma1 <= 0 && sigma2 <= 0) return 0f
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]
        val ix1 = x1 / viewW * imgW; val iy1 = y1 / viewH * imgH
        val ix2 = x2 / viewW * imgW; val iy2 = y2 / viewH * imgH
        val u1 = (ix1 - cx) / fx; val v1 = (iy1 - cy) / fy
        val u2 = (ix2 - cx) / fx; val v2 = (iy2 - cy) / fy
        val A = 1f + u1 * u1 + v1 * v1
        val C = 1f + u2 * u2 + v2 * v2
        val B = 1f + u1 * u2 + v1 * v2
        val dist = compute3DDistanceIntrinsic(x1, y1, x2, y2, d1, d2, viewW, viewH, intrinsics, imgW, imgH)
        if (dist < 0.1f) return (sigma1 + sigma2)
        val dd1 = (d1 * A - d2 * B) / dist
        val dd2 = (d2 * C - d1 * B) / dist
        return sqrt((dd1 * sigma1) * (dd1 * sigma1) + (dd2 * sigma2) * (dd2 * sigma2)).toFloat()
    }

    /**
     * Uncertainty propagation for FOV-based 3D distance.
     * σ_dist² = (∂dist/∂d1)² σ₁² + (∂dist/∂d2)² σ₂²
     */
    fun compute3DDistanceUncertaintyFOV(
        x1: Float, y1: Float, x2: Float, y2: Float,
        d1: Float, d2: Float,
        sigma1: Float, sigma2: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double
    ): Float {
        if (sigma1 <= 0 && sigma2 <= 0) return 0f
        val hfov = Math.toRadians(hfovDeg)
        val vfov = Math.toRadians(vfovDeg)
        val clampFactor = AppConstants.FOV_TAN_CLAMP.toDouble()
        val nx1 = ((x1 / viewW - 0.5f) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val ny1 = ((0.5f - y1 / viewH) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val nx2 = ((x2 / viewW - 0.5f) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val ny2 = ((0.5f - y2 / viewH) * 2f).toDouble().coerceIn(-clampFactor, clampFactor)
        val ta1 = Math.tan(nx1 * hfov / 2); val tb1 = Math.tan(ny1 * vfov / 2)
        val ta2 = Math.tan(nx2 * hfov / 2); val tb2 = Math.tan(ny2 * vfov / 2)
        val A = 1.0 + ta1 * ta1 + tb1 * tb1
        val C = 1.0 + ta2 * ta2 + tb2 * tb2
        val B = 1.0 + ta1 * ta2 + tb1 * tb2
        val dist = compute3DDistance(x1, y1, x2, y2, d1, d2, viewW, viewH, hfovDeg, vfovDeg)
        if (dist < 0.1f) return (sigma1 + sigma2)
        val dd1 = (d1 * A - d2 * B) / dist
        val dd2 = (d2 * C - d1 * B) / dist
        return sqrt((dd1 * sigma1) * (dd1 * sigma1) + (dd2 * sigma2) * (dd2 * sigma2)).toFloat()
    }

    /**
     * Edge penalty factor for depth at screen edge pixels.
     * Pixels near screen edges have larger angular error, amplifying depth noise in 3D.
     * Returns a multiplier > 1 for edge pixels, 1.0 for center.
     */
    fun edgeUncertaintyMultiplier(sx: Float, sy: Float, viewW: Float, viewH: Float): Float {
        if (viewW <= 0 || viewH <= 0) return 1f
        val nx = (sx / viewW - 0.5f) * 2f  // [-1, 1]
        val ny = (sy / viewH - 0.5f) * 2f
        val distFromCenter = sqrt((nx * nx + ny * ny).toDouble()).toFloat()
        // 0 at center, ~1.4 at corner → multiplier ranges from 1.0 to ~2.0
        return (1f + distFromCenter * 0.7f).coerceAtMost(3f)
    }

    /**
     * Compute true 3D surface area of a polygon in cm².
     * Each point has (px, py, depth) where px/py are horizontal/vertical offsets from camera center,
     * and depth is the measured distance along the view direction.
     * Uses Newell's method (cross product of edge vectors) to get the actual surface area,
     * not just the 2D projected area.
     */
    fun computePolygonArea3D(pts3d: List<Triple<Float, Float, Float>>): Float {
        if (pts3d.size < 3) return 0f
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        val n = pts3d.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            nx += (pts3d[i].second * pts3d[j].third - pts3d[j].second * pts3d[i].third)
            ny += (pts3d[i].third * pts3d[j].first - pts3d[j].third * pts3d[i].first)
            nz += (pts3d[i].first * pts3d[j].second - pts3d[j].first * pts3d[i].second)
        }
        return (sqrt(nx * nx + ny * ny + nz * nz) / 2.0).toFloat()
    }

    /**
     * Legacy 2D polygon area (shoelace formula on projected coordinates).
     */
    fun computePolygonArea(pts3d: List<Pair<Float, Float>>): Float {
        if (pts3d.size < 3) return 0f
        var nz = 0.0
        val n = pts3d.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            nz += pts3d[i].first * pts3d[j].second - pts3d[j].first * pts3d[i].second
        }
        return (Math.abs(nz) / 2.0).toFloat()
    }

    /**
     * Compute polygon area from screen coordinates with flat-plane approximation.
     * @param xs array of x coordinates (pixels)
     * @param ys array of y coordinates (pixels)
     * @return area in cm²
     */
    fun computeFlatArea(
        xs: FloatArray, ys: FloatArray,
        avgDistCm: Float,
        viewW: Float,
        hfovDeg: Double,
        viewH: Float = 0f,
        vfovDeg: Double = 0.0
    ): Float {
        val n = xs.size
        if (n < 3 || n != ys.size || avgDistCm <= 0 || viewW <= 0) return 0f
        val hfov = Math.toRadians(hfovDeg)
        val viewWidthCm = (2 * avgDistCm * Math.tan(hfov / 2)).toFloat()
        val scaleX = viewWidthCm / viewW
        // Use vfov if provided, otherwise fall back to aspect-ratio estimate
        val scaleY = if (viewH > 0 && vfovDeg > 0) {
            val vfov = Math.toRadians(vfovDeg)
            (2 * avgDistCm * Math.tan(vfov / 2)).toFloat() / viewH
        } else scaleX

        var areaPixels = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            areaPixels += xs[i] * ys[j]
            areaPixels -= xs[j] * ys[i]
        }
        return (Math.abs(areaPixels / 2.0) * scaleX * scaleY).toFloat()
    }

    /**
     * #9: Robust multi-sample depth: collect samples, reject outliers via MAD, return median.
     * Uses a minimum MAD threshold to avoid rejecting samples when variance is near-zero
     * (floating-point precision issue).
     * @param samples raw depth samples in cm (must be > 0)
     * @param madThreshold number of MADs to consider as outlier (default 2.0)
     * @return robust median depth in cm, or null if too few valid samples
     */
    fun robustDepth(samples: List<Float>, madThreshold: Double = 2.0): Float? {
        val valid = samples.filter { it > 0 }.sorted()
        if (valid.size < 2) return null

        val median = valid.median()
        // MAD: median of absolute deviations from median
        val deviations = valid.map { Math.abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        // #9: Use absolute minimum threshold to avoid float-precision false rejections
        if (mad < AppConstants.ROBUST_MAD_MIN_THRESHOLD) return median

        // Keep samples within madThreshold * 1.4826 * MAD (≈ standard deviation equivalent)
        val sigma = mad * 1.4826
        val threshold = (madThreshold * sigma).toFloat()
        val filtered = valid.filter { Math.abs(it - median) <= threshold }
        return if (filtered.size >= 2) filtered.median() else median
    }

    /**
     * Fuse two depth estimates with inverse-variance weighting.
     * @param d1 first depth estimate (cm)
     * @param var1 estimated variance of d1 (cm²), lower = more trusted
     * @param d2 second depth estimate (cm)
     * @param var2 estimated variance of d2 (cm²)
     * @return fused depth (cm)
     */
    fun fuseDepth(d1: Float, var1: Float, d2: Float, var2: Float): Float {
        if (var1 <= 0) return d2
        if (var2 <= 0) return d1
        val w1 = 1f / var1
        val w2 = 1f / var2
        return (w1 * d1 + w2 * d2) / (w1 + w2)
    }

    // ── 3D Coordinate Conversions (Apple-like world-anchored points) ──

    /**
     * Convert screen position + depth to 3D world coordinates (camera intrinsic model).
     * @return (wx, wy, wz) in cm, in camera coordinate frame at the time of measurement
     */
    fun screenTo3DIntrinsic(
        sx: Float, sy: Float, depthCm: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int
    ): Triple<Float, Float, Float> {
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]
        val ix = sx / viewW * imgW; val iy = sy / viewH * imgH
        return Triple(depthCm * (ix - cx) / fx, depthCm * (iy - cy) / fy, depthCm)
    }

    /**
     * Convert screen position + depth to 3D world coordinates (FOV model).
     */
    fun screenTo3DFOV(
        sx: Float, sy: Float, depthCm: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double
    ): Triple<Float, Float, Float> {
        val clamp = AppConstants.FOV_TAN_CLAMP.toDouble()
        val nx = ((sx / viewW - 0.5f) * 2f).toDouble().coerceIn(-clamp, clamp)
        val ny = ((0.5f - sy / viewH) * 2f).toDouble().coerceIn(-clamp, clamp)
        val hfov = Math.toRadians(hfovDeg); val vfov = Math.toRadians(vfovDeg)
        return Triple(
            depthCm * Math.tan(nx * hfov / 2).toFloat(),
            depthCm * Math.tan(ny * vfov / 2).toFloat(),
            depthCm
        )
    }

    /**
     * Project 3D world coordinates back to screen position (intrinsic model).
     * @return (screenX, screenY) in view coordinates
     */
    fun worldToScreenIntrinsic(
        wx: Float, wy: Float, wz: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int
    ): Pair<Float, Float> {
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]
        val px = wx * fx / wz + cx
        val py = wy * fy / wz + cy
        return Pair(px / imgW * viewW, py / imgH * viewH)
    }

    /**
     * Project 3D world coordinates back to screen position (FOV model).
     */
    fun worldToScreenFOV(
        wx: Float, wy: Float, wz: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double
    ): Pair<Float, Float> {
        val hfov = Math.toRadians(hfovDeg); val vfov = Math.toRadians(vfovDeg)
        val nx = if (wz > 0) Math.atan((wx / wz).toDouble()) / (hfov / 2) else 0.0
        val ny = if (wz > 0) Math.atan((wy / wz).toDouble()) / (vfov / 2) else 0.0
        val sx = (nx / 2.0 + 0.5) * viewW
        val sy = (0.5 - ny / 2.0) * viewH
        return Pair(sx.toFloat(), sy.toFloat())
    }

    /** Euclidean distance between two 3D points. */
    fun distance3D(p1: Triple<Float, Float, Float>, p2: Triple<Float, Float, Float>): Float {
        val dx = p1.first - p2.first; val dy = p1.second - p2.second; val dz = p1.third - p2.third
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Median of a sorted Float list. Assumes list is non-empty and sorted. */
    private fun List<Float>.median(): Float {
        val n = size
        return if (n % 2 == 1) this[n / 2] else (this[n / 2 - 1] + this[n / 2]) / 2f
    }

    /** Median or null if empty. */
    private fun List<Float>.medianOrNull(): Float? {
        if (isEmpty()) return null
        return sorted().median()
    }

    /**
     * Fit a plane to 3D points using RANSAC, then project onto best-fit plane for area.
     * Returns area in cm² on the fitted plane, or falls back to direct Newell's method.
     * @param pts3d list of (x, y, z) in cm
     * @param ransacIterations number of RANSAC trials (default 50)
     * @param inlierThreshold max distance from plane to count as inlier (cm, default 5)
     */
    fun computePolygonArea3DRansac(
        pts3d: List<Triple<Float, Float, Float>>,
        ransacIterations: Int = 50,
        inlierThreshold: Float = 5f
    ): Float {
        if (pts3d.size < 3) return 0f
        if (pts3d.size == 3) return computePolygonArea3D(pts3d)

        // RANSAC: find best plane from 3-point samples
        var bestPlane = DoubleArray(4)  // (nx, ny, nz, d) where nx*x + ny*y + nz*z + d = 0
        var bestInlierCount = 0
        val n = pts3d.size

        for (iter in 0 until ransacIterations) {
            // Pick 3 random distinct points
            val i1 = (Math.random() * n).toInt().coerceIn(0, n - 1)
            var i2 = (Math.random() * n).toInt().coerceIn(0, n - 1)
            while (i2 == i1) i2 = (Math.random() * n).toInt().coerceIn(0, n - 1)
            var i3 = (Math.random() * n).toInt().coerceIn(0, n - 1)
            while (i3 == i1 || i3 == i2) i3 = (Math.random() * n).toInt().coerceIn(0, n - 1)

            val (ax, ay, az) = pts3d[i1]
            val (bx, by, bz) = pts3d[i2]
            val (cx, cy, cz) = pts3d[i3]

            // Plane normal via cross product
            val u1 = bx - ax; val u2 = by - ay; val u3 = bz - az
            val v1 = cx - ax; val v2 = cy - ay; val v3 = cz - az
            val nx = u2 * v3 - u3 * v2
            val ny = u3 * v1 - u1 * v3
            val nz = u1 * v2 - u2 * v1
            val normLen = sqrt((nx * nx + ny * ny + nz * nz).toDouble())
            if (normLen < 1e-6) continue
            val pnx = nx / normLen; val pny = ny / normLen; val pnz = nz / normLen
            val pd = -(pnx * ax + pny * ay + pnz * az)

            // Count inliers
            var inliers = 0
            for (k in 0 until n) {
                val (px, py, pz) = pts3d[k]
                val dist = Math.abs(pnx * px + pny * py + pnz * pz + pd)
                if (dist < inlierThreshold) inliers++
            }
            if (inliers > bestInlierCount) {
                bestInlierCount = inliers
                bestPlane = doubleArrayOf(pnx, pny, pnz, pd)
            }
        }

        if (bestInlierCount < 3) return computePolygonArea3D(pts3d)

        // Project all points onto the best-fit plane, then compute 2D area
        val pnx = bestPlane[0]; val pny = bestPlane[1]; val pnz = bestPlane[2]; val pd = bestPlane[3]

        // Build a 2D coordinate system on the plane
        // u = arbitrary perpendicular to normal, v = normal × u
        val ux: Double; val uy: Double; val uz: Double
        if (Math.abs(pnx) < 0.9) {
            ux = 1.0; uy = 0.0; uz = 0.0
        } else {
            ux = 0.0; uy = 1.0; uz = 0.0
        }
        // u = u - (u·n)n (Gram-Schmidt)
        val dotUN = ux * pnx + uy * pny + uz * pnz
        var u1x = ux - dotUN * pnx; var u1y = uy - dotUN * pny; var u1z = uz - dotUN * pnz
        val uLen = sqrt(u1x * u1x + u1y * u1y + u1z * u1z)
        u1x /= uLen; u1y /= uLen; u1z /= uLen
        // v = n × u
        val v1x = pny * u1z - pnz * u1y
        val v1y = pnz * u1x - pnx * u1z
        val v1z = pnx * u1y - pny * u1x

        // Project to 2D
        val projected = ArrayList<Pair<Float, Float>>(n)
        // Use centroid as origin
        val cx = pts3d.sumOf { it.first.toDouble() } / n
        val cy = pts3d.sumOf { it.second.toDouble() } / n
        val cz = pts3d.sumOf { it.third.toDouble() } / n
        for (k in 0 until n) {
            val dx = pts3d[k].first - cx; val dy = pts3d[k].second - cy; val dz = pts3d[k].third - cz
            val pu = (dx * u1x + dy * u1y + dz * u1z).toFloat()
            val pv = (dx * v1x + dy * v1y + dz * v1z).toFloat()
            projected.add(Pair(pu, pv))
        }

        return computePolygonArea(projected)
    }
}
