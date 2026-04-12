package com.armeasure.app

import kotlin.math.sqrt

/**
 * Pure computation: 3D distance and polygon area.
 * Zero Android dependencies — fully testable in JVM unit tests.
 */
object MeasurementEngine {

    /**
     * Compute 3D distance between two screen points given their depths.
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

        val px1 = d1 * Math.tan(nx1 * hfov / 2).toFloat()
        val py1 = d1 * Math.tan(ny1 * vfov / 2).toFloat()
        val px2 = d2 * Math.tan(nx2 * hfov / 2).toFloat()
        val py2 = d2 * Math.tan(ny2 * vfov / 2).toFloat()

        val dx = px1 - px2
        val dy = py1 - py2
        val dz = d1 - d2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Compute polygon area in cm² using per-point 3D coordinates.
     */
    fun computePolygonArea(pts3d: List<Pair<Float, Float>>): Float {
        if (pts3d.size < 3) return 0f
        var area = 0.0
        val n = pts3d.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts3d[i].first * pts3d[j].second
            area -= pts3d[j].first * pts3d[i].second
        }
        return Math.abs(area / 2.0).toFloat()
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
        hfovDeg: Double
    ): Float {
        val n = xs.size
        if (n < 3 || n != ys.size || avgDistCm <= 0 || viewW <= 0) return 0f
        val hfov = Math.toRadians(hfovDeg)
        val viewWidthM = (2 * (avgDistCm / 100f) * Math.tan(hfov / 2)).toFloat()
        val scale = viewWidthM / viewW

        var areaPixels = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            areaPixels += xs[i] * ys[j]
            areaPixels -= xs[j] * ys[i]
        }
        return (Math.abs(areaPixels / 2.0) * scale * scale * 10000.0).toFloat()
    }
}
