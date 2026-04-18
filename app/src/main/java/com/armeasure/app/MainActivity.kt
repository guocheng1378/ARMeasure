package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.armeasure.app.databinding.ActivityMainBinding
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraCtrl: CameraController
    private lateinit var tofHelper: TofSensorHelper
    private lateinit var imuHelper: ImuFusionHelper

    @Volatile private var depthBuffer: ShortArray? = null
    @Volatile private var depthWidth: Int = 0
    @Volatile private var depthHeight: Int = 0
    private val depthLock = Any() // Fix #6: synchronize depth buffer access between BG and UI threads
    private val depthFilter = DistanceFilter(windowSize = 5, alpha = 0.4f, maxJumpMm = 2000f, maxRangeMm = 5000f, processNoise = 300f, initMeasureNoise = 300f)

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    @Volatile private var currentFocusDistance: Float = -1f

    private enum class Mode { POINT, LINE, AREA, SWEEP }
    private enum class MeasureUnit { CM, INCH, M }
    private var currentUnit = MeasureUnit.CM

    // Calibration
    private var calibrationFactor = 1.0f
    private var isCalibrated = false
    private var calibrating = false

    // History
    private val historyPrefs by lazy { getSharedPreferences("armeasure_history", MODE_PRIVATE) }
    private val measurementHistory = mutableListOf<HistoryEntry>()
    data class HistoryEntry(val timestamp: Long, val mode: String, val result: String, val unit: String)
    private var currentMode = Mode.POINT
    @Volatile private var cameraOpening = false

    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var firstDistance: Float = 0f
    private var firstUncertainty: Float = 0f
    private var measuredResult = "--"
    private val sweepHistory = mutableListOf<Pair<Float, Float>>()
    private val sweepLock = Any()
    private val depthCache = mutableMapOf<Int, Float>()
    private val maxSweepHistory = 200

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { java.io.File(getExternalFilesDir(null), "crash.log").run { if (length() > 512*1024) delete(); appendText("${java.util.Date()} ${e.stackTraceToString()}\n\n") } } catch (_: Exception) {}
            throw e
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        startBackgroundThread()  // Must start before CameraController so backgroundHandler is ready
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        cameraCtrl = CameraController(this, cm, backgroundHandler, binding.surfaceView)
        tofHelper = TofSensorHelper(sm)
        imuHelper = ImuFusionHelper(sm)
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }
        binding.overlayView.onMove = { x, y ->
            if (binding.overlayView.placingSecondPoint) {
                binding.overlayView.liveCrosshair = PointF(x, y)
                binding.overlayView.invalidate()
            }
        }
        binding.overlayView.onSweepMove = { x, y -> onSweepMoved(x, y) }
        binding.surfaceView.holder.addCallback(this)
        tofHelper.detect()
        setupUI()
        loadCalibration()
        loadHistory()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQUEST_CAMERA || results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show(); finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread(); cameraCtrl.backgroundHandler = backgroundHandler
        tofHelper.registerListener(this); imuHelper.start()
        if (cameraCtrl.cameraDevice == null && cameraCtrl.captureSession == null
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    override fun onPause() {
        tofHelper.unregisterListener(this); imuHelper.stop()
        cameraCtrl.close(); stopBackgroundThread()
        // Clear depth data after camera is closed and callbacks are stopped
        depthBuffer = null; depthBufReusable = null
        calibrating = false
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) { tofHelper.onSensorEvent(event) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startCamera() {
        if (cameraOpening || cameraCtrl.cameraDevice != null) return
        cameraOpening = true
        // Fix #8: invalidate FOV cache on new camera session
        cachedHfov = Double.NaN; cachedVfov = Double.NaN
        val selection = cameraCtrl.selectCameras() ?: run { binding.tvSensor.text = "无可用摄像头"; cameraOpening = false; return }
        cameraCtrl.onDepthImageAvailable = { reader -> processDepthImage(reader) }
        cameraCtrl.captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (fd != null && fd > 0) {
                    val newFocus = 1f / fd
                    // #4: reset depth filter when focus distance changes significantly
                    if (currentFocusDistance > 0 && Math.abs(newFocus - currentFocusDistance) > AppConstants.FOCUS_RESET_THRESHOLD) {
                        depthFilter.reset()
                        Log.d(TAG, "Focus changed: ${currentFocusDistance}→$newFocus, filter reset")
                    }
                    currentFocusDistance = newFocus
                }
            }
        }
        cameraCtrl.openCamera(selection, onReady = { same ->
            cameraOpening = false
            updateSensorLabel()
            // NOTE: openCamera() already calls setupPreviewSession() in its onOpened callback.
            // Do NOT call setupPreviewSession() here again — it destroys+recreates the capture session,
            // causing the preview to flash/switch back and forth.
        }, onError = { cameraOpening = false })
    }

    private fun updateSensorLabel() {
        val parts = mutableListOf<String>()
        if (cameraCtrl.depthCameraEnabled && cameraCtrl.hasDepthMap) {
            parts.add("DEPTH16")
            if (imuHelper.isAvailable()) parts.add("IMU")
        } else if (cameraCtrl.hasSeparateDepthCamera) {
            parts.add("主摄")
        }
        if (tofHelper.hasRealTof) parts.add("ToF")
        else if (!cameraCtrl.depthCameraEnabled) parts.add("AF")
        if (parts.isEmpty()) parts.add("AF")
        runOnUiThread { binding.tvSensor.text = parts.joinToString(" + ") }
    }

    private fun toggleDepthCamera() {
        if (cameraOpening) return
        if (!cameraCtrl.hasSeparateDepthCamera && !cameraCtrl.hasDepthMap) {
            Toast.makeText(this, "设备无深度摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        cameraOpening = true
        depthBuffer = null

        cameraCtrl.toggleDepthCamera { enabled ->
            cameraOpening = false
            runOnUiThread {
                updateDepthToggleButton()
                updateSensorLabel()
                val msg = if (enabled) "深度摄像头已开启" else "深度摄像头已关闭"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDepthToggleButton() {
        val enabled = cameraCtrl.depthCameraEnabled
        binding.btnDepthToggle.text = if (enabled) "深度:开" else "深度:关"
        binding.btnDepthToggle.setBackgroundColor(
            if (enabled) 0x3300FF88.toInt() else 0x00000000
        )
    }

    private fun getDistanceAt(sx: Float, sy: Float, depthFilterOverride: DistanceFilter? = null): Float? {
        var raw: Float? = null
        // Try depth map first, then ToF, then AF focus distance
        if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) raw = getDepthAtScreenPoint(sx, sy, depthFilterOverride ?: depthFilter)
        val tofDist = tofHelper.getDistanceCm()

        // Depth fusion: when both depth map and ToF are available, combine them
        if (raw != null && raw > 0 && tofDist != null && tofDist > 0) {
            // ToF is typically more precise at short range, depth map gives spatial info
            // Use inverse-variance weighting: ToF variance ~25cm² (5cm σ), DEPTH16 ~100cm² (10cm σ)
            val tofVar = AppConstants.TOF_VARIANCE
            val depthVar = if (raw < AppConstants.DEPTH16_CLOSE_THRESHOLD_CM) AppConstants.DEPTH16_VARIANCE_CLOSE else AppConstants.DEPTH16_VARIANCE_FAR
            raw = MeasurementEngine.fuseDepth(raw, depthVar, tofDist, tofVar)
        } else if (raw == null || raw <= 0) {
            raw = tofDist
        }
        if (raw == null || raw <= 0) { val d = currentFocusDistance; if (d > 0) raw = d * 100f }
        if (raw != null && raw > 0 && isCalibrated) raw = raw * calibrationFactor
        if (raw != null && raw > 0 && imuHelper.isAvailable()) {
            val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
            return if (vw > 0 && vh > 0) raw * imuHelper.getCorrectionFactor(sx, sy, vw, vh, raw) else imuHelper.compensateDepth(raw)
        }
        return raw
    }

    data class DepthResult(val depthCm: Float, val uncertaintyCm: Float)

    /**
     * Collect multiple depth samples at a screen point across frames.
     * Uses median + MAD outlier rejection for robustness.
     * Returns both the robust depth and the sample standard deviation as uncertainty.
     */
    private fun collectDepthSamples(sx: Float, sy: Float, sampleCount: Int = AppConstants.DEPTH_SAMPLE_COUNT, intervalMs: Long = AppConstants.DEPTH_SAMPLE_INTERVAL_MS, onProgress: ((Int, Int) -> Unit)? = null, onComplete: (DepthResult?) -> Unit) {
        // #1 + #2: Handler chain instead of Thread.sleep, with progress callback
        val localFilter = DistanceFilter(
            windowSize = AppConstants.DEPTH_WINDOW_SIZE, alpha = 0.4f,
            maxJumpMm = AppConstants.DEPTH_MAX_JUMP_MM, maxRangeMm = AppConstants.DEPTH_MAX_RANGE_MM,
            processNoise = AppConstants.DEPTH_PROCESS_NOISE, initMeasureNoise = AppConstants.DEPTH_INIT_MEASURE_NOISE
        )
        val samples = mutableListOf<Float>()
        var sampleIndex = 0

        fun nextSample() {
            if (sampleIndex >= sampleCount) {
                val robust = MeasurementEngine.robustDepth(samples)
                val unc = if (samples.size >= 2) {
                    val mean = samples.average().toFloat()
                    val variance = samples.sumOf { ((it - mean) * (it - mean)).toDouble() } / (samples.size - 1)
                    sqrt(variance).toFloat()
                } else 0f
                onComplete(if (robust != null && robust > 0) DepthResult(robust, unc) else null)
                return
            }
            val d = getDistanceAt(sx, sy, localFilter)
            if (d != null && d > 0) samples.add(d)
            sampleIndex++
            onProgress?.invoke(sampleIndex, sampleCount)
            if (sampleIndex < sampleCount) {
                backgroundHandler?.postDelayed({ nextSample() }, intervalMs)
            } else {
                nextSample()  // last sample, complete immediately
            }
        }
        nextSample()
    }

    private var cachedHfov: Double = Double.NaN
    private var cachedVfov: Double = Double.NaN

    private fun convertUnit(cm: Float): Pair<Float, String> = when (currentUnit) {
        MeasureUnit.CM -> Pair(cm, "cm")
        MeasureUnit.INCH -> Pair(cm / 2.54f, "in")
        MeasureUnit.M -> Pair(cm / 100f, "m")
    }

    private fun formatDistance(cm: Float): String {
        val (v, u) = convertUnit(cm)
        return String.format("%.1f %s", v, u)
    }

    private fun formatArea(cm2: Float): String {
        val (v, u) = when (currentUnit) {
            MeasureUnit.CM -> Pair(cm2, "cm²")
            MeasureUnit.INCH -> Pair(cm2 / (2.54f * 2.54f), "in²")
            MeasureUnit.M -> Pair(cm2 / 10000f, "m²")
        }
        return String.format("%.1f %s", v, u)
    }

    private fun getHfovDegrees(): Double {
        if (!cachedHfov.isNaN()) return cachedHfov
        val s = cameraCtrl.sensorSize ?: return 65.0; val f = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 65.0
        cachedHfov = 2 * Math.toDegrees(Math.atan(s.width / 2.0 / f)); return cachedHfov
    }
    private fun getVfovDegrees(): Double {
        if (!cachedVfov.isNaN()) return cachedVfov
        val s = cameraCtrl.sensorSize ?: return 50.0; val f = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 50.0
        cachedVfov = 2 * Math.toDegrees(Math.atan(s.height / 2.0 / f)); return cachedVfov
    }

    private fun onScreenTapped(x: Float, y: Float) {
        triggerAutoFocus(x, y); haptic()
        // AF needs more settling time than depth-based modes
        val settleMs = if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) 600L else 800L
        if (calibrating) {
            backgroundHandler?.postDelayed({ runOnUiThread { performCalibration(x, y) } }, settleMs)
            return
        }
        backgroundHandler?.postDelayed({ runOnUiThread { measureAtPoint(x, y) } }, settleMs)
    }

    private fun onSweepMoved(x: Float, y: Float) {
        backgroundHandler?.post {
            val dist = getDistanceAt(x, y)
            runOnUiThread {
                if (dist != null && dist > 0) {
                    binding.overlayView.sweepDistanceCm = dist
                    synchronized(sweepLock) {
                        sweepHistory.add(Pair(x, dist))
                        if (sweepHistory.size > maxSweepHistory) {
                            sweepHistory.removeAt(0)
                            sweepHistory.removeAt(0)
                        }
                        binding.overlayView.sweepHistory = sweepHistory.toList()
                    }
                    binding.tvDistance.text = formatDistance(dist)
                } else { binding.overlayView.sweepDistanceCm = -1f; binding.tvDistance.text = "扫描中..." }
                binding.overlayView.invalidate()
            }
        }
    }

    private fun triggerAutoFocus(sx: Float, sy: Float) {
        val session = cameraCtrl.captureSession ?: return; val device = cameraCtrl.cameraDevice ?: return
        val sr = cameraCtrl.rgbSensorActiveArray ?: return; val sv = binding.surfaceView
        val fx = (sx * sr.width() / sv.width).toInt().coerceIn(0, sr.width()-1)
        val fy = (sy * sr.height() / sv.height).toInt().coerceIn(0, sr.height()-1)
        val rs = (sr.width() * 0.1f).toInt().coerceAtLeast(50)
        val region = MeteringRectangle((fx-rs/2).coerceIn(0,sr.width()-rs), (fy-rs/2).coerceIn(0,sr.height()-rs), rs, rs, MeteringRectangle.METERING_WEIGHT_MAX)
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(sv.holder.surface)
            // Fix #3: also include depth surface in AF request when depth is active on same camera
            if (cameraCtrl.depthStreamActive && cameraCtrl.depthReader != null) {
                req.addTarget(cameraCtrl.depthReader!!.surface)
            }
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (fd != null && fd > 0) currentFocusDistance = 1f / fd
                    try {
                        val res = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); res.addTarget(sv.holder.surface)
                        // Fix #3: restore depth surface in repeating request
                        if (cameraCtrl.depthStreamActive && cameraCtrl.depthReader != null) {
                            res.addTarget(cameraCtrl.depthReader!!.surface)
                        }
                        res.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(res.build(), cameraCtrl.captureCallback, backgroundHandler)
                    } catch (_: Exception) {}
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "AF fail", e) }
    }

    private fun haptic() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun measureAtPoint(x: Float, y: Float) {
        when (currentMode) {
            Mode.POINT -> handlePointTap(x, y)
            Mode.LINE -> handleLineTap(x, y)
            Mode.AREA -> handleAreaTap(x, y)
            Mode.SWEEP -> {}
        }
    }

    private fun handlePointTap(x: Float, y: Float) {
        overlayPoints.clear(); overlayPoints.add(PointF(x, y))
        binding.tvDistance.text = "采样中(0/${AppConstants.DEPTH_SAMPLE_COUNT})..."
        updateOverlay()
        collectDepthSamples(x, y, onProgress = { cur, total ->
            runOnUiThread { binding.tvDistance.text = "采样中($cur/$total)..." }
        }) { result ->
            val dist = result?.depthCm
            if (dist != null && dist > 0) lastRawCm = dist
            val unc = result?.uncertaintyCm ?: 0f
            val uncStr = if (unc > 0.5f) "  ±${String.format("%.1f", unc)}cm" else ""
            runOnUiThread {
                measuredResult = when {
                    dist != null -> formatDistance(dist) + uncStr
                    tofHelper.tofDistanceMm > 0 -> "~${formatDistance(tofHelper.tofDistanceMm / 10f)} (近)"
                    else -> "对焦中..."
                }
                binding.tvDistance.text = measuredResult; updateOverlay()
                if (dist != null && dist > 0) saveToHistory(formatDistance(dist), "单点")
            }
        }
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            firstPoint = PointF(x, y)
            overlayPoints.clear(); overlayPoints.add(PointF(x, y))
            // Apple-style: pulse + placement state
            binding.overlayView.placingSecondPoint = true
            binding.overlayView.triggerPulse(x, y)
            binding.tvDistance.text = "采样中(1/2)..."
            updateOverlay()
            // Mark IMU snapshot for motion detection
            if (imuHelper.isAvailable()) imuHelper.markPoint()
            // Multi-sample depth for first point — async with progress
            collectDepthSamples(x, y, onProgress = { cur, total ->
                runOnUiThread { binding.tvDistance.text = "采样中(1/2) $cur/$total..." }
            }) { result ->
                firstDistance = result?.depthCm ?: 0f
                firstUncertainty = result?.uncertaintyCm ?: 0f
                runOnUiThread { binding.tvDistance.text = "标记第二点..." }
            }
        } else {
            val p1 = firstPoint!!; val p2 = PointF(x, y); overlayPoints.add(p2)
            // Apple-style: pulse + clear placement state
            binding.overlayView.placingSecondPoint = false
            binding.overlayView.liveCrosshair = null
            binding.overlayView.triggerPulse(x, y)
            // Check IMU motion between two taps
            val imuAvail = imuHelper.isAvailable()
            val motion = if (imuAvail) imuHelper.checkMotionSince() else null
            val motionWarn = motion?.warning
            binding.tvDistance.text = "采样中(2/2)..."
            updateOverlay()
            // Multi-sample depth for second point — async
            val d1 = firstDistance
            val u1 = firstUncertainty
            val motionFactor = when {
                !imuAvail -> 1f
                motion != null -> when {
                    motion.excessive -> 0.7f
                    motion.rotationDeg > ImuFusionHelper.ROTATION_NOISE_FLOOR_DEG * 3 -> 0.9f
                    else -> 1f
                }
                else -> 1f
            }
            val d1Corr = d1 * motionFactor
            collectDepthSamples(x, y, onProgress = { cur, total ->
                runOnUiThread { binding.tvDistance.text = "采样中(2/2) $cur/$total..." }
            }) { result2 ->
                val d2 = (result2?.depthCm ?: d1Corr) * motionFactor
                val u2 = result2?.uncertaintyCm ?: 0f

                // #3: Depth consistency check
                val depthRatio = if (d1Corr > 0 && d2 > 0) maxOf(d1Corr, d2) / minOf(d1Corr, d2) else 1f
                val consistencyWarn = if (depthRatio > AppConstants.DEPTH_CONSISTENCY_MAX_RATIO)
                    "⚠️ 两点平面差距大(${String.format("%.1f", depthRatio)}x)" else null

                val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
                val intrinsics = cameraCtrl.intrinsicCalibration
                val dist = if (intrinsics != null && intrinsics.size >= 4) {
                    val arr = cameraCtrl.rgbSensorActiveArray
                    val imgW = arr?.width() ?: vw.toInt()
                    val imgH = arr?.height() ?: vh.toInt()
                    MeasurementEngine.compute3DDistanceIntrinsic(p1.x, p1.y, p2.x, p2.y, d1Corr, d2, vw, vh, intrinsics, imgW, imgH)
                } else {
                    MeasurementEngine.compute3DDistance(p1.x, p1.y, p2.x, p2.y, d1Corr, d2, vw, vh, getHfovDegrees(), getVfovDegrees())
                }
                val totalUnc = sqrt(u1 * u1 + u2 * u2)
                runOnUiThread {
                    measuredResult = formatDistance(dist)
                    val uncStr = if (totalUnc > 0.5f) "  ±${String.format("%.1f", totalUnc)}cm" else ""
                    val displayText = listOfNotNull(measuredResult + uncStr, motionWarn, consistencyWarn).joinToString("  ")
                    binding.tvDistance.text = displayText
                    binding.overlayView.lines = listOf(Pair(p1, p2)); binding.overlayView.showLineLabels = true
                    binding.overlayView.lineDistanceLabels = listOf(displayText)
                    updateOverlay(); firstPoint = null
                    saveToHistory(measuredResult, "两点")
                }
            }
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))
        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints, depthCache)
            measuredResult = if (area > 0) formatArea(area) else "无法计算"
            binding.tvDistance.text = measuredResult
            if (area > 0) saveToHistory(measuredResult, "面积")
        }
        else binding.tvDistance.text = "继续点击 (${overlayAreaPoints.size}/3+)"
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i+1]))
        if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
        binding.overlayView.points = overlayAreaPoints.toList(); binding.overlayView.areaPoints = overlayAreaPoints.toList(); binding.overlayView.invalidate()
    }

    private fun updateOverlay() {
        binding.overlayView.points = overlayPoints.toList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun computeArea(pts: List<PointF>, depthsCache: MutableMap<Int, Float>? = null): Float {
        if (pts.size < 3) return 0f
        val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
        if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) {
            // Use cached depths where available, only measure new points
            val depths = pts.indices.map { i ->
                depthsCache?.get(i) ?: run {
                    val d = getDistanceAt(pts[i].x, pts[i].y)
                    if (d != null && d > 0) { depthsCache?.put(i, d); d } else null
                }
            }
            if (depths.all { it != null && it > 0 }) {
                val intrinsics = cameraCtrl.intrinsicCalibration
                val pts3d = pts.zip(depths).map { (p, d) ->
                    if (intrinsics != null && intrinsics.size >= 4) {
                        // Intrinsic projection: more accurate
                        val arr = cameraCtrl.rgbSensorActiveArray
                        val imgW = arr?.width() ?: vw.toInt()
                        val imgH = arr?.height() ?: vh.toInt()
                        val ix = p.x / vw * imgW; val iy = p.y / vh * imgH
                        val px = d!! * (ix - intrinsics[2]) / intrinsics[0]
                        val py = d * (iy - intrinsics[3]) / intrinsics[1]
                        Triple(px, py, d)
                    } else {
                        // FOV fallback
                        val nx = (p.x/vw - 0.5f)*2f; val ny = (0.5f - p.y/vh)*2f
                        val px = d!! * Math.tan(nx * Math.toRadians(getHfovDegrees()) / 2).toFloat()
                        val py = d * Math.tan(ny * Math.toRadians(getVfovDegrees()) / 2).toFloat()
                        Triple(px, py, d)
                    }
                }
                return MeasurementEngine.computePolygonArea3D(pts3d)
            }
        }
        val cx = pts.map{it.x}.average().toFloat()
        val cy = pts.map{it.y}.average().toFloat()
        val avg = getDistanceAt(cx, cy) ?: return 0f
        return MeasurementEngine.computeFlatArea(pts.map{it.x}.toFloatArray(), pts.map{it.y}.toFloatArray(), avg, vw, getHfovDegrees(), vh, getVfovDegrees())
    }

    private fun setupUI() {
        binding.tvSensor.text = "${if (tofHelper.hasRealTof) "ToF" else "AF"} ${tofHelper.sensorLabel}" + if (imuHelper.isAvailable()) " +IMU" else ""
        binding.btnPointMode.setOnClickListener { setMode(Mode.POINT) }
        binding.btnLineMode.setOnClickListener { setMode(Mode.LINE) }
        binding.btnAreaMode.setOnClickListener { setMode(Mode.AREA) }
        binding.btnSweepMode.setOnClickListener { setMode(Mode.SWEEP) }
        binding.btnDepthToggle.setOnClickListener { toggleDepthCamera() }
        binding.btnUndo.setOnClickListener { undoLastPoint() }
        binding.btnCalibrate.setOnClickListener { startCalibration() }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.tvDistance.setOnLongClickListener { cycleUnit(); true }
        binding.btnReset.setOnClickListener { resetMeasurement() }
        binding.btnSave.setOnClickListener { saveMeasurement() }
        setMode(Mode.POINT)
        updateDepthToggleButton()
    }

    private fun setMode(mode: Mode) {
        currentMode = mode; calibrating = false; resetMeasurement()
        binding.tvMode.text = when (mode) {
            Mode.POINT -> "点击测距"
            Mode.LINE -> "两点测距"
            Mode.AREA -> "面积测量"
            Mode.SWEEP -> "扫掠测距"
        }
        binding.overlayView.sweepMode = (mode == Mode.SWEEP)
        listOf(binding.btnPointMode, binding.btnLineMode, binding.btnAreaMode, binding.btnSweepMode).forEach { it.setBackgroundColor(0x00000000) }
        when (mode) {
            Mode.POINT -> binding.btnPointMode
            Mode.LINE -> binding.btnLineMode
            Mode.AREA -> binding.btnAreaMode
            Mode.SWEEP -> binding.btnSweepMode
        }.setBackgroundColor(0x3300FF88.toInt())
    }

    private fun resetMeasurement() {
        overlayPoints.clear(); overlayAreaPoints.clear(); sweepHistory.clear(); firstPoint = null; depthCache.clear()
        calibrating = false; lastRawCm = -1f; firstUncertainty = 0f
        measuredResult = "--"; binding.tvDistance.text = "--"
        binding.overlayView.points = emptyList()
        binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.showLineLabels = false
        binding.overlayView.sweepDistanceCm = -1f
        binding.overlayView.sweepHistory = emptyList()
        binding.overlayView.lineDistanceLabels = emptyList()
        binding.overlayView.placingSecondPoint = false
        binding.overlayView.liveCrosshair = null
        // Reset filter states so next measurement starts fresh (not from old Kalman/ToF momentum)
        depthFilter.reset()
        tofHelper.reset()
        if (imuHelper.isAvailable()) imuHelper.reset()
        currentFocusDistance = -1f
        depthBuffer = null
        updateCalibrationUI()
        binding.overlayView.postInvalidate()
    }

    private fun saveMeasurement() {
        if (measuredResult == "--" || measuredResult.contains("无")) {
            Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sv = binding.surfaceView; val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); sv.getLocationInWindow(loc)
                android.view.PixelCopy.request(window, Rect(loc[0], loc[1], loc[0]+sv.width, loc[1]+sv.height), bitmap,
                    { r -> if (r == android.view.PixelCopy.SUCCESS) { val c = Canvas(bitmap); binding.overlayView.draw(c); saveBitmapToGallery(bitmap) } }, Handler(mainLooper)) }
            else {
                val c = Canvas(bitmap); c.drawColor(Color.BLACK); binding.overlayView.draw(c)
                saveBitmapToGallery(bitmap)
            }
        } catch (e: Exception) { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val displayName = "ARMeasure_${System.currentTimeMillis()}"
            val mimeType = "image/png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/ARMeasure")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.insertImage(contentResolver, bitmap, displayName, "Distance: $measuredResult")
                runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun undoLastPoint() {
        when (currentMode) {
            Mode.AREA -> {
                if (overlayAreaPoints.isNotEmpty()) {
                    overlayAreaPoints.removeAt(overlayAreaPoints.size - 1)
                    depthCache.remove(overlayAreaPoints.size)
                    if (overlayAreaPoints.size >= 3) {
                        val a = computeArea(overlayAreaPoints, depthCache)
                        measuredResult = if (a > 0) formatArea(a) else "无法计算"
                        binding.tvDistance.text = measuredResult
                    } else {
                        measuredResult = if (overlayAreaPoints.isEmpty()) "--" else "继续点击 (${overlayAreaPoints.size}/3+)"
                        binding.tvDistance.text = measuredResult
                    }
                    val lines = mutableListOf<Pair<PointF, PointF>>()
                    for (i in 0 until overlayAreaPoints.size - 1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i + 1]))
                    if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
                    binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
                    binding.overlayView.points = overlayAreaPoints.toList()
                    binding.overlayView.areaPoints = overlayAreaPoints.toList()
                    binding.overlayView.invalidate()
                }
            }
            Mode.LINE -> {
                firstPoint = null
                firstUncertainty = 0f
                overlayPoints.clear()
                binding.tvDistance.text = "--"
                updateOverlay()
            }
            else -> resetMeasurement()
        }
    }

    private var lastRawCm: Float = -1f  // store last raw cm value for unit re-display

    private fun cycleUnit() {
        currentUnit = when (currentUnit) { MeasureUnit.CM -> MeasureUnit.INCH; MeasureUnit.INCH -> MeasureUnit.M; MeasureUnit.M -> MeasureUnit.CM }
        val label = when (currentUnit) { MeasureUnit.CM -> "厘米"; MeasureUnit.INCH -> "英寸"; MeasureUnit.M -> "米" }
        Toast.makeText(this, "单位: $label", Toast.LENGTH_SHORT).show()
        if (lastRawCm > 0 && currentMode == Mode.POINT) {
            measuredResult = formatDistance(lastRawCm)
            binding.tvDistance.text = measuredResult
        }
    }

    // ── Calibration ──────────────────────────────────────────

    private fun startCalibration() {
        if (calibrating) { calibrating = false; updateCalibrationUI(); return }
        calibrating = true
        binding.tvDistance.text = "对准目标后点击屏幕"
        binding.tvMode.text = "校准模式"
        updateCalibrationUI()
    }

    private fun performCalibration(sx: Float, sy: Float) {
        backgroundHandler?.post {
            // Multi-sample averaging for more stable calibration
            val samples = mutableListOf<Float>()
            for (i in 0 until 5) {
                val d = getDistanceAt(sx, sy)
                if (d != null && d > 0) samples.add(d)
                Thread.sleep(150)
            }
            val measured = if (samples.isNotEmpty()) samples.sorted()[samples.size / 2] else null  // median
            runOnUiThread {
                if (measured == null || measured <= 0) {
                    Toast.makeText(this, "无法获取距离，请重试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // Ask user for known distance
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    hint = "已知距离 (cm)"
                    setText(String.format("%.0f", measured))
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("校准: 当前测量 ${String.format("%.1f cm", measured)}")
                    .setMessage("输入实际距离 (cm) 进行校准\n(已取5次采样中位数)")
                    .setView(input)
                    .setPositiveButton("校准") { _, _ ->
                        val known = input.text.toString().toFloatOrNull()
                        if (known != null && known > 0) {
                            calibrationFactor = known / measured
                            isCalibrated = true
                            calibrating = false
                            saveCalibration()
                            updateCalibrationUI()
                            setMode(currentMode) // restore mode display
                            Toast.makeText(this, "校准完成 (x${String.format("%.3f", calibrationFactor)})", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消") { _, _ ->
                        calibrating = false
                        updateCalibrationUI()
                        setMode(currentMode)
                    }
                    .show()
            }
        }
    }

    private fun loadCalibration() {
        val prefs = getSharedPreferences("armeasure_cal", MODE_PRIVATE)
        calibrationFactor = prefs.getFloat("factor", 1.0f)
        isCalibrated = prefs.getBoolean("calibrated", false)
        updateCalibrationUI()
    }

    private fun saveCalibration() {
        getSharedPreferences("armeasure_cal", MODE_PRIVATE).edit()
            .putFloat("factor", calibrationFactor)
            .putBoolean("calibrated", isCalibrated)
            .apply()
    }

    private fun updateCalibrationUI() {
        if (isCalibrated) {
            binding.tvCalibration.text = "x${String.format("%.3f", calibrationFactor)}"
            binding.tvCalibration.setTextColor(Color.parseColor("#00FF88"))
        } else {
            binding.tvCalibration.text = ""
        }
        binding.btnCalibrate.setBackgroundColor(if (calibrating) 0x33FFCC00.toInt() else 0x00000000)
    }

    // ── History ──────────────────────────────────────────────

    private fun saveToHistory(result: String, modeStr: String) {
        val entry = HistoryEntry(System.currentTimeMillis(), modeStr, result, when(currentUnit) {
            MeasureUnit.CM -> "cm"; MeasureUnit.INCH -> "in"; MeasureUnit.M -> "m"
        })
        measurementHistory.add(0, entry)
        if (measurementHistory.size > 50) measurementHistory.removeAt(measurementHistory.size - 1)
        persistHistory()
    }

    private fun persistHistory() {
        val json = jsonArrayOf(measurementHistory.map {
            jsonObjectOf("t" to it.timestamp, "m" to it.mode, "r" to it.result, "u" to it.unit)
        })
        historyPrefs.edit().putString("data", json.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val raw = historyPrefs.getString("data", "[]") ?: "[]"
            val arr = org.json.JSONArray(raw)
            measurementHistory.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                measurementHistory.add(HistoryEntry(o.getLong("t"), o.getString("m"), o.getString("r"), o.getString("u")))
            }
        } catch (_: Exception) { measurementHistory.clear() }
    }

    private fun showHistory() {
        if (measurementHistory.isEmpty()) {
            Toast.makeText(this, "暂无测量记录", Toast.LENGTH_SHORT).show()
            return
        }
        val items = measurementHistory.map { e ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.timestamp))
            "$time  [${e.mode}]  ${e.result}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("测量记录 (${measurementHistory.size})")
            .setItems(items, null)
            .setNeutralButton("清空") { _, _ ->
                measurementHistory.clear(); persistHistory()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ── Helpers for JSON ─────────────────────────────────────

    private fun jsonObjectOf(vararg pairs: Pair<String, Any>): org.json.JSONObject {
        val obj = org.json.JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj
    }

    private fun jsonArrayOf(items: List<org.json.JSONObject>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it) }
        return arr
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (cameraCtrl.cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        // Only set up preview if camera is open BUT no capture session yet
        // (camera opened before surface was ready). Skip if session already exists
        // to avoid destroying/recreating it (which causes preview flicker).
        else if (cameraCtrl.cameraDevice != null && cameraCtrl.captureSession == null) {
            cameraCtrl.setupPreviewSession(cameraCtrl.cameraDevice!!, cameraCtrl.depthCameraEnabled && cameraCtrl.depthStreamActive)
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private var depthBufReusable: ShortArray? = null

    private fun processDepthImage(reader: android.media.ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]; val buffer = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride; val w = image.width; val h = image.height
            synchronized(depthLock) {
                val buf = depthBufReusable?.let { if (it.size == w * h) it else null } ?: ShortArray(w * h).also { depthBufReusable = it }
                for (row in 0 until h) {
                    val rowStart = row * rs
                    for (col in 0 until w) {
                        val bi = rowStart + col * ps
                        if (bi + 1 < buffer.capacity()) buf[row * w + col] = buffer.getShort(bi)
                    }
                }
                depthBuffer = buf; depthWidth = w; depthHeight = h
            }
        } catch (e: Exception) { Log.e(TAG, "depth err", e) } finally { image.close() }
    }

    private fun getDepthAtScreenPoint(sx: Float, sy: Float, filter: DistanceFilter = depthFilter): Float? {
        synchronized(depthLock) {
            val buf = depthBuffer ?: return null
            if (buf.isEmpty() || depthWidth <= 0 || depthHeight <= 0) return null
            val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
            if (vw <= 0 || vh <= 0) return null
            val dx: Int; val dy: Int
            val sep = cameraCtrl.depthCameraDevice?.id != cameraCtrl.cameraDevice?.id
            if (sep && cameraCtrl.depthSensorActiveArray != null && cameraCtrl.rgbSensorActiveArray != null) {
                val rgb = cameraCtrl.rgbSensorActiveArray!!; val dep = cameraCtrl.depthSensorActiveArray!!
                dx = (sx / vw * dep.width()).toInt().coerceIn(0, depthWidth - 1)
                dy = (sy / vh * dep.height()).toInt().coerceIn(0, depthHeight - 1)
            } else {
                dx = (sx / vw * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                dy = (sy / vh * depthHeight).toInt().coerceIn(0, depthHeight - 1)
            }

            // #5: Adaptive neighborhood — depth-aware kernel size
            // Near (<1m): 3×3 to avoid contamination from neighboring objects
            // Mid (1-3m): 5×5 for noise averaging
            // Far (>3m): 7×7 for heavy noise
            // Also adapt by resolution: low-res depth maps use smaller kernel
            val radius = when {
                depthWidth <= 240 || depthHeight <= 180 -> 1  // always 3×3 for low-res
                else -> 2  // 5×5 default, refined below
            }

            // First pass: quick center depth estimate for adaptive kernel
            val centerRaw = buf[dy * depthWidth + dx].toInt() and 0xFFFF
            val centerCm = if (centerRaw in 1..65533) centerRaw / 10f else 0f
            val adaptRadius = when {
                centerCm > 0 && centerCm < AppConstants.NEIGHBORHOOD_NEAR_CM -> AppConstants.NEIGHBORHOOD_RADIUS_NEAR
                centerCm >= AppConstants.NEIGHBORHOOD_FAR_CM -> AppConstants.NEIGHBORHOOD_RADIUS_FAR
                else -> radius  // use resolution-based default
            }

            var wSum = 0.0; var wtSum = 0.0; var cnt = 0
            for (ddy in -adaptRadius..adaptRadius) for (ddx in -adaptRadius..adaptRadius) {
                val px = (dx+ddx).coerceIn(0, depthWidth-1); val py = (dy+ddy).coerceIn(0, depthHeight-1)
                val raw = buf[py*depthWidth+px].toInt() and 0xFFFF
                if (raw in 1..65533) {
                    val d2 = (ddx * ddx + ddy * ddy).toFloat()
                    val w = 1f / (1f + d2)
                    wSum += raw * w
                    wtSum += w
                    cnt++
                }
            }
            if (cnt < 3) return null
            val filtered = filter.filter((wSum / wtSum).toFloat())
            return if (filtered > 0) filtered / 10f else null
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) {
            backgroundThread?.quitSafely()
            try { backgroundThread?.join(1000) } catch (_: Exception) {}
        }
        backgroundThread = HandlerThread("CameraBG").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper)
    }
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely(); try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (_: Exception) {}
    }
}


