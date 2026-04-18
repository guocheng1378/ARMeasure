package com.armeasure.app

/**
 * Centralized constants — eliminates magic numbers across the codebase.
 */
object AppConstants {

    // ── Depth filtering ──
    const val DEPTH_WINDOW_SIZE = 5
    const val DEPTH_MAX_JUMP_MM = 2000f
    const val DEPTH_MAX_RANGE_MM = 5000f
    const val DEPTH_PROCESS_NOISE = 300f
    const val DEPTH_INIT_MEASURE_NOISE = 300f
    const val DEPTH_SAMPLE_COUNT = 5
    const val DEPTH_SAMPLE_INTERVAL_MS = 80L

    // ── Depth fusion variances (cm²) ──
    const val TOF_VARIANCE = 25f           // 5cm σ
    const val DEPTH16_VARIANCE_CLOSE = 64f // <1m
    const val DEPTH16_VARIANCE_FAR = 144f  // ≥1m
    const val DEPTH16_CLOSE_THRESHOLD_CM = 100f

    // ── IMU motion detection ──
    const val MAX_ROTATION_DEG = 5.0f
    const val MAX_VELOCITY_MS = 0.25f
    const val ROTATION_NOISE_FLOOR_DEG = 0.5f
    const val IMU_ADAPTIVE_ALPHA_STATIC = 0.99f
    const val IMU_ADAPTIVE_ALPHA_MOTION = 0.90f
    const val IMU_ADAPTIVE_ALPHA_DEFAULT = 0.98f
    const val IMU_ACCEL_THRESHOLD_HIGH = 12.0
    const val IMU_ACCEL_THRESHOLD_LOW = 8.5

    // ── Depth consistency ──
    const val DEPTH_CONSISTENCY_MAX_RATIO = 3.0f  // max d1/d2 ratio without warning

    // ── Focus distance change threshold (diopters) ──
    const val FOCUS_RESET_THRESHOLD = 0.5f

    // ── Adaptive depth neighborhood ──
    const val NEIGHBORHOOD_NEAR_CM = 100f   // <1m → small kernel
    const val NEIGHBORHOOD_FAR_CM = 300f    // >3m → large kernel
    const val NEIGHBORHOOD_RADIUS_NEAR = 1  // 3×3
    const val NEIGHBORHOOD_RADIUS_MID = 2   // 5×5
    const val NEIGHBORHOOD_RADIUS_FAR = 3   // 7×7

    // ── UI ──
    const val CROSSHAIR_SIZE = 20f
    const val PLACEMENT_CROSSHAIR_SIZE = 24f
}
