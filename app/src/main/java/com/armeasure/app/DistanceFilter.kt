package com.armeasure.app

import java.util.Arrays

/**
 * Multi-stage signal processing for dToF distance data.
 * Pipeline: raw → spike reject → median → EMA → output
 *
 * Ported from tof-ranger (DistanceFilter.java) to Kotlin.
 */
class DistanceFilter(
    private val windowSize: Int = 5,
    private val alpha: Float = 0.4f,
    private val maxJumpMm: Float = 0f,
    private val maxRangeMm: Float = 0f
) {
    private val medianBuffer = FloatArray(windowSize) { -1f }
    private var medianPos = 0
    private var medianFilled = false

    private var ema = 0f
    private var emaInitialized = false

    private var lastRaw = -1f

    /**
     * Feed a raw distance reading (mm). Returns filtered distance in mm, or -1 if rejected.
     */
    fun filter(rawMm: Float): Float {
        if (rawMm <= 0) return if (emaInitialized) ema else -1f

        // Range check
        if (maxRangeMm > 0 && rawMm >= maxRangeMm) {
            return if (emaInitialized) ema else -1f
        }

        // Spike rejection — reject sudden jumps > threshold
        if (lastRaw > 0 && maxJumpMm > 0) {
            if (Math.abs(rawMm - lastRaw) > maxJumpMm) {
                // Spike detected: keep previous filtered value
                return if (emaInitialized) ema else lastRaw
            }
        }
        lastRaw = rawMm

        // Fill median buffer
        medianBuffer[medianPos] = rawMm
        medianPos = (medianPos + 1) % windowSize
        if (medianPos == 0) medianFilled = true

        val count = if (medianFilled) windowSize else medianPos
        if (count < 2) {
            return applyEma(rawMm)
        }

        // Median filter
        val sorted = medianBuffer.copyOf(count)
        sorted.sort()
        val median = sorted[count / 2]

        return applyEma(median)
    }

    private fun applyEma(value: Float): Float {
        if (!emaInitialized) {
            ema = value
            emaInitialized = true
        } else {
            ema = alpha * value + (1 - alpha) * ema
        }
        return ema
    }

    fun reset() {
        medianPos = 0
        medianFilled = false
        emaInitialized = false
        lastRaw = -1f
        ema = 0f
        Arrays.fill(medianBuffer, -1f)
    }

    fun getCurrentValue(): Float = ema

    fun isWarmedUp(): Boolean = medianFilled || medianPos >= 2
}
