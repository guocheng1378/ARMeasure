package com.armeasure.app

/**
 * Multi-stage signal processing for dToF distance data.
 * Pipeline: raw → spike reject → median → Kalman → output
 *
 * Replaces fixed-alpha EMA with adaptive 1st-order Kalman filter.
 * Constructor signature kept backward-compatible with `alpha` param.
 */
class DistanceFilter(
    private val windowSize: Int = 5,
    @Suppress("unused") private val alpha: Float = 0.4f, // legacy, not used by Kalman
    private val maxJumpMm: Float = 0f,
    private val maxRangeMm: Float = 0f,
    private var processNoise: Float = 50f,
    private val initMeasureNoise: Float = 400f
) {
    private val medianBuffer = FloatArray(windowSize) { -1f }
    private val sortBuffer = FloatArray(windowSize)
    private var medianPos = 0
    private var medianFilled = false

    private var estimate = 0f
    private var errorCov = 1f
    private var kalmanInitialized = false

    private var lastRaw = -1f
    private var tickCount = 0

    fun filter(rawMm: Float): Float {
        if (rawMm <= 0) return if (kalmanInitialized) estimate else -1f
        if (maxRangeMm > 0 && rawMm >= maxRangeMm) return if (kalmanInitialized) estimate else -1f

        // After reset, skip spike rejection for the first few frames
        // to allow the filter to re-converge to new measurement distances.
        val skipSpikeCheck = !kalmanInitialized || tickCount < windowSize
        if (!skipSpikeCheck && lastRaw > 0 && maxJumpMm > 0 && Math.abs(rawMm - lastRaw) > maxJumpMm) {
            return if (kalmanInitialized) estimate else lastRaw
        }
        lastRaw = rawMm

        medianBuffer[medianPos] = rawMm
        medianPos = (medianPos + 1) % windowSize
        if (medianPos == 0) medianFilled = true

        val count = if (medianFilled) windowSize else medianPos
        if (count < 2) return applyKalman(rawMm)

        for (i in 0 until count) sortBuffer[i] = medianBuffer[i]
        // Insert sort: O(n) for small n, better cache behavior than TimSort
        for (i in 1 until count) {
            val key = sortBuffer[i]
            var j = i - 1
            while (j >= 0 && sortBuffer[j] > key) { sortBuffer[j + 1] = sortBuffer[j]; j-- }
            sortBuffer[j + 1] = key
        }
        return applyKalman(sortBuffer[count / 2])
    }

    /**
     * Fix #6: Adaptive Kalman with linear (not quadratic) innovation scaling.
     * Uses |innovation| × constant instead of innovation² × constant to prevent
     * Kalman gain collapse when switching to a new measurement target.
     */
    private fun applyKalman(z: Float): Float {
        tickCount++
        if (!kalmanInitialized) {
            estimate = z
            errorCov = initMeasureNoise
            kalmanInitialized = true
            return estimate
        }
        val predCov = errorCov + processNoise
        val innovation = z - estimate
        val rAdaptive = if (tickCount > windowSize) {
            val base = initMeasureNoise * 0.5f
            // Linear scaling: |innovation| × 0.5 — tracks new targets quickly
            // while still suppressing noise for small deviations.
            val dynamic = Math.abs(innovation) * 0.5f
            (base + dynamic).coerceIn(initMeasureNoise * 0.1f, initMeasureNoise * 3f)
        } else initMeasureNoise
        val gain = predCov / (predCov + rAdaptive)
        estimate += gain * innovation
        errorCov = (1f - gain) * predCov
        return estimate
    }

    fun reset() {
        medianPos = 0; medianFilled = false; kalmanInitialized = false
        lastRaw = -1f; estimate = 0f; errorCov = 1f; tickCount = 0
        medianBuffer.fill(-1f)
    }

    fun getCurrentValue(): Float = estimate
    fun isWarmedUp(): Boolean = medianFilled || medianPos >= 2

    /** #3: Dynamically adjust process noise based on IMU motion state */
    fun setProcessNoise(q: Float) {
        processNoise = q.coerceAtLeast(1f)
    }

    /** #7: Copy Kalman state from another filter for faster convergence */
    fun warmStartFrom(other: DistanceFilter) {
        if (other.kalmanInitialized && other.estimate > 0) {
            estimate = other.estimate
            errorCov = other.errorCov * 2f  // slightly higher uncertainty to allow adaptation
            kalmanInitialized = true
            tickCount = other.tickCount.coerceAtMost(windowSize)  // keep spike-check skip window
        }
    }
}
