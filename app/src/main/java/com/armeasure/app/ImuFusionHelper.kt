package com.armeasure.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.acos

class ImuFusionHelper(private val sensorManager: SensorManager) {

    var pitch = 0f
        private set
    var roll = 0f
        private set

    val tiltAngle: Float
        get() = acos(cos(pitch.toDouble()) * cos(roll.toDouble())).toFloat()

    private var accelX = 0f; private var accelY = 0f; private var accelZ = 0f
    private var gyroX = 0f; private var gyroY = 0f; private var gyroZ = 0f
    private var gyroPitch = 0f; private var gyroRoll = 0f
    private var lastTs = 0L
    private val alpha = 0.98f
    private var accelSensor: Sensor? = null; private var gyroSensor: Sensor? = null
    private var linAccelSensor: Sensor? = null

    // ── Linear acceleration (gravity removed) ──
    private var linAccelX = 0f; private var linAccelY = 0f; private var linAccelZ = 0f

    // ── Motion tracking between taps ──
    // Snapshot when user marks a point
    private var snapPitch = 0f; private var snapRoll = 0f
    private var snapLinAccelX = 0f; private var snapLinAccelY = 0f; private var snapLinAccelZ = 0f
    private var snapTimestamp = 0L
    private var hasSnapshot = false

    // Accumulated rotation since snapshot (rad) — body-rate gyro integral, usable
    // as an axis-angle vector for full 3D rotation compensation (pitch+roll+yaw).
    private var accumGyroX = 0.0; private var accumGyroY = 0.0; private var accumGyroZ = 0.0
    // #7: proper velocity integration via trapezoidal rule
    private var integratedVelocityMs = 0.0  // ∫|a(t)|dt in m/s
    private var lastLinAccelTimestamp = 0L
    private var motionSamples = 0

    // ── Smoothed "up" (gravity) direction in device/camera frame ──
    // At rest the accelerometer measures the reaction to gravity, which points up.
    // Low-pass filtered for noise rejection; used for gravity-frame height projection.
    private var upSmoothX = 0f; private var upSmoothY = 0f; private var upSmoothZ = 0f
    private var upFilterInit = false

    companion object {
        // Thresholds — tuned for phone held by hand (using constants)
        const val MAX_ROTATION_DEG = AppConstants.MAX_ROTATION_DEG
        const val MAX_VELOCITY_MS = AppConstants.MAX_VELOCITY_MS
        const val ROTATION_NOISE_FLOOR_DEG = AppConstants.ROTATION_NOISE_FLOOR_DEG
        // Low-pass coefficient for the smoothed up vector (0..1, higher = snappier)
        private const val UP_SMOOTHING_ALPHA = 0.15f
    }

    // ── Rotation compensation & gravity frame ──

    /**
     * Full 3D rotation matrix (row-major) describing how the camera frame rotated
     * between the last markPoint() and now, derived from the body-rate gyro integral.
     * Convention: v_at_markPoint = R · v_now (rotates a vector expressed in the
     * CURRENT camera frame back into the frame at markPoint time). Includes yaw,
     * unlike the old pitch/roll-only compensation.
     * @return 3×3 matrix, or null when no point is marked / rotation is negligible.
     */
    fun getDeltaRotationMatrix(): FloatArray? {
        if (!hasSnapshot) return null
        // Treat sub-noise-floor rotation as identity to avoid matrix churn
        val mag = sqrt(accumGyroX * accumGyroX + accumGyroY * accumGyroY + accumGyroZ * accumGyroZ)
        if (mag < Math.toRadians(ROTATION_NOISE_FLOOR_DEG.toDouble())) return null
        return MeasurementEngine.rotationMatrixFromAxisAngle(accumGyroX, accumGyroY, accumGyroZ)
    }

    /**
     * Normalized, low-pass filtered "up" (gravity) direction in the current
     * camera/device frame. Each depth sample should be paired with the up vector
     * at its own capture time so vertical (height) projection stays correct
     * while the device moves between taps.
     * @return float[3] unit vector, or null before the first accelerometer sample.
     */
    fun getUpVectorCam(): FloatArray? {
        if (!upFilterInit) return null
        val len = sqrt((upSmoothX * upSmoothX + upSmoothY * upSmoothY + upSmoothZ * upSmoothZ).toDouble())
        if (len < 1e-6) return null
        return floatArrayOf(
            (upSmoothX / len).toFloat(),
            (upSmoothY / len).toFloat(),
            (upSmoothZ / len).toFloat()
        )
    }

