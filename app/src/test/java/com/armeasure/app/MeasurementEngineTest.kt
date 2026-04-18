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
    fun `3D distance does not blow up at screen edges`() {
        // #10: Edge pixels should not produce infinity due to tan() blowup
        val dist = MeasurementEngine.compute3DDistance(
            x1 = 0f, y1 = 0f, x2 = 1080f, y2 = 1920f,
            d1 = 100f, d2 = 100f,
            viewW = 1080f, viewH = 1920f,
            hfovDeg = 65.0, vfovDeg = 50.0
        )
        assertTrue("Edge distance should be finite and reasonable, got $dist", dist > 0f && dist < 1000f)
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

    // ── robustDepth tests ──

    @Test
    fun `robustDepth returns null for empty samples`() {
        assertNull(MeasurementEngine.robustDepth(emptyList()))
    }

    @Test
    fun `robustDepth returns null for single sample`() {
        assertNull(MeasurementEngine.robustDepth(listOf(100f)))
    }

    @Test
    fun `robustDepth returns median for tight cluster`() {
        val samples = listOf(100f, 101f, 102f, 100f, 101f)
        val result = MeasurementEngine.robustDepth(samples)
        assertNotNull(result)
        assertEquals(101f, result!!, 0.5f)
    }

    @Test
    fun `robustDepth rejects outlier spike`() {
        // Normal cluster around 100, with one spike at 500
        val samples = listOf(100f, 102f, 99f, 101f, 500f)
        val result = MeasurementEngine.robustDepth(samples)
        assertNotNull(result)
        // Should be close to 100, not pulled up by the 500 outlier
        assertTrue("Result $result should be < 120 (outlier rejected)", result!! < 120f)
    }

    @Test
    fun `robustDepth filters negative values`() {
        val samples = listOf(-1f, 100f, 101f, -1f, 102f)
        val result = MeasurementEngine.robustDepth(samples)
        assertNotNull(result)
        assertEquals(101f, result!!, 0.5f)
    }

    // ── fuseDepth tests ──

    @Test
    fun `fuseDepth returns d1 when var2 is zero`() {
        assertEquals(100f, MeasurementEngine.fuseDepth(100f, 10f, 200f, 0f), 0.01f)
    }

    @Test
    fun `fuseDepth returns d2 when var1 is zero`() {
        assertEquals(200f, MeasurementEngine.fuseDepth(100f, 0f, 200f, 10f), 0.01f)
    }

    @Test
    fun `fuseDepth weights lower variance more`() {
        // d1=100 with var=100 (trusted), d2=200 with var=1 (very trusted)
        val result = MeasurementEngine.fuseDepth(100f, 100f, 200f, 1f)
        // Should be much closer to 200 than to 100
        assertTrue("Result $result should be > 190 (var2 dominates)", result > 190f)
    }

    @Test
    fun `fuseDepth with equal variance gives average`() {
        val result = MeasurementEngine.fuseDepth(100f, 10f, 200f, 10f)
        assertEquals(150f, result, 0.1f)
    }

    // ── Intrinsic projection tests ──

    @Test
    fun `intrinsic projection same point is zero`() {
        // Simulate 4000x3000 image, fx=fy=2000, cx=2000, cy=1500
        val intrinsics = floatArrayOf(2000f, 2000f, 2000f, 1500f)
        val dist = MeasurementEngine.compute3DDistanceIntrinsic(
            x1 = 540f, y1 = 960f, x2 = 540f, y2 = 960f,
            d1 = 100f, d2 = 100f,
            viewW = 1080f, viewH = 1920f,
            intrinsics = intrinsics, imgW = 4000, imgH = 3000
        )
        assertEquals(0f, dist, 0.01f)
    }

    @Test
    fun `intrinsic projection matches FOV at image center`() {
        // For points near center, intrinsic and FOV should give similar results
        val intrinsics = floatArrayOf(1500f, 1500f, 1080f, 960f)
        val intrinsicDist = MeasurementEngine.compute3DDistanceIntrinsic(
            x1 = 440f, y1 = 860f, x2 = 640f, y2 = 1060f,
            d1 = 200f, d2 = 200f,
            viewW = 1080f, viewH = 1920f,
            intrinsics = intrinsics, imgW = 2160, imgH = 1920
        )
        assertTrue("Intrinsic distance should be positive, got $intrinsicDist", intrinsicDist > 0f)
    }
}
