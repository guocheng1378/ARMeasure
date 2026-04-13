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
    private var gyroPitch = 0f; private var gyroRoll = 0f
    private var lastTs = 0L
    private val alpha = 0.98f
    private var accelSensor: Sensor? = null; private var gyroSensor: Sensor? = null

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

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { accelX = e.values[0]; accelY = e.values[1]; accelZ = e.values[2] }
                Sensor.TYPE_GYROSCOPE -> processGyro(e)
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun processGyro(e: SensorEvent) {
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = (e.timestamp - lastTs) / 1_000_000_000f
        lastTs = e.timestamp
        if (dt <= 0f || dt > 0.5f) return
        gyroRoll += e.values[0] * dt
        gyroPitch += e.values[1] * dt
        val ap = atan2(-accelX.toDouble(), sqrt((accelY * accelY + accelZ * accelZ).toDouble())).toFloat()
        val ar = atan2(accelY.toDouble(), accelZ.toDouble()).toFloat()
        pitch = alpha * gyroPitch + (1f - alpha) * ap
        roll = alpha * gyroRoll + (1f - alpha) * ar
        gyroPitch = pitch; gyroRoll = roll
    }

    fun start() {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
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
        gyroPitch = 0f
        gyroRoll = 0f
        pitch = 0f
        roll = 0f
        lastTs = 0L
    }
}
