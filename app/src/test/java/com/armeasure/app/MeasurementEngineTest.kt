package com.armeasure.app

import org.junit.Assert.*
import org.junit.Test

class MeasurementEngineTest {

    @Test
    fun `3D distance between same point is zero`() {
        val dist = MeasurementEngine.compute3DDistance(
            x1 = 500f, y1 = 500f, x2 = 500f, y2 = 500f,
            d1 = 100f, d2 = 100f,
            viewW = 1080f, viewH = 1920f,
            hfovDeg = 65.0, vfovDeg = 50.0
        )
        assertEquals(0f, dist, 0.01f)
    }

    @Test
    fun `3D distance for points at same depth on horizontal axis`() {
        val cx = 540f
        val cy = 960f
        val dist = MeasurementEngine.compute3DDistance(
            x1 = cx - 100f, y1 = cy, x2 = cx + 100f, y2 = cy,
            d1 = 200f, d2 = 200f,
            viewW = 1080f, viewH = 1920f,
            hfovDeg = 65.0, vfovDeg = 50.0
        )
        assertTrue("Distance should be positive, got $dist", dist > 0f)
    }

    @Test
    fun `3D distance increases with depth difference`() {
        val x = 540f
        val y = 960f
        val distSmall = MeasurementEngine.compute3DDistance(
            x1 = x, y1 = y, x2 = x, y2 = y,
            d1 = 100f, d2 = 105f,
            viewW = 1080f, viewH = 1920f,
            hfovDeg = 65.0, vfovDeg = 50.0
        )
        val distLarge = MeasurementEngine.compute3DDistance(
            x1 = x, y1 = y, x2 = x, y2 = y,
            d1 = 100f, d2 = 200f,
            viewW = 1080f, viewH = 1920f,
            hfovDeg = 65.0, vfovDeg = 50.0
        )
        assertTrue("Larger depth diff should give larger distance", distLarge > distSmall)
    }

    @Test
    fun `polygon area of triangle`() {
        val pts = listOf(Pair(0f, 0f), Pair(10f, 0f), Pair(0f, 10f))
        val area = MeasurementEngine.computePolygonArea(pts)
        assertEquals(50f, area, 0.1f)
    }

    @Test
    fun `polygon area of rectangle`() {
        val pts = listOf(Pair(0f, 0f), Pair(10f, 0f), Pair(10f, 5f), Pair(0f, 5f))
        val area = MeasurementEngine.computePolygonArea(pts)
        assertEquals(50f, area, 0.1f)
    }

    @Test
    fun `polygon area with less than 3 points returns zero`() {
        assertEquals(0f, MeasurementEngine.computePolygonArea(listOf(Pair(0f, 0f))), 0.01f)
        assertEquals(0f, MeasurementEngine.computePolygonArea(
            listOf(Pair(0f, 0f), Pair(1f, 1f))
        ), 0.01f)
    }

    @Test
    fun `flat area returns positive for valid polygon`() {
        val xs = floatArrayOf(100f, 500f, 500f, 100f)
        val ys = floatArrayOf(100f, 100f, 500f, 500f)
        val area = MeasurementEngine.computeFlatArea(xs, ys, 200f, 1080f, 65.0)
        assertTrue("Flat area should be positive, got $area", area > 0f)
    }

    @Test
    fun `flat area returns zero for empty input`() {
        assertEquals(0f, MeasurementEngine.computeFlatArea(
            floatArrayOf(), floatArrayOf(), 100f, 1080f, 65.0
        ), 0.01f)
    }
}