    // ── Motion detection API ──

    /** Call when user taps a point. Snapshots IMU state. */
    fun markPoint() {
        snapPitch = pitch; snapRoll = roll
        snapLinAccelX = linAccelX; snapLinAccelY = linAccelY; snapLinAccelZ = linAccelZ
        snapTimestamp = System.nanoTime()
        accumGyroX = 0.0; accumGyroY = 0.0; accumGyroZ = 0.0
        integratedVelocityMs = 0.0
        // Reset timestamp so first post-snapshot sample doesn't use stale dt
        lastLinAccelTimestamp = System.nanoTime()
        motionSamples = 0
        hasSnapshot = true
    }

    data class MotionResult(
        /** Rotation difference from snapshot (degrees) */
        val rotationDeg: Float,
        /** Estimated linear velocity from integrated acceleration (m/s) */
        val velocityMs: Float,
        /** Time elapsed since markPoint (ms) */
        val elapsedMs: Long,
        /** Whether motion exceeds thresholds */
        val excessive: Boolean,
        /** Human-readable warning, or null if OK */
        val warning: String?
    )

    /** Check accumulated motion since last markPoint(). */
    fun checkMotionSince(): MotionResult {
        if (!hasSnapshot) return MotionResult(0f, 0f, 0L, false, null)

        // Rotation: difference in pitch/roll from snapshot
        val dp = pitch - snapPitch
        val dr = roll - snapRoll
        val rotDeg = Math.toDegrees(atan2(
            sqrt((dp * dp + dr * dr).toDouble()), 1.0
        )).toFloat()

        // Also factor in accumulated gyro integral (catches rotation that was corrected by complementary filter)
        val gyroRotDeg = Math.toDegrees(sqrt(
            accumGyroX * accumGyroX + accumGyroY * accumGyroY + accumGyroZ * accumGyroZ
        )).toFloat()

        // Weighted blend of complementary-filter rotation and raw gyro integral
        // Complementary filter is smoother but may correct away large rotations
        // Gyro integral catches transient rotations but drifts over time
        val maxRotDeg = if (gyroRotDeg > rotDeg * 2f) {
            // Gyro detected significantly more rotation → trust it (complementary filter corrected it away)
            gyroRotDeg
        } else {
            // Normal case: complementary filter is more reliable
            rotDeg
        }

        // #7: velocity is now properly integrated via trapezoidal rule
        val elapsedNs = System.nanoTime() - snapTimestamp
        val elapsedMs = elapsedNs / 1_000_000
        val velocityMs = integratedVelocityMs.toFloat()

        val excessive = maxRotDeg > MAX_ROTATION_DEG || velocityMs > MAX_VELOCITY_MS

        val warning = when {
            maxRotDeg > MAX_ROTATION_DEG && velocityMs > MAX_VELOCITY_MS ->
                "⚠️ 设备移动过大 (旋转${String.format("%.1f", maxRotDeg)}° + 平移${String.format("%.1f", velocityMs)}m/s)"
            maxRotDeg > MAX_ROTATION_DEG ->
                "⚠️ 设备旋转过大 (${String.format("%.1f", maxRotDeg)}°)"
            velocityMs > MAX_VELOCITY_MS ->
                "⚠️ 设备平移过大 (${String.format("%.1f", velocityMs)}m/s)"
            maxRotDeg > ROTATION_NOISE_FLOOR_DEG * 2 ->
                "⚡ 轻微晃动 (${String.format("%.1f", maxRotDeg)}°)"
            else -> null
        }

        return MotionResult(maxRotDeg, velocityMs, elapsedMs, excessive, warning)
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = e.values[0]; accelY = e.values[1]; accelZ = e.values[2]
                    updateSmoothedUp()
                }
                Sensor.TYPE_GYROSCOPE -> processGyro(e)
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linAccelX = e.values[0]; linAccelY = e.values[1]; linAccelZ = e.values[2]
                    accumulateMotion(e.timestamp)
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /**
     * Low-pass filter the raw accelerometer into a smoothed up direction.
     * At rest the accelerometer reads the gravity reaction (pointing up in the
     * device frame); filtering suppresses hand-shake noise so the gravity-frame
     * height projection stays stable.
     */
    private fun updateSmoothedUp() {
        if (!upFilterInit) {
            upSmoothX = accelX; upSmoothY = accelY; upSmoothZ = accelZ
            upFilterInit = true
        } else {
            upSmoothX += UP_SMOOTHING_ALPHA * (accelX - upSmoothX)
            upSmoothY += UP_SMOOTHING_ALPHA * (accelY - upSmoothY)
            upSmoothZ += UP_SMOOTHING_ALPHA * (accelZ - upSmoothZ)
        }
    }

