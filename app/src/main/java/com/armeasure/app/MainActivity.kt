package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import com.armeasure.app.databinding.ActivityMainBinding
import java.io.File
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraCtrl: CameraController
    private lateinit var tofHelper: TofSensorHelper
    private lateinit var imuHelper: ImuFusionHelper

    @Volatile private var depthBuffer: ShortArray? = null
    @Volatile private var depthWidth: Int = 0
    @Volatile private var depthHeight: Int = 0
    private val depthLock = Any()
    private val depthFilter = DistanceFilter(windowSize = 5, alpha = 0.4f, maxJumpMm = 2000f, maxRangeMm = 5000f, processNoise = 300f, initMeasureNoise = 300f)

    // Multi-frame temporal smoothing
    private val temporalLock = Any()
    private val temporalFrames = ArrayDeque<ShortArray>(AppConstants.TEMPORAL_FRAME_COUNT)

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    @Volatile private var currentFocusDistance: Float = -1f

    private enum class Mode { POINT, LINE, AREA, SWEEP }
    private enum class MeasureUnit { CM, INCH, M }
    private var currentUnit = MeasureUnit.CM

    private var calibrationFactor = 1.0f
    private var isCalibrated = false
    private var calibrating = false
    /** Manual reference depth override (cm). 0 = disabled, use sensor. */
    private var manualDepthCm: Float = 0f

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
    /** Apple-like: stored 3D world coordinates of first point (camera frame at tap time) */
    private var firstWorld3D: Triple<Float, Float, Float>? = null
    /** Apple-like: stored 3D world coordinates of second point */
    private var secondWorld3D: Triple<Float, Float, Float>? = null
    /** Line mode preview loop: samples depth at screen center, updates live distance */
    private val linePreviewRunnable = object : Runnable {
        override fun run() {
            if (!binding.overlayView.placingSecondPoint || firstWorld3D == null) return
            val w1 = firstWorld3D!!
            val cx = cachedViewWidth / 2f; val cy = cachedViewHeight / 2f
            val d2 = getDistanceAt(cx, cy, skipImu = true)
            if (d2 != null && d2 > 0) {
                val w2 = screenToWorld3D(cx, cy, d2)
                val (dp, dr) = if (imuHelper.isAvailable()) imuHelper.getRotationDeltaRad() else Pair(0f, 0f)
                val dist = if (Math.abs(dp) > 0.005f || Math.abs(dr) > 0.005f) {
                    val (rx, ry, rz) = MeasurementEngine.rotatePoint(w1.first, w1.second, w1.third, dp, dr)
                    MeasurementEngine.distance3D(Triple(rx, ry, rz), w2)
                } else {
                    MeasurementEngine.distance3D(w1, w2)
                }
                val projP1 = if (Math.abs(dp) > 0.005f || Math.abs(dr) > 0.005f) {
                    val (rx, ry, rz) = MeasurementEngine.rotatePoint(w1.first, w1.second, w1.third, dp, dr)
                    world3DToScreen(rx, ry, rz)
                } else {
                    world3DToScreen(w1.first, w1.second, w1.third)
                }
                val isLevel = imuHelper.isAvailable() && Math.abs(Math.toDegrees(imuHelper.tiltAngle.toDouble())) < 3.0
                runOnUiThread {
                    binding.overlayView.liveDistanceCm = dist
                    binding.overlayView.secondPointDepthCm = d2
                    binding.overlayView.firstPointDepthCm = firstDistance
                    binding.overlayView.deviceIsLevel = isLevel
                    overlayPoints.clear()
                    overlayPoints.add(PointF(projP1.first, projP1.second))
                    binding.overlayView.points = overlayPoints.toList()
                    binding.tvDistance.text = formatDistance(dist)
                    binding.overlayView.invalidate()
                }
            }
            backgroundHandler?.postDelayed(this, 100) // 10 FPS preview
        }
    }
    private var measuredResult = "--"
    private val sweepHistory = mutableListOf<Pair<Float, Float>>()
    private val sweepLock = Any()
    private val depthCache = mutableMapOf<Int, Float>()
    private val depthCacheLock = Any()
    private val maxSweepHistory = 200

    @Volatile private var cachedViewWidth: Float = 0f
    @Volatile private var cachedViewHeight: Float = 0f

    private var lastTapTime = 0L
    private var lastModeClickTime = 0L
    private var lastUndoClickTime = 0L

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { File(getExternalFilesDir(null), "crash.log").run { if (length() > 512*1024) delete(); appendText("${java.util.Date()} ${e.stackTraceToString()}\n\n") } } catch (_: Exception) {}
            throw e
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        startBackgroundThread()
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        cameraCtrl = CameraController(this, cm, backgroundHandler, binding.surfaceView)
        tofHelper = TofSensorHelper(sm)
        imuHelper = ImuFusionHelper(sm)
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }
        // Line mode: no finger tracking — second point is at screen center (Apple style)
        binding.overlayView.onMove = { x, y ->
            if (binding.overlayView.placingSecondPoint) {
                // Just update crosshair visual, don't compute distance from finger
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
        // Show tutorial on first launch
        val tutPrefs = getSharedPreferences(AppConstants.TUTORIAL_PREF, MODE_PRIVATE)
        if (!tutPrefs.getBoolean(AppConstants.TUTORIAL_SHOWN_KEY, false)) {
            binding.overlayView.showTutorial = true
            binding.overlayView.invalidate()
            tutPrefs.edit().putBoolean(AppConstants.TUTORIAL_SHOWN_KEY, true).apply()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CAMERA) {
            if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要相机权限才能使用测距功能", Toast.LENGTH_LONG).show()
                binding.tvSensor.text = "❌ 需要相机权限"
                binding.tvDistance.text = "请授予相机权限后重启应用"
            }
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
        depthBuffer = null; depthBufReusable = null
        calibrating = false
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) { tofHelper.onSensorEvent(event) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Haptic feedback ──────────────────────────────────────

    private fun haptic() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(AppConstants.HAPTIC_TAP_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun hapticComplete() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            // Double pulse for measurement complete
            val timings = longArrayOf(0, AppConstants.HAPTIC_COMPLETE_MS, AppConstants.HAPTIC_COMPLETE_GAP_MS, AppConstants.HAPTIC_COMPLETE_MS)
            val amps = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
            v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } catch (_: Exception) {}
    }

    private fun hapticWarning() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(AppConstants.HAPTIC_WARNING_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    // ── Camera ───────────────────────────────────────────────

    private fun startCamera() {
        if (cameraOpening || cameraCtrl.cameraDevice != null) return
        cameraOpening = true
        cachedHfov = Double.NaN; cachedVfov = Double.NaN
        val selection = cameraCtrl.selectCameras() ?: run { binding.tvSensor.text = "无可用摄像头"; cameraOpening = false; return }
        cameraCtrl.onDepthImageAvailable = { reader -> processDepthImage(reader) }
        cameraCtrl.captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (fd != null && fd > 0) {
                    val newFocus = 1f / fd
                    if (currentFocusDistance > 0 && Math.abs(newFocus - currentFocusDistance) > AppConstants.FOCUS_RESET_THRESHOLD) {
                        depthFilter.reset()
                        synchronized(temporalLock) { temporalFrames.clear() }
                        Log.d(TAG, "Focus changed: ${currentFocusDistance}→$newFocus, filter reset")
                    }
                    currentFocusDistance = newFocus
                }
            }
        }
        cameraCtrl.openCamera(selection, onReady = { same ->
            cameraOpening = false
            updateSensorLabel()
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
        binding.btnDepthToggle.setBackgroundColor(if (enabled) 0x3300FF88.toInt() else 0x00000000)
    }

    // ── Measurement ──────────────────────────────────────────

    private fun getDistanceAt(sx: Float, sy: Float, depthFilterOverride: DistanceFilter? = null, skipImu: Boolean = false): Float? {
        // Manual reference depth override (for ToF-only devices)
        if (manualDepthCm > 0) {
            var raw = manualDepthCm
            if (isCalibrated) raw *= calibrationFactor
            if (!skipImu && imuHelper.isAvailable()) {
                val vw = cachedViewWidth; val vh = cachedViewHeight
                return if (vw > 0 && vh > 0) raw * imuHelper.getCorrectionFactor(sx, sy, vw, vh, raw) else imuHelper.compensateDepth(raw)
            }
            return raw
        }
        // Priority: depth map (spatial) > AF focus distance > ToF (center-only, no spatial info)
        var raw: Float? = null
        if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) {
            raw = getDepthAtScreenPoint(sx, sy, depthFilterOverride ?: depthFilter)
        }
        if (raw == null || raw <= 0) {
            // No depth map — try AF focus distance (varies with focus point)
            val d = currentFocusDistance
            if (d > 0) raw = d * 100f
        }
        if (raw == null || raw <= 0) {
            // Last resort: ToF (center-only, no spatial info — only accurate near screen center)
            raw = tofHelper.getDistanceCm()
        }
        if (raw != null && raw > 0 && isCalibrated) raw = raw * calibrationFactor
        if (!skipImu && raw != null && raw > 0 && imuHelper.isAvailable()) {
            val vw = cachedViewWidth; val vh = cachedViewHeight
            return if (vw > 0 && vh > 0) raw * imuHelper.getCorrectionFactor(sx, sy, vw, vh, raw) else imuHelper.compensateDepth(raw)
        }
        return raw
    }

    data class DepthResult(val depthCm: Float, val uncertaintyCm: Float)

    /** Compute 3D world coordinates from screen position + depth (cm). */
    private fun screenToWorld3D(sx: Float, sy: Float, depthCm: Float): Triple<Float, Float, Float> {
        val vw = cachedViewWidth; val vh = cachedViewHeight
        val intrinsics = cameraCtrl.intrinsicCalibration
        val arr = cameraCtrl.rgbSensorActiveArray
        if (intrinsics != null && intrinsics.size >= 4 && arr != null && vw > 0 && vh > 0) {
            return MeasurementEngine.screenTo3DIntrinsic(sx, sy, depthCm, vw, vh, intrinsics, arr.width(), arr.height())
        }
        return MeasurementEngine.screenTo3DFOV(sx, sy, depthCm, vw, vh, getHfovDegrees(), getVfovDegrees())
    }

    /** Project 3D world coordinates back to screen position. */
    private fun world3DToScreen(wx: Float, wy: Float, wz: Float): Pair<Float, Float> {
        val vw = cachedViewWidth; val vh = cachedViewHeight
        val intrinsics = cameraCtrl.intrinsicCalibration
        val arr = cameraCtrl.rgbSensorActiveArray
        if (intrinsics != null && intrinsics.size >= 4 && arr != null && vw > 0 && vh > 0) {
            return MeasurementEngine.worldToScreenIntrinsic(wx, wy, wz, vw, vh, intrinsics, arr.width(), arr.height())
        }
        return MeasurementEngine.worldToScreenFOV(wx, wy, wz, vw, vh, getHfovDegrees(), getVfovDegrees())
    }

    private fun collectDepthSamples(sx: Float, sy: Float, sampleCount: Int = AppConstants.DEPTH_SAMPLE_COUNT, intervalMs: Long = AppConstants.DEPTH_SAMPLE_INTERVAL_MS, skipImu: Boolean = false, onProgress: ((Int, Int) -> Unit)? = null, onComplete: (DepthResult?) -> Unit) {
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
            val d = getDistanceAt(sx, sy, localFilter, skipImu)
            if (d != null && d > 0) samples.add(d)
            sampleIndex++
            onProgress?.invoke(sampleIndex, sampleCount)
            if (sampleIndex < sampleCount) backgroundHandler?.postDelayed({ nextSample() }, intervalMs)
            else nextSample()
        }
        nextSample()
    }

    private var cachedHfov: Double = Double.NaN
    private var cachedVfov: Double = Double.NaN

    private fun convertUnit(cm: Float): Pair<Float, String> = when (currentUnit) {
        MeasureUnit.CM -> Pair(cm, "cm"); MeasureUnit.INCH -> Pair(cm / 2.54f, "in"); MeasureUnit.M -> Pair(cm / 100f, "m")
    }
    private fun formatDistance(cm: Float): String { val (v, u) = convertUnit(cm); return String.format("%.1f %s", v, u) }
    private fun formatArea(cm2: Float): String {
        val (v, u) = when (currentUnit) {
            MeasureUnit.CM -> Pair(cm2, "cm²"); MeasureUnit.INCH -> Pair(cm2 / (2.54f * 2.54f), "in²"); MeasureUnit.M -> Pair(cm2 / 10000f, "m²")
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
        val now = System.currentTimeMillis()
        if (now - lastTapTime < AppConstants.TAP_DEBOUNCE_MS) return
        lastTapTime = now
        // Tap ripple feedback
        binding.overlayView.triggerRipple(x, y)
        triggerAutoFocus(x, y); haptic()
        val settleMs = if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) AppConstants.SETTLE_MS_DEPTH else AppConstants.SETTLE_MS_AF
        if (calibrating) { backgroundHandler?.postDelayed({ runOnUiThread { performCalibration(x, y) } }, settleMs); return }
        backgroundHandler?.postDelayed({ runOnUiThread { measureAtPoint(x, y) } }, settleMs)
    }

    private fun onSweepMoved(x: Float, y: Float) {
        backgroundHandler?.post {
            val dist = getDistanceAt(x, y)
            runOnUiThread {
                if (dist != null && dist > 0) {
                    binding.overlayView.sweepDistanceCm = dist
                    binding.overlayView.updateSweepText(String.format("%.1f cm", dist))
                    synchronized(sweepLock) {
                        sweepHistory.add(Pair(x, dist))
                        while (sweepHistory.size > maxSweepHistory) sweepHistory.removeAt(0)
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
            if (cameraCtrl.depthStreamActive && cameraCtrl.depthReader != null) req.addTarget(cameraCtrl.depthReader!!.surface)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (fd != null && fd > 0) currentFocusDistance = 1f / fd
                    try {
                        val res = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); res.addTarget(sv.holder.surface)
                        if (cameraCtrl.depthStreamActive && cameraCtrl.depthReader != null) res.addTarget(cameraCtrl.depthReader!!.surface)
                        res.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(res.build(), cameraCtrl.captureCallback, backgroundHandler)
                    } catch (_: Exception) {}
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "AF fail", e) }
    }

    private fun measureAtPoint(x: Float, y: Float) {
        // Show loading indicator
        binding.progressBar.visibility = android.view.View.VISIBLE
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
                binding.progressBar.visibility = android.view.View.GONE
                measuredResult = when {
                    dist != null -> formatDistance(dist) + uncStr
                    tofHelper.tofDistanceMm > 0 -> "~${formatDistance(tofHelper.tofDistanceMm / 10f)} (近)"
                    else -> "对焦中..."
                }
                binding.tvDistance.text = measuredResult; updateOverlay()
                if (dist != null && dist > 0) { hapticComplete(); saveToHistory(formatDistance(dist), "单点") }
            }
        }
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            // ── First point: tap to anchor on surface ──
            firstPoint = PointF(x, y)
            overlayPoints.clear(); overlayPoints.add(PointF(x, y))
            binding.overlayView.placingSecondPoint = true
            binding.overlayView.triggerPulse(x, y)
            binding.tvDistance.text = "采样中(1/2)..."
            updateOverlay()
            if (imuHelper.isAvailable()) imuHelper.markPoint()
            collectDepthSamples(x, y, onProgress = { cur, total ->
                runOnUiThread { binding.tvDistance.text = "采样中(1/2) $cur/$total..." }
            }) { result ->
                firstDistance = result?.depthCm ?: 0f
                firstUncertainty = result?.uncertaintyCm ?: 0f
                firstWorld3D = if (firstDistance > 0) screenToWorld3D(x, y, firstDistance) else null
                runOnUiThread {
                    binding.overlayView.firstPointDepthCm = firstDistance
                    binding.tvDistance.text = "移动手机，点击确认"
                }
                // ★ Apple-like: start preview loop — samples depth at SCREEN CENTER
                backgroundHandler?.post(linePreviewRunnable)
            }
        } else {
            // ── Second point: CONFIRM at screen center (Apple style) ──
            backgroundHandler?.removeCallbacks(linePreviewRunnable)
            binding.overlayView.placingSecondPoint = false
            binding.overlayView.triggerPulse(x, y)
            val imuAvail = imuHelper.isAvailable()
            val (deltaPitch, deltaRoll) = if (imuAvail) imuHelper.getRotationDeltaRad() else Pair(0f, 0f)
            val motion = if (imuAvail) imuHelper.checkMotionSince() else null
            if (imuAvail) imuHelper.markPoint()
            val motionWarn = motion?.warning
            if (motionWarn != null) hapticWarning()

            // ★ Second point = screen center, not tap location
            val cx = cachedViewWidth / 2f; val cy = cachedViewHeight / 2f
            val d2 = getDistanceAt(cx, cy, skipImu = true) ?: firstDistance
            val w1 = firstWorld3D
            val w2 = if (d2 > 0) screenToWorld3D(cx, cy, d2) else null
            secondWorld3D = w2

            // Compute FIXED distance from stored 3D coordinates
            val dist = if (w1 != null && w2 != null) {
                if (Math.abs(deltaPitch) > 0.005f || Math.abs(deltaRoll) > 0.005f) {
                    val (rw1x, rw1y, rw1z) = MeasurementEngine.rotatePoint(w1.first, w1.second, w1.third, deltaPitch, deltaRoll)
                    MeasurementEngine.distance3D(Triple(rw1x, rw1y, rw1z), w2)
                } else {
                    MeasurementEngine.distance3D(w1, w2)
                }
            } else if (firstDistance > 0 && d2 > 0) {
                // Fallback
                val vw = cachedViewWidth; val vh = cachedViewHeight
                val intrinsics = cameraCtrl.intrinsicCalibration
                val arr = cameraCtrl.rgbSensorActiveArray
                val imgW = arr?.width() ?: vw.toInt(); val imgH = arr?.height() ?: vh.toInt()
                if (intrinsics != null && intrinsics.size >= 4) {
                    MeasurementEngine.compute3DDistanceIntrinsic(firstPoint!!.x, firstPoint!!.y, cx, cy, firstDistance, d2, vw, vh, intrinsics, imgW, imgH)
                } else {
                    MeasurementEngine.compute3DDistance(firstPoint!!.x, firstPoint!!.y, cx, cy, firstDistance, d2, vw, vh, getHfovDegrees(), getVfovDegrees())
                }
            } else 0f

            // Project first 3D point to current screen position for line display
            val lineP1 = if (w1 != null) {
                if (Math.abs(deltaPitch) > 0.005f || Math.abs(deltaRoll) > 0.005f) {
                    val (rw1x, rw1y, rw1z) = MeasurementEngine.rotatePoint(w1.first, w1.second, w1.third, deltaPitch, deltaRoll)
                    world3DToScreen(rw1x, rw1y, rw1z)
                } else {
                    world3DToScreen(w1.first, w1.second, w1.third)
                }
            } else Pair(firstPoint!!.x, firstPoint!!.y)

            val p1Screen = PointF(lineP1.first, lineP1.second)
            val p2Screen = PointF(cx, cy) // ★ Second point at screen center
            overlayPoints.clear(); overlayPoints.add(p1Screen); overlayPoints.add(p2Screen)

            // Uncertainty
            val vw = cachedViewWidth; val vh = cachedViewHeight
            val intrinsics = cameraCtrl.intrinsicCalibration
            val arr = cameraCtrl.rgbSensorActiveArray
            val imgW = arr?.width() ?: vw.toInt(); val imgH = arr?.height() ?: vh.toInt()
            val rawUnc = if (intrinsics != null && intrinsics.size >= 4) {
                MeasurementEngine.compute3DDistanceUncertaintyIntrinsic(firstPoint!!.x, firstPoint!!.y, cx, cy, firstDistance, d2, firstUncertainty, 0f, vw, vh, intrinsics, imgW, imgH)
            } else {
                MeasurementEngine.compute3DDistanceUncertaintyFOV(firstPoint!!.x, firstPoint!!.y, cx, cy, firstDistance, d2, firstUncertainty, 0f, vw, vh, getHfovDegrees(), getVfovDegrees())
            }
            val motionConfidence = when {
                !imuAvail -> 1f; motion?.excessive == true -> 1.5f
                motion != null && motion.rotationDeg > ImuFusionHelper.ROTATION_NOISE_FLOOR_DEG * 3 -> 1.25f
                else -> 1f
            }
            val totalUnc = rawUnc * motionConfidence

            binding.overlayView.liveDistanceCm = -1f
            binding.overlayView.firstPointDepthCm = -1f
            binding.overlayView.secondPointDepthCm = -1f
            binding.overlayView.deviceIsLevel = false
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                measuredResult = formatDistance(dist)
                val uncStr = if (totalUnc > 0.5f) "  ±${String.format("%.1f", totalUnc)}cm" else ""
                val displayText = listOfNotNull(measuredResult + uncStr, motionWarn).joinToString("  ")
                binding.tvDistance.text = displayText
                binding.overlayView.lines = listOf(Pair(p1Screen, p2Screen)); binding.overlayView.showLineLabels = true
                binding.overlayView.lineDistanceLabels = listOf(displayText)
                updateOverlay(); firstPoint = null
                binding.overlayView.animateLineExpand()
                hapticComplete(); saveToHistory(measuredResult, "两点")
            }
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))
        binding.overlayView.animateNewPoint()
        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints, depthCache)
            measuredResult = if (area > 0) formatArea(area) else "无法计算"
            binding.tvDistance.text = measuredResult
            if (area > 0) saveToHistory(measuredResult, "面积")
        } else binding.tvDistance.text = "继续点击 (${overlayAreaPoints.size}/3+)"
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i+1]))
        if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
        binding.overlayView.points = overlayAreaPoints.toList(); binding.overlayView.areaPoints = overlayAreaPoints.toList(); binding.overlayView.invalidate()
        if (overlayAreaPoints.size >= 3) { binding.progressBar.visibility = android.view.View.GONE; hapticComplete() }
    }

    private fun updateOverlay() {
        binding.overlayView.points = overlayPoints.toList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun computeArea(pts: List<PointF>, depthsCache: MutableMap<Int, Float>? = null): Float {
        if (pts.size < 3) return 0f
        val vw = cachedViewWidth; val vh = cachedViewHeight
        if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) {
            val depths = synchronized(depthCacheLock) {
                pts.indices.map { i ->
                    depthsCache?.get(i) ?: run {
                        val d = getDistanceAt(pts[i].x, pts[i].y)
                        if (d != null && d > 0) { depthsCache?.put(i, d); d } else null
                    }
                }
            }
            if (depths.all { it != null && it > 0 }) {
                val intrinsics = cameraCtrl.intrinsicCalibration
                val pts3d = pts.zip(depths).map { (p, d) ->
                    if (intrinsics != null && intrinsics.size >= 4) {
                        val arr = cameraCtrl.rgbSensorActiveArray
                        val imgW = arr?.width() ?: vw.toInt(); val imgH = arr?.height() ?: vh.toInt()
                        val ix = p.x / vw * imgW; val iy = p.y / vh * imgH
                        Triple(d!! * (ix - intrinsics[2]) / intrinsics[0], d * (iy - intrinsics[3]) / intrinsics[1], d)
                    } else {
                        val clamp = AppConstants.FOV_TAN_CLAMP.toDouble()
                        val nx = (p.x/vw - 0.5f)*2f; val ny = (0.5f - p.y/vh)*2f
                        Triple(d!! * Math.tan(nx.toDouble().coerceIn(-clamp, clamp) * Math.toRadians(getHfovDegrees()) / 2).toFloat(),
                               d * Math.tan(ny.toDouble().coerceIn(-clamp, clamp) * Math.toRadians(getVfovDegrees()) / 2).toFloat(), d)
                    }
                }
                return MeasurementEngine.computePolygonArea3D(pts3d)
            }
        }
        val cx = pts.map{it.x}.average().toFloat(); val cy = pts.map{it.y}.average().toFloat()
        val avg = getDistanceAt(cx, cy) ?: return 0f
        return MeasurementEngine.computeFlatArea(pts.map{it.x}.toFloatArray(), pts.map{it.y}.toFloatArray(), avg, vw, getHfovDegrees(), vh, getVfovDegrees())
    }

    // ── UI Setup ─────────────────────────────────────────────

    private fun setupUI() {
        binding.tvSensor.text = "${if (tofHelper.hasRealTof) "ToF" else "AF"} ${tofHelper.sensorLabel}" + if (imuHelper.isAvailable()) " +IMU" else ""
        binding.btnPointMode.setOnClickListener { setMode(Mode.POINT) }
        binding.btnLineMode.setOnClickListener { setMode(Mode.LINE) }
        binding.btnAreaMode.setOnClickListener { setMode(Mode.AREA) }
        binding.btnSweepMode.setOnClickListener { setMode(Mode.SWEEP) }
        binding.btnDepthToggle.setOnClickListener { toggleDepthCamera() }
        binding.btnUndo.setOnClickListener { undoLastPoint() }
        binding.overlayView.onScale = { sf ->
            val sv = binding.surfaceView; val ns = (sv.scaleX * sf).coerceIn(0.5f, 5f)
            sv.scaleX = ns; sv.scaleY = ns; sv.pivotX = sv.width / 2f; sv.pivotY = sv.height / 2f
        }
        binding.btnCalibrate.setOnClickListener { startCalibration() }
        binding.btnCalibrate.setOnLongClickListener { setManualDepth(); true }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.tvDistance.setOnLongClickListener { cycleUnit(); true }
        binding.btnReset.setOnClickListener { resetMeasurement() }
        binding.btnSave.setOnClickListener { saveMeasurement() }
        setMode(Mode.POINT)
        updateDepthToggleButton()
    }

    private fun setMode(mode: Mode) {
        currentMode = mode; calibrating = false
        binding.tvMode.text = when (mode) {
            Mode.POINT -> "点击测距"; Mode.LINE -> "两点测距"; Mode.AREA -> "面积测量"; Mode.SWEEP -> "扫掠测距"
        }
        binding.overlayView.sweepMode = (mode == Mode.SWEEP)
        val allBtns = listOf(binding.btnPointMode, binding.btnLineMode, binding.btnAreaMode, binding.btnSweepMode)
        allBtns.forEach { it.setBackgroundColor(0x00000000); it.setTextColor(android.graphics.Color.parseColor("#AAAAAA")) }
        val active = when (mode) {
            Mode.POINT -> binding.btnPointMode; Mode.LINE -> binding.btnLineMode
            Mode.AREA -> binding.btnAreaMode; Mode.SWEEP -> binding.btnSweepMode
        }
        active.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        active.setTextColor(android.graphics.Color.WHITE)
    }

    private fun resetMeasurement() {
        overlayPoints.clear(); overlayAreaPoints.clear(); sweepHistory.clear(); firstPoint = null
        backgroundHandler?.removeCallbacks(linePreviewRunnable)
        synchronized(depthCacheLock) { depthCache.clear() }
        calibrating = false; lastRawCm = -1f; firstUncertainty = 0f
        firstWorld3D = null; secondWorld3D = null
        measuredResult = "--"; binding.tvDistance.text = "--"
        binding.progressBar.visibility = android.view.View.GONE
        binding.overlayView.points = emptyList(); binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList(); binding.overlayView.showLineLabels = false
        binding.overlayView.sweepDistanceCm = -1f; binding.overlayView.sweepHistory = emptyList()
        binding.overlayView.lineDistanceLabels = emptyList(); binding.overlayView.placingSecondPoint = false
        binding.overlayView.liveCrosshair = null; binding.overlayView.liveDistanceCm = -1f
        binding.overlayView.firstPointDepthCm = -1f; binding.overlayView.secondPointDepthCm = -1f
        binding.overlayView.deviceIsLevel = false
        depthFilter.reset(); tofHelper.reset()
        if (imuHelper.isAvailable()) imuHelper.reset()
        currentFocusDistance = -1f; depthBuffer = null
        synchronized(temporalLock) { temporalFrames.clear() }
        updateCalibrationUI()
        binding.overlayView.postInvalidate()
    }

    // ── Save & Share ─────────────────────────────────────────

    private fun saveMeasurement() {
        if (measuredResult == "--" || measuredResult.contains("无")) {
            Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val sv = binding.surfaceView; val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); sv.getLocationInWindow(loc)
                android.view.PixelCopy.request(window, Rect(loc[0], loc[1], loc[0]+sv.width, loc[1]+sv.height), bitmap,
                    { r ->
                        if (r == android.view.PixelCopy.SUCCESS) { val c = Canvas(bitmap); binding.overlayView.draw(c); saveBitmapToGallery(bitmap) }
                        else { saveBitmapFallback() }
                    }, Handler(mainLooper))
            } else { saveBitmapFallback() }
        } catch (e: Exception) { saveBitmapFallback() }
    }

    private fun saveBitmapFallback() {
        // Fallback: draw SurfaceView + overlay into a bitmap
        try {
            val sv = binding.surfaceView
            val bitmap = Bitmap.createBitmap(sv.width.coerceAtLeast(1), sv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap); c.drawColor(Color.BLACK)
            binding.overlayView.draw(c)
            saveBitmapToGallery(bitmap)
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val displayName = "ARMeasure_${System.currentTimeMillis()}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/ARMeasure")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    // Share intent
                    runOnUiThread {
                        Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
                        showShareOption(uri)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val path = android.provider.MediaStore.Images.Media.insertImage(contentResolver, bitmap, displayName, "Distance: $measuredResult")
                runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showShareOption(uri: android.net.Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("分享测量结果？")
            .setPositiveButton("分享") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "AR测距结果: $measuredResult")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享测量结果"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ── Undo ─────────────────────────────────────────────────

    private fun undoLastPoint() {
        val now = System.currentTimeMillis()
        if (now - lastUndoClickTime < AppConstants.MODE_DEBOUNCE_MS) return
        lastUndoClickTime = now
        binding.overlayView.animateFadeOut { this.undoLastPointImpl() }
    }
    private fun undoLastPointImpl() {
        when (currentMode) {
            Mode.AREA -> {
                if (overlayAreaPoints.isNotEmpty()) {
                    overlayAreaPoints.removeAt(overlayAreaPoints.size - 1)
                    synchronized(depthCacheLock) { depthCache.remove(overlayAreaPoints.size) }
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
            Mode.LINE -> { backgroundHandler?.removeCallbacks(linePreviewRunnable); firstPoint = null; firstUncertainty = 0f; firstWorld3D = null; secondWorld3D = null; overlayPoints.clear(); binding.tvDistance.text = "--"; binding.overlayView.liveDistanceCm = -1f; binding.overlayView.firstPointDepthCm = -1f; binding.overlayView.secondPointDepthCm = -1f; binding.overlayView.deviceIsLevel = false; updateOverlay() }
            else -> resetMeasurement()
        }
    }

    private var lastRawCm: Float = -1f

    private fun cycleUnit() {
        currentUnit = when (currentUnit) { MeasureUnit.CM -> MeasureUnit.INCH; MeasureUnit.INCH -> MeasureUnit.M; MeasureUnit.M -> MeasureUnit.CM }
        val label = when (currentUnit) { MeasureUnit.CM -> "厘米"; MeasureUnit.INCH -> "英寸"; MeasureUnit.M -> "米" }
        Toast.makeText(this, "单位: $label", Toast.LENGTH_SHORT).show()
        if (lastRawCm > 0 && currentMode == Mode.POINT) { measuredResult = formatDistance(lastRawCm); binding.tvDistance.text = measuredResult }
    }

    // ── Calibration ──────────────────────────────────────────

    private fun startCalibration() {
        if (calibrating) { calibrating = false; updateCalibrationUI(); return }
        calibrating = true; binding.tvDistance.text = "对准目标后点击屏幕"; binding.tvMode.text = "校准模式"
        updateCalibrationUI()
    }

    private fun performCalibration(sx: Float, sy: Float) {
        // Use same pipeline as two-point: collectDepthSamples with local filter, skip IMU correction
        collectDepthSamples(sx, sy,
            sampleCount = AppConstants.CALIBRATION_SAMPLE_COUNT,
            intervalMs = AppConstants.CALIBRATION_SAMPLE_INTERVAL_MS,
            skipImu = true
        ) { result ->
            val measured = result?.depthCm
            runOnUiThread {
                if (measured == null || measured <= 0) { Toast.makeText(this, "无法获取距离，请重试", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    hint = "已知距离 (cm)"; setText(String.format("%.0f", measured))
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("校准: 当前测量 ${String.format("%.1f cm", measured)}")
                    .setMessage("输入实际距离 (cm) 进行校准\n(已取${AppConstants.CALIBRATION_SAMPLE_COUNT}次采样中位数)")
                    .setView(input)
                    .setPositiveButton("校准") { _, _ ->
                        val known = input.text.toString().toFloatOrNull()
                        if (known != null && known > 0) {
                            calibrationFactor = known / measured; isCalibrated = true; calibrating = false
                            saveCalibration(); updateCalibrationUI(); setMode(currentMode)
                            Toast.makeText(this, "校准完成 (x${String.format("%.3f", calibrationFactor)})", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消") { _, _ -> calibrating = false; updateCalibrationUI(); setMode(currentMode) }
                    .show()
            }
        }
    }

    private fun loadCalibration() {
        val prefs = getSharedPreferences("armeasure_cal", MODE_PRIVATE)
        calibrationFactor = prefs.getFloat("factor", 1.0f); isCalibrated = prefs.getBoolean("calibrated", false)
        updateCalibrationUI()
    }
    private fun saveCalibration() {
        getSharedPreferences("armeasure_cal", MODE_PRIVATE).edit()
            .putFloat("factor", calibrationFactor).putBoolean("calibrated", isCalibrated).apply()
    }
    private fun updateCalibrationUI() {
        if (isCalibrated) {
            binding.tvCalibration.text = "x${String.format("%.3f", calibrationFactor)}"
            binding.tvCalibration.setTextColor(android.graphics.Color.parseColor("#00FF88"))
        } else { binding.tvCalibration.text = "" }
        binding.btnCalibrate.setBackgroundColor(if (calibrating) 0x33FFCC00.toInt() else 0x00000000)
        if (manualDepthCm > 0) {
            binding.tvCalibration.text = "ref:${String.format("%.0f", manualDepthCm)}cm"
            binding.tvCalibration.setTextColor(android.graphics.Color.parseColor("#FFCC00"))
        }
    }

    /** Set manual reference depth for ToF-only devices */
    private fun setManualDepth() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "到被测物体的距离 (cm)"
            if (manualDepthCm > 0) setText(String.format("%.0f", manualDepthCm))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("设置参考深度")
            .setMessage("ToF单点传感器无法区分前景/背景。\n输入到被测物体的距离(cm)，\n用于准确计算水平距离。")
            .setView(input)
            .setPositiveButton("设置") { _, _ ->
                val v = input.text.toString().toFloatOrNull()
                if (v != null && v > 0) {
                    manualDepthCm = v; updateCalibrationUI()
                    Toast.makeText(this, "参考深度: ${v.toInt()}cm (再按一次清除)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("清除") { _, _ ->
                manualDepthCm = 0f; updateCalibrationUI()
                Toast.makeText(this, "已清除参考深度", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
        val json = jsonArrayOf(measurementHistory.map { jsonObjectOf("t" to it.timestamp, "m" to it.mode, "r" to it.result, "u" to it.unit) })
        historyPrefs.edit().putString("data", json.toString()).apply()
    }
    private fun loadHistory() {
        try {
            val raw = historyPrefs.getString("data", "[]") ?: "[]"; val arr = org.json.JSONArray(raw)
            measurementHistory.clear()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); measurementHistory.add(HistoryEntry(o.getLong("t"), o.getString("m"), o.getString("r"), o.getString("u"))) }
        } catch (_: Exception) { measurementHistory.clear() }
    }
    private fun showHistory() {
        if (measurementHistory.isEmpty()) { Toast.makeText(this, "暂无测量记录", Toast.LENGTH_SHORT).show(); return }
        val items = measurementHistory.map { e ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.timestamp))
            "$time  [${e.mode}]  ${e.result}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("测量记录 (${measurementHistory.size})").setItems(items, null)
            .setNeutralButton("清空") { _, _ -> measurementHistory.clear(); persistHistory(); Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("关闭", null).show()
    }

    // ── JSON helpers ─────────────────────────────────────────

    private fun jsonObjectOf(vararg pairs: Pair<String, Any>): org.json.JSONObject {
        val obj = org.json.JSONObject(); for ((k, v) in pairs) obj.put(k, v); return obj
    }
    private fun jsonArrayOf(items: List<org.json.JSONObject>): org.json.JSONArray {
        val arr = org.json.JSONArray(); items.forEach { arr.put(it) }; return arr
    }

    // ── Surface Callbacks ────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (cameraCtrl.cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (cameraCtrl.cameraDevice != null && cameraCtrl.captureSession == null) {
            cameraCtrl.setupPreviewSession(cameraCtrl.cameraDevice!!, cameraCtrl.depthCameraEnabled && cameraCtrl.depthStreamActive)
        }
        cachedViewWidth = binding.surfaceView.width.toFloat(); cachedViewHeight = binding.surfaceView.height.toFloat()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cachedViewWidth = width.toFloat(); cachedViewHeight = height.toFloat()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // ── Depth Processing Pipeline ────────────────────────────

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
                // Temporal smoothing: keep last N frames
                synchronized(temporalLock) {
                    temporalFrames.addLast(buf.copyOf())
                    while (temporalFrames.size > AppConstants.TEMPORAL_FRAME_COUNT) temporalFrames.removeFirst()
                }
                depthBuffer = buf; depthWidth = w; depthHeight = h
            }
        } catch (e: Exception) { Log.e(TAG, "depth err", e) } finally { image.close() }
    }

    /**
     * Multi-frame temporal median at pixel level + spatial kernel + boundary detection.
     */
    private fun getDepthAtScreenPoint(sx: Float, sy: Float, filter: DistanceFilter = depthFilter): Float? {
        synchronized(depthLock) {
            val buf = depthBuffer ?: return null
            if (buf.isEmpty() || depthWidth <= 0 || depthHeight <= 0) return null
            val vw = cachedViewWidth; val vh = cachedViewHeight
            if (vw <= 0 || vh <= 0) return null
            val dx: Int; val dy: Int
            val sep = cameraCtrl.depthCameraDevice?.id != cameraCtrl.cameraDevice?.id
            if (sep && cameraCtrl.depthSensorActiveArray != null && cameraCtrl.rgbSensorActiveArray != null) {
                val dep = cameraCtrl.depthSensorActiveArray!!
                dx = (sx / vw * dep.width()).toInt().coerceIn(0, depthWidth - 1)
                dy = (sy / vh * dep.height()).toInt().coerceIn(0, depthHeight - 1)
            } else {
                dx = (sx / vw * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                dy = (sy / vh * depthHeight).toInt().coerceIn(0, depthHeight - 1)
            }

            // Temporal median: center pixel across N frames
            val centerRaw = synchronized(temporalLock) {
                if (temporalFrames.size >= 2) {
                    val vals = temporalFrames.mapNotNull { f ->
                        if (f.size == depthWidth * depthHeight) {
                            val v = f[dy * depthWidth + dx].toInt() and 0x1FFF
                            if (v in 1..8191) v.toFloat() else null
                        } else null
                    }
                    if (vals.size >= 2) { val sorted = vals.sorted(); sorted[sorted.size / 2] }
                    else (buf[dy * depthWidth + dx].toInt() and 0x1FFF).toFloat()
                } else (buf[dy * depthWidth + dx].toInt() and 0x1FFF).toFloat()
            }

            // Adaptive kernel size
            val centerCm = if (centerRaw > 0 && centerRaw <= 8191) centerRaw / 10f else 0f
            val radius = when {
                depthWidth <= 240 || depthHeight <= 180 -> AppConstants.NEIGHBORHOOD_RADIUS_NEAR
                centerCm > 0 && centerCm < AppConstants.NEIGHBORHOOD_NEAR_CM -> AppConstants.NEIGHBORHOOD_RADIUS_NEAR
                centerCm >= AppConstants.NEIGHBORHOOD_FAR_CM -> AppConstants.NEIGHBORHOOD_RADIUS_FAR
                else -> AppConstants.NEIGHBORHOOD_RADIUS_MID
            }

            var wSum = 0.0; var wtSum = 0.0; var cnt = 0
            var neighborMin = Float.MAX_VALUE; var neighborMax = 0f
            // Bilateral filter: depth-aware spatial weighting
            val depthSigma = AppConstants.DEPTH_BILATERAL_SIGMA_MM
            val depthSigma2 = 2f * depthSigma * depthSigma
            for (ddy in -radius..radius) for (ddx in -radius..radius) {
                val px = (dx+ddx).coerceIn(0, depthWidth-1); val py = (dy+ddy).coerceIn(0, depthHeight-1)
                val raw = buf[py*depthWidth+px].toInt() and 0x1FFF
                if (raw in 1..8191) {
                    val spatialD2 = (ddx * ddx + ddy * ddy).toFloat()
                    val wSpatial = 1f / (1f + spatialD2)
                    // Depth similarity weight: reject neighbors with very different depth (different surface)
                    val depthDiff = (raw - centerRaw)
                    val wDepth = Math.exp(-(depthDiff * depthDiff / depthSigma2).toDouble()).toFloat()
                    val w = wSpatial * wDepth
                    wSum += raw * w; wtSum += w; cnt++
                    val rf = raw.toFloat()
                    if (rf < neighborMin) neighborMin = rf
                    if (rf > neighborMax) neighborMax = rf
                }
            }
            if (cnt < 3) return null

            // Boundary detection: large neighbor variance → object edge
            val neighborVariance = neighborMax - neighborMin
            val edgeConfidence = if (neighborVariance > AppConstants.DEPTH_EDGE_VARIANCE_THRESHOLD) {
                (1f - (neighborVariance - AppConstants.DEPTH_EDGE_VARIANCE_THRESHOLD) / AppConstants.DEPTH_EDGE_VARIANCE_THRESHOLD)
                    .coerceAtLeast(AppConstants.DEPTH_EDGE_CONFIDENCE_MIN)
            } else 1f

            val filtered = filter.filter((wSum / wtSum).toFloat())
            // At object edges, reduce confidence (return null to trigger ToF/AF fallback)
            if (edgeConfidence < 0.5f && tofHelper.hasRealTof) return null
            return if (filtered > 0) filtered / 10f else null
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) { backgroundThread?.quitSafely(); try { backgroundThread?.join(1000) } catch (_: Exception) {} }
        backgroundThread = HandlerThread("CameraBG").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper)
    }
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely(); try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (_: Exception) {}
    }
}
