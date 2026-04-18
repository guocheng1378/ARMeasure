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

    // ── Depth temporal smoothing (multi-frame median) ──
    const val TEMPORAL_FRAME_COUNT = 5    // keep last N depth frames for median
    const val DEPTH_EDGE_VARIANCE_THRESHOLD = 800f  // mm² — neighbor variance at depth edges
    const val DEPTH_EDGE_CONFIDENCE_MIN = 0.3f      // min confidence at object boundaries
    const val DEPTH_BILATERAL_SIGMA_MM = 200f       // depth similarity sigma for bilateral filter (mm)

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

    // ── Timing / debounce ──
    const val TAP_DEBOUNCE_MS = 400L
    const val MODE_DEBOUNCE_MS = 300L
    const val SETTLE_MS_DEPTH = 600L       // depth camera settling time after tap
    const val SETTLE_MS_AF = 800L          // AF settling time after tap
    const val CALIBRATION_SAMPLE_COUNT = 5
    const val CALIBRATION_SAMPLE_INTERVAL_MS = 150L

    // ── FOV projection ──
    const val FOV_TAN_CLAMP = 0.95f        // clamp tan input to avoid infinity at edges

    // ── Robust depth ──
    const val ROBUST_MAD_MIN_THRESHOLD = 0.1f  // min MAD to trigger outlier rejection

    // ── IMU velocity integration ──
    const val VELOCITY_DT_MAX = 0.1        // max dt in seconds for velocity integration

    // ── Sweep ──
    const val SWEEP_RULER_COUNT = 5        // number of Y-axis ruler labels
    const val SWEEP_GRID_ALPHA = 25        // alpha for grid lines

    // ── Haptic ──
    const val HAPTIC_TAP_MS = 20L
    const val HAPTIC_COMPLETE_MS = 15L     // double pulse
    const val HAPTIC_COMPLETE_GAP_MS = 80L
    const val HAPTIC_WARNING_MS = 50L

    // ── Tutorial ──
    const val TUTORIAL_PREF = "armeasure_tutorial"
    const val TUTORIAL_SHOWN_KEY = "shown"
}
