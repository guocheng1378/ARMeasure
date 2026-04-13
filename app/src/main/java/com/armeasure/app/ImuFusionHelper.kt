package com.armeasure.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin
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

    // Accumulated rotation since snapshot (rad)
    private var accumGyroX = 0.0; private var accumGyroY = 0.0; private var accumGyroZ = 0.0
    private var accumLinAccelMag = 0.0  // accumulated |linear accel| integral (m/s)
    private var motionSamples = 0

    companion object {
        // Thresholds — tuned for phone held by hand
        /** Max acceptable rotation between taps (degrees) */
        const val MAX_ROTATION_DEG = 3.0f
        /** Max acceptable linear velocity between taps (m/s) */
        const val MAX_VELOCITY_MS = 0.15f
        /** Min reliable rotation to flag (degrees) — filter out sensor noise */
        const val ROTATION_NOISE_FLOOR_DEG = 0.3f
    }

    fun compensateDepth(depthCm: Float): Float {
        if (depthCm <= 0) return depthCm
        val c = cos(tiltAngle.toDouble()).toFloat()
        return if (c > 0.5f) depthCm * c else depthCm
    }

    fun getCorrectionFactor(sx: Float, sy: Float, vw: Float, vh: Float): Float {
        val c = cos(tiltAngle.toDouble()).toFloat()
        if (c <= 0.5f) return 1f
        val nx = (sx / vw - 0.5f) * 2f
        val ny = (0.5f - sy / vh) * 2f
        val pc = ny * sin(pitch.toDouble()).toFloat() * 0.3f
        val rc = nx * sin(roll.toDouble()).toFloat() * 0.3f
        return (c + pc + rc).coerceIn(0.3f, 1.2f)
    }

    // ── Motion detection API ──

    /** Call when user taps a point. Snapshots IMU state. */
    fun markPoint() {
        snapPitch = pitch; snapRoll = roll
        snapLinAccelX = linAccelX; snapLinAccelY = linAccelY; snapLinAccelZ = linAccelZ
        snapTimestamp = System.nanoTime()
        accumGyroX = 0.0; accumGyroY = 0.0; accumGyroZ = 0.0
        accumLinAccelMag = 0.0
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

        val maxRotDeg = maxOf(rotDeg, gyroRotDeg)

        // Velocity: integrate |linear acceleration| over time
        val elapsedNs = System.nanoTime() - snapTimestamp
        val elapsedMs = elapsedNs / 1_000_000
        val elapsedS = elapsedNs / 1_000_000_000.0
        val velocityMs = if (motionSamples > 0 && elapsedS > 0) {
            // Average linear acceleration magnitude × time = rough velocity estimate
            (accumLinAccelMag / motionSamples * elapsedS).toFloat()
        } else 0f

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

    /** Get a motion-aware depth correction factor for depth sensor readings. */
    fun getMotionCorrectionFactor(): Float {
        if (!hasSnapshot) return 1f
        val motion = checkMotionSince()
        // When device is moving fast, depth sensors become unreliable
        // Reduce confidence by scaling — pure heuristic
        return when {
            motion.excessive -> 0.7f  // significant motion → depth likely off
            motion.rotationDeg > ROTATION_NOISE_FLOOR_DEG * 3 -> 0.9f
            else -> 1f
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { accelX = e.values[0]; accelY = e.values[1]; accelZ = e.values[2] }
                Sensor.TYPE_GYROSCOPE -> processGyro(e)
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linAccelX = e.values[0]; linAccelY = e.values[1]; linAccelZ = e.values[2]
                    accumulateMotion()
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun accumulateMotion() {
        if (!hasSnapshot) return
        motionSamples++
        accumLinAccelMag += sqrt(
            (linAccelX * linAccelX + linAccelY * linAccelY + linAccelZ * linAccelZ).toDouble()
        )
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
        val ap = atan2(-accelX.toDouble(), sqrt((accelY * accelY + accelZ * accelZ).toDouble())).toFloat()
        val ar = atan2(accelY.toDouble(), accelZ.toDouble()).toFloat()
        pitch = alpha * gyroPitch + (1f - alpha) * ap
        roll = alpha * gyroRoll + (1f - alpha) * ar
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
        accumLinAccelMag = 0.0; motionSamples = 0
    }
}
