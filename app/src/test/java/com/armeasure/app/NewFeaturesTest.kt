package com.armeasure.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for new features and AppConstants.
 * Pure JVM tests — no Android dependencies.
 */
class NewFeaturesTest {

    // ── AppConstants sanity checks ──

    @Test
    fun `FOV tan clamp prevents blowup`() {
        val clamp = AppConstants.FOV_TAN_CLAMP.toDouble()
        assertTrue("Clamp should be < 1.0 to prevent infinity", clamp < 1.0)
        assertTrue("Clamp should be > 0.5 to not lose data", clamp > 0.5)
    }

    @Test
    fun `depth sample count is reasonable`() {
        assertTrue("Sample count should be >= 2", AppConstants.DEPTH_SAMPLE_COUNT >= 2)
        assertTrue("Sample count should be <= 20", AppConstants.DEPTH_SAMPLE_COUNT <= 20)
    }

    @Test
    fun `IMU thresholds are consistent`() {
        assertTrue("High threshold > Low threshold",
            AppConstants.IMU_ACCEL_THRESHOLD_HIGH > AppConstants.IMU_ACCEL_THRESHOLD_LOW)
        assertTrue("Motion alpha < Static alpha (more responsive during motion)",
            AppConstants.IMU_ADAPTIVE_ALPHA_MOTION < AppConstants.IMU_ADAPTIVE_ALPHA_STATIC)
    }

    @Test
    fun `neighborhood radii are ordered`() {
        assertTrue("Near < Mid",
            AppConstants.NEIGHBORHOOD_RADIUS_NEAR < AppConstants.NEIGHBORHOOD_RADIUS_MID)
        assertTrue("Mid < Far",
            AppConstants.NEIGHBORHOOD_RADIUS_MID < AppConstants.NEIGHBORHOOD_RADIUS_FAR)
    }

    @Test
    fun `robust MAD threshold is small positive value`() {
        assertTrue("MAD min threshold should be > 0", AppConstants.ROBUST_MAD_MIN_THRESHOLD > 0f)
        assertTrue("MAD min threshold should be < 1", AppConstants.ROBUST_MAD_MIN_THRESHOLD < 1f)
    }

    // ── MeasurementEngine RANSAC tests ──

    @Test
    fun `RANSAC area equals Newell for coplanar triangle`() {
        val pts = listOf(
            Triple(0f, 0f, 100f),
            Triple(10f, 0f, 100f),
            Triple(0f, 10f, 100f)
        )
        val newell = MeasurementEngine.computePolygonArea3D(pts)
        val ransac = MeasurementEngine.computePolygonArea3DRansac(pts)
        assertEquals(newell, ransac, 0.1f)
    }

    @Test
    fun `RANSAC area with less than 3 points returns zero`() {
        assertEquals(0f, MeasurementEngine.computePolygonArea3DRansac(emptyList()), 0.01f)
        assertEquals(0f, MeasurementEngine.computePolygonArea3DRansac(
            listOf(Triple(1f, 2f, 3f))
        ), 0.01f)
    }

    @Test
    fun `3D polygon area for square`() {
        val pts = listOf(
            Triple(0f, 0f, 100f),
            Triple(10f, 0f, 100f),
            Triple(10f, 10f, 100f),
            Triple(0f, 10f, 100f)
        )
        val area = MeasurementEngine.computePolygonArea3D(pts)
        assertEquals(100f, area, 0.5f)
    }
}
