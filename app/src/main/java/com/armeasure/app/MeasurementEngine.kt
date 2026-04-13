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
}
