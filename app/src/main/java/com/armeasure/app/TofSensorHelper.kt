package com.armeasure.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.Locale

/**
 * Encapsulates ToF sensor detection, warm-up, and filtered distance reading.
 */
class TofSensorHelper(
    private val sensorManager: SensorManager
) {
    companion object {
        private const val TAG = "TofSensorHelper"
        private val KNOWN_TOF_TYPES = intArrayOf(33171040, 33171041, 65570, 65572)
        private const val WARM_UP_SAMPLES = 3
    }

    var tofSensor: Sensor? = null
        private set
    var detectedTofType: Int = 0
        private set
    var hasRealTof: Boolean = false
        private set
    var sensorLabel: String = "未找到"
        private set

    @Volatile
    var tofDistanceMm: Float = -1f
        private set

    private val filter = DistanceFilter(
        windowSize = 5, alpha = 0.4f, maxJumpMm = 800f, maxRangeMm = 5000f
    )
    private var warmUpCount = 0

    /**
     * Detect available ToF sensor. Call once at startup.
     */
    fun detect() {
        try {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            tofSensor = null
            sensorLabel = "未找到"

            // 1. Check known ToF sensor types
            for (tofType in KNOWN_TOF_TYPES) {
                for (s in allSensors) {
                    if (s.type == tofType) {
                        tofSensor = s
                        detectedTofType = tofType
                        sensorLabel = "${s.name} (type=$tofType)"
                        hasRealTof = true
                        return
                    }
                }
            }

            // 2. Name-based heuristic for unknown ToF types
            for (s in allSensors) {
                val name = s.name.lowercase(Locale.ROOT)
                if (s.type < 65536) continue
                if (name.contains("tof") || name.contains("vl53") ||
                    name.contains("d-tof") || name.contains("dtof") || name.contains("range")
                ) {
                    tofSensor = s
                    detectedTofType = s.type
                    sensorLabel = "${s.name} (type=${s.type} 匹配)"
                    hasRealTof = true
                    return
                }
            }

            // 3. Proximity fallback
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (tofSensor != null) {
                sensorLabel = "${tofSensor!!.name} (Proximity降级)"
                hasRealTof = false
            }

            Log.d(TAG, "ToF sensor: $sensorLabel (hasRealTof=$hasRealTof)")
        } catch (e: Exception) {
            Log.e(TAG, "detect failed", e)
            sensorLabel = "⚠️ 传感器检测异常"
        }
    }

    /**
     * Process a sensor event. Returns true if the event was consumed.
     */
    fun onSensorEvent(event: SensorEvent): Boolean {
        val isTofEvent = (detectedTofType > 0 && event.sensor.type == detectedTofType)
                || event.sensor.type == Sensor.TYPE_PROXIMITY
        if (!isTofEvent) return false

        val raw = event.values[0]
        if (warmUpCount < WARM_UP_SAMPLES) { warmUpCount++; return true }

        val overflowThresholdMm = tofSensor?.let { maxOf(it.maximumRange, 4000f) } ?: 4000f
        if (raw <= 0 || raw >= overflowThresholdMm) return true

        val filtered = filter.filter(raw)
        if (filtered > 0) tofDistanceMm = filtered
        return true
    }

    fun registerListener(listener: SensorEventListener) {
        tofSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun unregisterListener(listener: SensorEventListener) {
        sensorManager.unregisterListener(listener)
    }

    /**
     * Get distance in cm, or null if unavailable/too close.
     */
    fun getDistanceCm(minCm: Float = 15f): Float? {
        if (tofDistanceMm <= 0) return null
        val cm = tofDistanceMm / 10f
        return if (cm >= minCm) cm else null
    }

    /**
     * Reset the internal filter state. Call when starting a new measurement.
     */
    fun reset() {
        filter.reset()
        tofDistanceMm = -1f
        warmUpCount = 0
    }
}
