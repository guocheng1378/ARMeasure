package com.armeasure.app

import kotlin.math.sqrt
import kotlin.math.acos

/**
 * Angle measurement: measure angle at a vertex formed by 3 tapped points.
 * Point 1 = vertex, Points 2 and 3 = arms of the angle.
 */
object AngleMeasureHelper {

    private fun fastTan(theta: Double): Float {
        return if (Math.abs(theta) < 0.26) theta.toFloat() else Math.tan(theta).toFloat()
    }

    /**
     * Compute angle at vertex (in degrees) from 3 points in 3D.
     */
    fun computeAngle3D(
        vertex: Triple<Float, Float, Float>,
        arm1: Triple<Float, Float, Float>,
        arm2: Triple<Float, Float, Float>
    ): Float {
        val v1x = arm1.first - vertex.first
        val v1y = arm1.second - vertex.second
        val v1z = arm1.third - vertex.third
        val v2x = arm2.first - vertex.first
        val v2y = arm2.second - vertex.second
        val v2z = arm2.third - vertex.third

        val dot = v1x * v2x + v1y * v2y + v1z * v2z
        val len1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val len2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)

        if (len1 < 0.01f || len2 < 0.01f) return 0f
        val cosAngle = (dot / (len1 * len2)).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle)).toFloat()
    }

    /**
     * Compute angle from screen coordinates + depths.
     */
    fun computeAngleFromScreen(
        vx: Float, vy: Float, vd: Float,
        ax1: Float, ay1: Float, d1: Float,
        ax2: Float, ay2: Float, d2: Float,
        viewW: Float, viewH: Float,
        intrinsics: FloatArray?, imgW: Int, imgH: Int,
        hfovDeg: Double, vfovDeg: Double
    ): Float {
        // Minimum arm length check: angle is meaningless with very short arms
        val armLen1 = MeasurementEngine.distance3D(
            MeasurementEngine.screenTo3DFOV(vx, vy, vd, viewW, viewH, hfovDeg, vfovDeg),
            MeasurementEngine.screenTo3DFOV(ax1, ay1, d1, viewW, viewH, hfovDeg, vfovDeg)
        )
        val armLen2 = MeasurementEngine.distance3D(
            MeasurementEngine.screenTo3DFOV(vx, vy, vd, viewW, viewH, hfovDeg, vfovDeg),
            MeasurementEngine.screenTo3DFOV(ax2, ay2, d2, viewW, viewH, hfovDeg, vfovDeg)
        )
        if (armLen1 < AppConstants.ANGLE_MIN_ARM_CM || armLen2 < AppConstants.ANGLE_MIN_ARM_CM) return 0f

        val vertex: Triple<Float, Float, Float>
        val arm1: Triple<Float, Float, Float>
        val arm2: Triple<Float, Float, Float>

        if (intrinsics != null && intrinsics.size >= 4) {
            val fx = intrinsics[0]; val fy = intrinsics[1]
            val cx = intrinsics[2]; val cy = intrinsics[3]
            vertex = Triple(vd * (vx / viewW * imgW - cx) / fx, vd * (vy / viewH * imgH - cy) / fy, vd)
            arm1 = Triple(d1 * (ax1 / viewW * imgW - cx) / fx, d1 * (ay1 / viewH * imgH - cy) / fy, d1)
            arm2 = Triple(d2 * (ax2 / viewW * imgW - cx) / fx, d2 * (ay2 / viewH * imgH - cy) / fy, d2)
        } else {
            val clamp = AppConstants.FOV_TAN_CLAMP.toDouble()
            val hfov = Math.toRadians(hfovDeg); val vfov = Math.toRadians(vfovDeg)
            fun to3D(sx: Float, sy: Float, d: Float): Triple<Float, Float, Float> {
                val nx = ((sx / viewW - 0.5f) * 2f).toDouble().coerceIn(-clamp, clamp)
                val ny = ((0.5f - sy / viewH) * 2f).toDouble().coerceIn(-clamp, clamp)
                return Triple(d * fastTan(nx * hfov / 2), d * fastTan(ny * vfov / 2), d)
            }
            vertex = to3D(vx, vy, vd)
            arm1 = to3D(ax1, ay1, d1)
            arm2 = to3D(ax2, ay2, d2)
        }

        return computeAngle3D(vertex, arm1, arm2)
    }
}
