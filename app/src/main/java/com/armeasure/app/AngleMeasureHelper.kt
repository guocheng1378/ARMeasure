package com.armeasure.app

import kotlin.math.sqrt
import kotlin.math.acos

/**
 * Angle measurement: measure angle at a vertex formed by 3 tapped points.
 * Point 1 = vertex, Points 2 and 3 = arms of the angle.
 */
object AngleMeasureHelper {

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
}
