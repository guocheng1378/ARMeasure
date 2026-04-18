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
// ── 自适应置信度 ──
    /** 最近N次有效读数，用于评估稳定性 */
    private val recentReadings = ArrayDeque<Float>(CONFIDENCE_WINDOW)
    /** 上一次有效读数的时间戳 */
    private var lastValidTimestamp = 0L
    /** 连续有效读数计数 */
    private var consecutiveValid = 0
    /** 读数间最大时间间隔(ms)，超过则重置一致性 */
    private val maxIntervalMs = 500L

    companion object {
        private const val TAG = "TofSensorHelper"
        private val KNOWN_TOF_TYPES = intArrayOf(33171040, 33171041, 65570, 65572)
        private const val WARM_UP_SAMPLES = 3
        /** 置信度窗口大小 */
        private const val CONFIDENCE_WINDOW = 8
        /** 变异系数阈值: CV < 0.05 表示非常稳定 */
        private const val CV_STABLE = 0.05f
        /** 变异系数阈值: CV > 0.20 表示非常不稳定 */
        private const val CV_UNSTABLE = 0.20f
        /** 近距离不可靠阈值(cm): ToF 在极近距离精度下降 */
        private const val CLOSE_RANGE_CM = 20f
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
        windowSize = 5, alpha = 0.4f, maxJumpMm = 2000f, maxRangeMm = 5000f,
        processNoise = 300f, initMeasureNoise = 300f
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
        if (filtered > 0) {
            tofDistanceMm = filtered
            // Track for confidence scoring
            val now = System.currentTimeMillis()
            if (lastValidTimestamp > 0 && now - lastValidTimestamp > maxIntervalMs) {
                consecutiveValid = 0
                recentReadings.clear()
            }
            lastValidTimestamp = now
            consecutiveValid++
            recentReadings.addLast(filtered / 10f) // store in cm
            while (recentReadings.size > CONFIDENCE_WINDOW) recentReadings.removeFirst()
        }
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
    /**
     * ToF 置信度 (0~1): 基于最近读数的一致性。
     * - 变异系数(CV)低 → 高置信度
     * - 连续有效读数多 → 高置信度
     * - 近距离(<20cm) → 降低置信度
     */
    fun confidence(): Float {
        val readings = recentReadings
        if (readings.size < 3) return 0.3f // 数据不足，低置信度

        val mean = readings.average().toFloat()
        if (mean <= 0) return 0.1f

        // 变异系数 = 标准差 / 均值
        val variance = readings.sumOf { (it - mean) * (it - mean) }.toFloat() / readings.size
        val stddev = kotlin.math.sqrt(variance)
        val cv = stddev / mean

        // CV → 置信度映射
        val cvConfidence = when {
            cv < CV_STABLE -> 1.0f
            cv > CV_UNSTABLE -> 0.2f
            else -> 1f - (cv - CV_STABLE) / (CV_UNSTABLE - CV_STABLE) * 0.8f
        }

        // 连续性加分: 连续读数越多越可靠
        val continuityBonus = if (consecutiveValid >= 10) 0.1f else 0f

        // 近距离惩罚: ToF 在 <20cm 精度下降
        val rangePenalty = if (mean < CLOSE_RANGE_CM) 0.3f else 0f

        return (cvConfidence + continuityBonus - rangePenalty).coerceIn(0.1f, 1.0f)
    }

    /**
     * ToF 读数估计方差 (cm²)，用于加权融合。
     * 基于最近读数的实际统计方差，而非固定值。
     */
    fun estimatedVariance(): Float {
        val readings = recentReadings
        if (readings.size < 3) return 400f // 数据不足，返回大方差(20cm σ)

        val mean = readings.average().toFloat()
        val variance = readings.sumOf { (it - mean) * (it - mean) }.toFloat() / (readings.size - 1)

        // 下限: 即使非常稳定，方差不低于 4 (2cm σ)
        // 上限: 即使非常不稳定，方差不超过 900 (30cm σ)
        return variance.coerceIn(4f, 900f)
    }

    fun reset() {
        filter.reset()
        tofDistanceMm = -1f
        warmUpCount = 0
        recentReadings.clear()
        consecutiveValid = 0
        lastValidTimestamp = 0L
    }
}