    /**
     * #26: Fix — always update lastLinAccelTimestamp even when no snapshot,
     * to avoid huge dt on first post-snapshot sample.
     * Uses event timestamp (not System.nanoTime) for consistent dt calculation.
     */
    private fun accumulateMotion(eventTimestamp: Long) {
        if (!hasSnapshot) return
        motionSamples++
        // Trapezoidal integration: v += |a| * dt
        if (lastLinAccelTimestamp > 0) {
            val dt = (eventTimestamp - lastLinAccelTimestamp) / 1_000_000_000.0
            if (dt > 0 && dt < AppConstants.VELOCITY_DT_MAX) {  // skip outliers
                val accelMag = sqrt(
                    (linAccelX * linAccelX + linAccelY * linAccelY + linAccelZ * linAccelZ).toDouble()
                )
                integratedVelocityMs += accelMag * dt
            }
        }
        lastLinAccelTimestamp = eventTimestamp
    }

    private fun processGyro(e: SensorEvent) {
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = (e.timestamp - lastTs) / 1_000_000_000f
        lastTs = e.timestamp
        if (dt <= 0f || dt > 0.5f) return

        gyroX = e.values[0]; gyroY = e.values[1]; gyroZ = e.values[2]

        // Accumulate gyro rotation for motion detection
        if (hasSnapshot) {
            accumGyroX += gyroX * dt
            accumGyroY += gyroY * dt
            accumGyroZ += gyroZ * dt
        }

        gyroRoll += gyroX * dt
        gyroPitch += gyroY * dt
        val ap = atan2(-accelX.toDouble(), sqrt((accelY * accelY + accelZ * accelZ).toDouble()).coerceAtLeast(0.1)).toFloat()
        val ar = atan2(accelY.toDouble(), accelZ.toDouble()).toFloat()
        // #4: adaptive alpha using constants
        val accelMag = sqrt((accelX * accelX + accelY * accelY + accelZ * accelZ).toDouble())
        val dynamicAlpha = when {
            accelMag > AppConstants.IMU_ACCEL_THRESHOLD_HIGH -> AppConstants.IMU_ADAPTIVE_ALPHA_MOTION
            accelMag < AppConstants.IMU_ACCEL_THRESHOLD_LOW  -> AppConstants.IMU_ADAPTIVE_ALPHA_STATIC
            else -> AppConstants.IMU_ADAPTIVE_ALPHA_DEFAULT
        }
        pitch = dynamicAlpha * gyroPitch + (1f - dynamicAlpha) * ap
        roll = dynamicAlpha * gyroRoll + (1f - dynamicAlpha) * ar
        gyroPitch = pitch; gyroRoll = roll
    }

    fun start() {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        linAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        linAccelSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        lastTs = 0L
    }

    private var _available: Boolean? = null
    fun isAvailable(): Boolean {
        _available?.let { return it }
        val result = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                && sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        _available = result
        return result
    }

    fun reset() {
        gyroPitch = 0f; gyroRoll = 0f
        pitch = 0f; roll = 0f
        lastTs = 0L
        hasSnapshot = false
        accumGyroX = 0.0; accumGyroY = 0.0; accumGyroZ = 0.0
        integratedVelocityMs = 0.0; lastLinAccelTimestamp = 0L; motionSamples = 0
        // Keep upSmooth* running (it converges fast); only reset motion tracking
    }
}
