package com.armeasure.app

import kotlin.math.sqrt

/**
 * Height measurement: measures vertical distance between two points.
 * Uses IMU pitch to determine "vertical" axis.
 */
object HeightMeasureHelper {

    /**
     * Calculate true vertical height between two 3D points.
     * Uses camera intrinsic projection for accuracy.
     */
    fun computeHeight(
        x1: Float, y1: Float, d1: Float,
        x2: Float, y2: Float, d2: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray, imgW: Int, imgH: Int
    ): Float {
        val fx = intrinsics[0]; val fy = intrinsics[1]
        val cx = intrinsics[2]; val cy = intrinsics[3]
        val ix1 = x1 / viewW * imgW; val iy1 = y1 / viewH * imgH
        val ix2 = x2 / viewW * imgW; val iy2 = y2 / viewH * imgH
        val wy1 = d1 * (iy1 - cy) / fy
        val wy2 = d2 * (iy2 - cy) / fy
        return Math.abs(wy2 - wy1)
    }

    fun computeHeightFOV(
        x1: Float, y1: Float, d1: Float,
        x2: Float, y2: Float, d2: Float,
        viewW: Float, viewH: Float,
        vfovDeg: Double
    ): Float {
        val vfov = Math.toRadians(vfovDeg)
        val clamp = AppConstants.FOV_TAN_CLAMP.toDouble()
        val ny1 = ((0.5f - y1 / viewH) * 2f).toDouble().coerceIn(-clamp, clamp)
        val ny2 = ((0.5f - y2 / viewH) * 2f).toDouble().coerceIn(-clamp, clamp)
        val py1 = d1 * Math.tan(ny1 * vfov / 2).toFloat()
        val py2 = d2 * Math.tan(ny2 * vfov / 2).toFloat()
        return Math.abs(py2 - py1)
    }

    /**
     * Height with IMU tilt compensation.
     * When device is tilted, project measurements onto gravity axis.
     */
    fun computeHeightWithTilt(
        x1: Float, y1: Float, d1: Float,
        x2: Float, y2: Float, d2: Float,
        viewW: Float, viewH: Float,
        hfovDeg: Double, vfovDeg: Double,
        pitchRad: Float
    ): Float {
        val rawHeight = computeHeightFOV(x1, y1, d1, x2, y2, d2, viewW, viewH, vfovDeg)
        // Project onto vertical (gravity) axis
        val cosPitch = Math.cos(pitchRad.toDouble()).toFloat()
        return if (cosPitch > 0.1f) rawHeight / cosPitch else rawHeight
    }
}
