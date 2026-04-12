package com.armeasure.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DistanceFilterTest {

    private lateinit var filter: DistanceFilter

    @Before
    fun setUp() {
        filter = DistanceFilter(windowSize = 5, alpha = 0.4f, maxJumpMm = 800f, maxRangeMm = 5000f)
    }

    @Test
    fun `first reading initializes filter`() {
        val result = filter.filter(1000f)
        assertEquals(1000f, result, 0.1f)
    }

    @Test
    fun `stable readings converge`() {
        var result = 0f
        repeat(20) { result = filter.filter(1500f) }
        assertEquals(1500f, result, 5f)
    }

    @Test
    fun `small noise is smoothed`() {
        val readings = floatArrayOf(1000f, 1020f, 980f, 1010f, 990f, 1005f)
        var result = 0f
        for (r in readings) { result = filter.filter(r) }
        assertTrue("Smoothed value $result should be near 1000", Math.abs(result - 1000f) < 50f)
    }

    @Test
    fun `spikes beyond maxJump are rejected`() {
        filter.filter(1000f)
        filter.filter(1000f)
        filter.filter(1000f)
        val result = filter.filter(3000f)
        assertTrue("Spike should be rejected, got $result", result < 2000f)
    }

    @Test
    fun `readings above maxRange are clamped`() {
        filter.filter(4000f)
        filter.filter(4000f)
        val result = filter.filter(5500f)
        assertEquals(4000f, result, 10f)
    }

    @Test
    fun `zero or negative returns previous estimate`() {
        filter.filter(1000f)
        filter.filter(1000f)
        val result = filter.filter(0f)
        assertEquals(1000f, result, 10f)
    }

    @Test
    fun `reset clears all state`() {
        filter.filter(1000f)
        filter.filter(1000f)
        filter.reset()
        val result = filter.filter(2000f)
        assertEquals(2000f, result, 0.1f)
    }

    @Test
    fun `isWarmedUp returns false initially`() {
        assertFalse(filter.isWarmedUp())
        filter.filter(1000f)
        assertFalse(filter.isWarmedUp())
        filter.filter(1000f)
        assertTrue(filter.isWarmedUp())
    }
}
