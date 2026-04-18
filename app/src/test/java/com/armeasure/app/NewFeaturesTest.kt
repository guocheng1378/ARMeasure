package com.armeasure.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for new features: #6 RANSAC plane fitting, #3 depth consistency,
 * IMU velocity integration, and adaptive constants.
 */
class MeasurementEngineRansacTest {

    @Test
    fun `RANSAC area matches exact area for coplanar points`() {
        // 10cm × 10cm square in the XY plane at z=200cm
        val pts = listOf(
            Triple(0f, 0f, 200f),
            Triple(10f, 0f, 200f),
            Triple(10f, 10f, 200f),
            Triple(0f, 10f, 200f)
        )
        val area = MeasurementEngine.computePolygonArea3DRansac(pts, ransacIterations = 100)
        // Should be approximately 100 cm²
        assertTrue("RANSAC area should be close to 100, got $area", area in 90f..110f)
    }

    @Test
    fun `RANSAC area for triangle`() {
        // Simple right triangle: 3-4-5, area = 6
        val pts = listOf(
            Triple(0f, 0f, 100f),
            Triple(3f, 0f, 100f),
            Triple(0f, 4f, 100f)
        )
        val area = MeasurementEngine.computePolygonArea3DRansac(pts, ransacIterations = 50)
        assertTrue("Triangle area should be close to 6, got $area", area in 5f..7f)
    }

    @Test
    fun `RANSAC falls back for non-coplanar points`() {
        // Points not on a single plane — should still return a reasonable value
        val pts = listOf(
            Triple(0f, 0f, 100f),
            Triple(10f, 0f, 150f),
            Triple(10f, 10f, 200f),
            Triple(0f, 10f, 120f)
        )
        val area = MeasurementEngine.computePolygonArea3DRansac(pts, ransacIterations = 100)
        assertTrue("Area should be positive for valid polygon, got $area", area > 0f)
    }

    @Test
    fun `RANSAC with less than 3 points returns zero`() {
        val pts = listOf(Triple(0f, 0f, 100f), Triple(1f, 1f, 100f))
        val area = MeasurementEngine.computePolygonArea3DRansac(pts)
        assertEquals(0f, area, 0.01f)
    }
}

class AppConstantsTest {

    @Test
    fun `depth consistency threshold is reasonable`() {
        assertTrue(AppConstants.DEPTH_CONSISTENCY_MAX_RATIO >= 1.0f)
        assertTrue(AppConstants.DEPTH_CONSISTENCY_MAX_RATIO <= 10.0f)
    }

    @Test
    fun `neighborhood radii are ordered`() {
        assertTrue(AppConstants.NEIGHBORHOOD_RADIUS_NEAR < AppConstants.NEIGHBORHOOD_RADIUS_MID)
        assertTrue(AppConstants.NEIGHBORHOOD_RADIUS_MID <= AppConstants.NEIGHBORHOOD_RADIUS_FAR)
    }

    @Test
    fun `IMU thresholds are positive`() {
        assertTrue(AppConstants.MAX_ROTATION_DEG > 0)
        assertTrue(AppConstants.MAX_VELOCITY_MS > 0)
        assertTrue(AppConstants.ROTATION_NOISE_FLOOR_DEG > 0)
    }

    @Test
    fun `adaptive alpha values are in valid range`() {
        assertTrue(AppConstants.IMU_ADAPTIVE_ALPHA_STATIC in 0.5f..1.0f)
        assertTrue(AppConstants.IMU_ADAPTIVE_ALPHA_MOTION in 0.5f..1.0f)
        assertTrue(AppConstants.IMU_ADAPTIVE_ALPHA_DEFAULT in 0.5f..1.0f)
        assertTrue(AppConstants.IMU_ADAPTIVE_ALPHA_MOTION < AppConstants.IMU_ADAPTIVE_ALPHA_STATIC)
    }

    @Test
    fun `fusion variances are positive`() {
        assertTrue(AppConstants.TOF_VARIANCE > 0)
        assertTrue(AppConstants.DEPTH16_VARIANCE_CLOSE > 0)
        assertTrue(AppConstants.DEPTH16_VARIANCE_FAR > 0)
        assertTrue(AppConstants.DEPTH16_VARIANCE_CLOSE <= AppConstants.DEPTH16_VARIANCE_FAR)
    }
}
