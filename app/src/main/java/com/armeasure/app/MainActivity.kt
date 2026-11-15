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
import androidx.activity.result.contract.ActivityResultContracts
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

    /** Launches the settings screen; calibration requests come back via the result. */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getStringExtra("action") == "calibrate") {
            startCalibration()
        }
    }

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

    private enum class Mode { POINT, LINE, AREA, SWEEP, HEIGHT, ANGLE, LEVEL }
    private enum class MeasureUnit { CM, INCH, M }
    private var currentUnit = MeasureUnit.CM

    private var calibrationFactor = 1.0f
    private var isCalibrated = false
    private var calibrating = false

    // ── Settings-backed state (read from "armeasure_settings" on create/resume) ──
    private var hapticsEnabled = true
    private var showUncertainty = true

    /** Current preview zoom applied to the SurfaceView via scaleX/scaleY. */
    private var zoomScale: Float = 1f

    private val historyPrefs by lazy { getSharedPreferences("armeasure_history", MODE_PRIVATE) }
    private val measurementHistory = mutableListOf<HistoryEntry>()
    data class HistoryEntry(val timestamp: Long, val mode: String, val result: String, val unit: String)
    private var currentMode = Mode.POINT
    @Volatile private var cameraOpening = false
    /** True while a calibration sample collection is in flight — blocks extra taps
     *  from stacking multiple sampling dialogs (see onScreenTapped). */
    private var calibrationSampling = false

    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private val heightPoints = mutableListOf<PointF>()
    private val anglePoints = mutableListOf<PointF>()
    /** Gyro rotation matrix from the vertex tap to arm1 (tap 2) */
    private var angleRotation1: FloatArray? = null
    /** Gyro rotation matrix from arm1 (tap 2) to arm2 (tap 3) */
    private var angleRotation2: FloatArray? = null
    private var firstPoint: PointF? = null
    @Volatile private var firstDistance: Float = 0f
    private var firstUncertainty: Float = 0f
    /** Guard against the fast double-tap race: true once the first point's
     *  depth sampling has finished. Second tap is ignored until then. */
    @Volatile private var firstSamplingDone = false
    /** True once the area result was written to history for the current polygon —
     *  prevents every extra tap on a ≥3-point polygon from spamming history. */
    private var areaHistorySaved = false

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
    private var lastSweepPostMs = 0L

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 100

        // Precomputed bilateral depth-similarity LUT: exp(-x²/2σ²) for x in [0, 256]
        // Avoids per-pixel Math.exp() in depth spatial filter
        private val BILATERAL_LUT by lazy {
            val sigma = AppConstants.DEPTH_BILATERAL_SIGMA_MM
            val sigma2 = 2f * sigma * sigma
            FloatArray(256) { i -> Math.exp(-(i * i).toDouble() / sigma2).toFloat() }
        }
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
        tofHelper = TofSensorHelper(this, sm)
        imuHelper = ImuFusionHelper(this, sm)
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }
        // Line mode: no finger tracking — second point is at screen center (Apple style)
        binding.overlayView.onMove = { x, y ->
            if (firstPoint != null && !binding.overlayView.lineConfirmed) {
                // 安卓方式: 预览线从第一点连到手指位置
                binding.overlayView.lines = listOf(Pair(firstPoint!!, PointF(x, y)))
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
        // Tutorial display is handled in onResume so Settings can re-trigger it
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CAMERA) {
            if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
                // 权限文案统一走资源，便于多语言维护
                Toast.makeText(this, R.string.err_camera_permission, Toast.LENGTH_LONG).show()
                binding.tvSensor.text = getString(R.string.err_camera_permission_label)
                binding.tvDistance.text = getString(R.string.err_camera_permission_restart)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread(); cameraCtrl.backgroundHandler = backgroundHandler
        tofHelper.registerListener(this); imuHelper.start()
        // Re-read settings (unit/haptics/uncertainty/depth) every resume —
        // covers the first launch and changes made in SettingsActivity
        applySettings()
        // Show the tutorial when the "shown" flag is clear (first launch,
        // or after "重新显示教程" in Settings cleared it)
        val tutPrefs = getSharedPreferences(AppConstants.TUTORIAL_PREF, MODE_PRIVATE)
        if (!tutPrefs.getBoolean(AppConstants.TUTORIAL_SHOWN_KEY, false)) {
            binding.overlayView.showTutorial = true
            binding.overlayView.invalidate()
            tutPrefs.edit().putBoolean(AppConstants.TUTORIAL_SHOWN_KEY, true).apply()
        }
        // Level mode updates will be handled via sensor callback
        if (cameraCtrl.cameraDevice == null && cameraCtrl.captureSession == null
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    /** Sync all settings-backed state from SharedPreferences. */
    private fun applySettings() {
        val prefs = getSharedPreferences(AppConstants.SETTINGS_PREF, MODE_PRIVATE)
        currentUnit = when (prefs.getString("unit", "cm")) {
            "inch" -> MeasureUnit.INCH
            "m" -> MeasureUnit.M
            else -> MeasureUnit.CM
        }
        hapticsEnabled = prefs.getBoolean("haptic", true)
        showUncertainty = prefs.getBoolean("show_uncertainty", true)
        val depthEnabled = prefs.getBoolean("depth_enabled", false)
        // 相机尚未枚举时先不切换：等 startCamera 选好摄像头后在 onReady 里补齐，
        // 否则首次启动会误报「设备无深度摄像头」
        if (depthEnabled != cameraCtrl.depthCameraEnabled && cameraCtrl.hasSelected) toggleDepthCamera()
    }

    override fun onPause() {
                tofHelper.unregisterListener(this); imuHelper.stop()
        cameraCtrl.close(); stopBackgroundThread()
        depthBuffer = null; depthBufReusable = null
        calibrating = false; firstPoint = null
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        tofHelper.onSensorEvent(event)
        if (currentMode == Mode.LEVEL) {
            val tilt = Math.toDegrees(imuHelper.tiltAngle.toDouble()).toFloat()
            val isLevel = tilt < AppConstants.LEVEL_THRESHOLD_DEG
            measuredResult = String.format("%.1f°", tilt)
            binding.overlayView.levelTiltDeg = tilt
            // Feed pitch/roll separately so the bubble moves along the true
            // tilt direction instead of always the 45° diagonal
            binding.overlayView.levelPitchDeg = Math.toDegrees(imuHelper.pitch.toDouble()).toFloat()
            binding.overlayView.levelRollDeg = Math.toDegrees(imuHelper.roll.toDouble()).toFloat()
            binding.overlayView.postInvalidate()
            runOnUiThread {
                binding.tvDistance.text = if (isLevel) getString(R.string.level_ok) + " ($measuredResult)"
                                         else getString(R.string.level_tilted) + " $measuredResult"
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Haptic feedback ──────────────────────────────────────

    private fun haptic() {
        if (!hapticsEnabled) return
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(AppConstants.HAPTIC_TAP_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun hapticComplete() {
        if (!hapticsEnabled) return
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
        if (!hapticsEnabled) return
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
        val selection = cameraCtrl.selectCameras() ?: run { binding.tvSensor.text = getString(R.string.err_no_camera); cameraOpening = false; return }
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
            // 摄像头枚举完成后，若设置里的深度开关与当前状态不一致（如首次启动即开启），补一次切换
            val prefs = getSharedPreferences(AppConstants.SETTINGS_PREF, MODE_PRIVATE)
            if (prefs.getBoolean("depth_enabled", false) != cameraCtrl.depthCameraEnabled) toggleDepthCamera()
        }, onError = { cameraOpening = false })
    }

    private fun updateSensorLabel() {
        val parts = mutableListOf<String>()
        if (cameraCtrl.depthCameraEnabled && cameraCtrl.hasDepthMap) {
            parts.add("DEPTH16")
            if (imuHelper.isAvailable()) parts.add("IMU")
        } else if (cameraCtrl.hasSeparateDepthCamera) {
            parts.add(getString(R.string.sensor_main_camera))
        }
        if (tofHelper.hasRealTof) parts.add("ToF")
        else if (!cameraCtrl.depthCameraEnabled) parts.add("AF")
        if (parts.isEmpty()) parts.add("AF")
        runOnUiThread { binding.tvSensor.text = parts.joinToString(" + ") }
    }

    private fun toggleDepthCamera() {
        if (cameraOpening) return
        if (!cameraCtrl.hasSeparateDepthCamera && !cameraCtrl.hasDepthMap) {
            Toast.makeText(this, R.string.err_no_depth_camera, Toast.LENGTH_SHORT).show()
            return
        }
        cameraOpening = true
        depthBuffer = null
        cameraCtrl.toggleDepthCamera { enabled ->
            cameraOpening = false
            runOnUiThread {
                updateDepthToggleButton()
                updateSensorLabel()
                val msg = if (enabled) getString(R.string.err_depth_on) else getString(R.string.err_depth_off)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDepthToggleButton() {
        // Depth toggle is now in SettingsActivity — no-op here
    }

    // ── Measurement ──────────────────────────────────────────

    private fun getDistanceAt(sx: Float, sy: Float, depthFilterOverride: DistanceFilter? = null): Float? {
        // 深度采集: Depth16 + ToF + AF 自适应融合
        val activeFilter = depthFilterOverride ?: depthFilter
        var depthMapCm: Float? = null
        val tofCm = tofHelper.getDistanceCm()

        if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) {
            depthMapCm = getDepthAtScreenPoint(sx, sy, activeFilter)
        }

        // AF 焦距回退 (屈光度 → cm)
        val afCm = run {
            val d = currentFocusDistance
            if (d > 0.1f) {
                val cm = (1f / d) * 100f
                if (cm in 5f..2000f) cm else null
            } else null
        }

        // ── 自适应融合 ──
        var raw: Float? = null

        // 有 Depth16 深度图: 跟 ToF 做置信度加权融合
        if (depthMapCm != null && depthMapCm > 0) {
            raw = if (tofCm != null && tofCm > 0) {
                val varDepth = if (depthMapCm < AppConstants.DEPTH16_CLOSE_THRESHOLD_CM)
                    AppConstants.DEPTH16_VARIANCE_CLOSE else AppConstants.DEPTH16_VARIANCE_FAR
                val varTof = tofHelper.estimatedVariance() // 动态方差
                MeasurementEngine.fuseDepth(depthMapCm, varDepth, tofCm, varTof)
            } else {
                depthMapCm
            }
        }
        // 没有 Depth16: ToF + AF 自适应融合
        else if (tofCm != null && tofCm > 0) {
            raw = if (afCm != null && afCm > 0) {
                val tofConf = tofHelper.confidence()
                val varTof = tofHelper.estimatedVariance()
                // AF 方差固定较大 (AF 本身不准，σ≈15cm)
                val varAf = 225f
                // ToF 置信度低时: 降低 ToF 权重，提高 AF 权重
                val adjustedVarTof = varTof / tofConf.coerceAtLeast(0.1f)
                MeasurementEngine.fuseDepth(tofCm, adjustedVarTof, afCm, varAf)
            } else {
                tofCm
            }
        }
        // 只有 AF
        else if (afCm != null && afCm > 0) {
            raw = afCm
        }

        // NOTE: IMU-based depth scaling was removed — tilting the device does not
        // scale the along-axis depth reading (the old cos(tilt) multiplication made
        // flat-wall readings shrink as the phone tilted, the opposite of reality).
        // Rotation is instead handled as a proper 3D frame change during
        // multi-point measurement (see handleLineTap / handleHeightTap / handleAngleTap).
        if (raw != null && raw > 0 && isCalibrated) raw = raw * calibrationFactor
        return raw
    }

    data class DepthResult(val depthCm: Float, val uncertaintyCm: Float)



    private fun collectDepthSamples(sx: Float, sy: Float, sampleCount: Int = AppConstants.DEPTH_SAMPLE_COUNT, intervalMs: Long = AppConstants.DEPTH_SAMPLE_INTERVAL_MS, onProgress: ((Int, Int) -> Unit)? = null, onComplete: (DepthResult?) -> Unit) {
        val localFilter = DistanceFilter(
            windowSize = AppConstants.DEPTH_WINDOW_SIZE, alpha = 0.4f,
            maxJumpMm = AppConstants.DEPTH_MAX_JUMP_MM, maxRangeMm = AppConstants.DEPTH_MAX_RANGE_MM,
            processNoise = AppConstants.DEPTH_PROCESS_NOISE, initMeasureNoise = AppConstants.DEPTH_INIT_MEASURE_NOISE
        )
        // Warm start from main filter for faster convergence
        localFilter.warmStartFrom(depthFilter)
        val samples = mutableListOf<Float>()
        val minSamples = 3  // 最少采样次数
        var sampleIndex = 0
        fun nextSample() {
            if (sampleIndex >= sampleCount) {
                finishSampling(samples, onComplete)
                return
            }
            val d = getDistanceAt(sx, sy, localFilter)
            if (d != null && d > 0) samples.add(d)
            sampleIndex++
            onProgress?.invoke(sampleIndex, sampleCount)

            // 早停: 采够最少次数后，检查收敛
            if (sampleIndex >= minSamples && localFilter.isConverged(AppConstants.DEPTH_CONVERGENCE_THRESHOLD_MM)) {
                finishSampling(samples, onComplete)
                return
            }
            if (sampleIndex < sampleCount) backgroundHandler?.postDelayed({ nextSample() }, intervalMs)
            else nextSample()
        }
        nextSample()
    }

    private fun finishSampling(samples: MutableList<Float>, onComplete: (DepthResult?) -> Unit) {
        val robust = MeasurementEngine.robustDepth(samples)
        val unc = if (samples.size >= 2) {
            val mean = samples.average().toFloat()
            val variance = samples.sumOf { ((it - mean) * (it - mean)).toDouble() } / (samples.size - 1)
            sqrt(variance).toFloat()
        } else 0f
        onComplete(if (robust != null && robust > 0) DepthResult(robust, unc) else null)
    }

    @Volatile private var cachedHfov: Double = Double.NaN
    @Volatile private var cachedVfov: Double = Double.NaN

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

    // ── Zoom-aware coordinate mapping ────────────────────────

    /**
     * Map a screen coordinate from the zoomed preview scale back to the
     * unzoomed camera plane. After a pinch-zoom the SurfaceView is scaled with
     * scaleX/scaleY around its center; every depth lookup and 3D projection
     * must invert that scale or the sampled pixels no longer match the visual
     * target. Overlay drawing keeps the original (zoomed) screen coordinates.
     */
    private fun unzoom(x: Float, y: Float): PointF {
        val zoom = zoomScale
        if (Math.abs(zoom - 1f) < 1e-4f) return PointF(x, y)
        val w = cachedViewWidth; val h = cachedViewHeight
        if (w <= 0 || h <= 0) return PointF(x, y)
        return PointF((x - w / 2f) / zoom + w / 2f, (y - h / 2f) / zoom + h / 2f)
    }

    /**
     * Intrinsics + active array for depth→3D math. When a separate depth camera
     * supplies the depth map, its own LENS_INTRINSIC_CALIBRATION and active array
     * must be used (RGB intrinsics describe different optics).
     * @return (intrinsics or null for FOV fallback, active array or null)
     */
    private fun depthIntrinsics(): Pair<FloatArray?, Rect?> {
        if (cameraCtrl.hasSeparateDepthCamera) {
            val di = cameraCtrl.depthIntrinsicCalibration
            val da = cameraCtrl.depthSensorActiveArray
            if (di != null && da != null) return Pair(di, da)
        }
        return Pair(cameraCtrl.intrinsicCalibration, cameraCtrl.rgbSensorActiveArray)
    }

    /**
     * Screen point + depth → 3D point (cm) in the capture-time camera frame.
     * Prefers the depth camera intrinsics, falls back to the FOV model.
     */
    private fun screenTo3D(sx: Float, sy: Float, depthCm: Float): Triple<Float, Float, Float> {
        val vw = cachedViewWidth; val vh = cachedViewHeight
        val (intr, arr) = depthIntrinsics()
        return if (intr != null && intr.size >= 4 && vw > 0 && vh > 0 && arr != null) {
            MeasurementEngine.screenTo3DIntrinsic(sx, sy, depthCm, vw, vh, intr, arr.width(), arr.height())
        } else {
            MeasurementEngine.screenTo3DFOV(sx, sy, depthCm, vw, vh, getHfovDegrees(), getVfovDegrees())
        }
    }

    private fun onScreenTapped(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < AppConstants.TAP_DEBOUNCE_MS) return
        lastTapTime = now
        // Tap ripple feedback
        binding.overlayView.triggerRipple(x, y)
        triggerAutoFocus(x, y); haptic()
        val settleMs = if (cameraCtrl.hasDepthMap && cameraCtrl.depthCameraEnabled) AppConstants.SETTLE_MS_DEPTH else AppConstants.SETTLE_MS_AF
        if (calibrating) {
            // 校准采样进行中：忽略额外点击，避免叠开多个采样对话框
            if (calibrationSampling) return
            calibrationSampling = true
            backgroundHandler?.postDelayed({ runOnUiThread { performCalibration(x, y) } }, settleMs)
            return
        }
        backgroundHandler?.postDelayed({ runOnUiThread { measureAtPoint(x, y) } }, settleMs)
    }

    private fun onSweepMoved(x: Float, y: Float) {
        // Throttle: touch-move events can fire at 120Hz; ~30fps is plenty for
        // the sweep readout and avoids flooding the background handler
        val now = System.currentTimeMillis()
        if (now - lastSweepPostMs < 33) return
        lastSweepPostMs = now
        backgroundHandler?.post {
            val dist = getDistanceAt(x, y)
            runOnUiThread {
                if (dist != null && dist > 0) {
                    binding.overlayView.sweepDistanceCm = dist
                    // 读数跟随单位设置（此前写死 cm，切换英寸/米后浮层仍显示厘米）
                    binding.overlayView.updateSweepText(formatDistance(dist))
                    synchronized(sweepLock) {
                        sweepHistory.add(Pair(x, dist))
                        while (sweepHistory.size > maxSweepHistory) sweepHistory.removeAt(0)
                        binding.overlayView.sweepHistory = sweepHistory.toList()
                    }
                    binding.tvDistance.text = formatDistance(dist)
                } else { binding.overlayView.sweepDistanceCm = -1f; binding.tvDistance.text = getString(R.string.sweep_scanning) }
                binding.overlayView.invalidate()
            }
        }
    }

    private fun triggerAutoFocus(sx: Float, sy: Float) {
        val session = cameraCtrl.captureSession ?: return; val device = cameraCtrl.cameraDevice ?: return
        val sr = cameraCtrl.rgbSensorActiveArray ?: return; val sv = binding.surfaceView
        // Invert the preview zoom before mapping to the sensor region
        val uz = unzoom(sx, sy)
        val fx = (uz.x * sr.width() / sv.width).toInt().coerceIn(0, sr.width()-1)
        val fy = (uz.y * sr.height() / sv.height).toInt().coerceIn(0, sr.height()-1)
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
            Mode.HEIGHT -> handleHeightTap(x, y)
            Mode.ANGLE -> handleAngleTap(x, y)
            Mode.LEVEL -> {} // Level mode doesn't use tap
        }
    }

    private fun handlePointTap(x: Float, y: Float) {
        overlayPoints.clear(); overlayPoints.add(PointF(x, y))
        binding.tvDistance.text = getString(R.string.sampling, 0, AppConstants.DEPTH_SAMPLE_COUNT)
        updateOverlay()
        collectDepthSamples(x, y, onProgress = { cur, total ->
            runOnUiThread { binding.tvDistance.text = getString(R.string.sampling, cur, total) }
        }) { result ->
            val dist = result?.depthCm
            if (dist != null && dist > 0) lastRawCm = dist
            val unc = result?.uncertaintyCm ?: 0f
            val uncStr = if (showUncertainty && unc > 0.5f) "  ±${String.format("%.1f", unc)}cm" else ""
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                measuredResult = when {
                    dist != null -> formatDistance(dist) + uncStr
                    tofHelper.tofDistanceMm > 0 -> "~${formatDistance(tofHelper.tofDistanceMm / 10f)} ${getString(R.string.text_near_suffix)}"
                    else -> getString(R.string.focus_error)
                }
                binding.tvDistance.text = measuredResult; updateOverlay()
                if (dist != null && dist > 0) { hapticComplete(); saveToHistory(formatDistance(dist), "单点") }
            }
        }
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            // ── 第一点: 点哪里就是哪里 ──
            binding.overlayView.lines = emptyList()
            binding.overlayView.lineDistanceLabels = emptyList()
            binding.overlayView.showLineLabels = false
            binding.overlayView.lineConfirmed = false
            binding.overlayView.lineExpandProgress = 1f

            firstPoint = PointF(x, y)
            firstSamplingDone = false  // second tap is ignored until sampling finishes
            overlayPoints.clear(); overlayPoints.add(PointF(x, y))
            binding.overlayView.points = overlayPoints.toList()
            binding.overlayView.triggerPulse(x, y)
            binding.tvDistance.text = getString(R.string.sampling, 1, 2)
            haptic()
            if (imuHelper.isAvailable()) imuHelper.markPoint()
            collectDepthSamples(x, y, onProgress = { cur, total ->
                runOnUiThread { binding.tvDistance.text = getString(R.string.sampling_line_first, cur, total) }
            }) { result ->
                firstDistance = result?.depthCm ?: 0f
                firstUncertainty = result?.uncertaintyCm ?: 0f
                if (firstDistance < 5f || firstDistance > 2000f) {
                    firstDistance = 0f
                    firstSamplingDone = true
                    hapticWarning()
                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        binding.tvDistance.text = getString(R.string.err_no_depth_retry)
                        firstPoint = null
                        overlayPoints.clear(); updateOverlay()
                    }
                    return@collectDepthSamples
                }
                firstSamplingDone = true
                runOnUiThread {
                    binding.tvDistance.text = getString(R.string.prompt_second_point)
                }
            }
        } else {
            // ── 第二点: 点哪里就是哪里 ──
            // Fast double-tap race: the first point's samples are still in
            // flight (firstDistance not yet written) — ignore this tap instead
            // of reporting a bogus "depth failed".
            if (!firstSamplingDone) {
                binding.tvDistance.text = getString(R.string.sampling, 1, 2)
                return
            }
            val p1 = firstPoint!!
            binding.overlayView.triggerPulse(x, y)
            haptic()
            val imuAvail = imuHelper.isAvailable()
            val motion = if (imuAvail) imuHelper.checkMotionSince() else null
            // Capture the FULL 3D rotation (pitch+roll+yaw) that happened since
            // the first tap BEFORE markPoint() clears the gyro integral
            val rotationToP1Frame = if (imuAvail) imuHelper.getDeltaRotationMatrix() else null
            if (imuAvail) imuHelper.markPoint()
            val motionWarn = motion?.warning
            if (motionWarn != null) hapticWarning()

            binding.tvDistance.text = getString(R.string.prompt_line_sampling)
            overlayPoints.add(PointF(x, y))
            binding.overlayView.points = overlayPoints.toList()

            collectDepthSamples(x, y, onProgress = { cur, total ->
                runOnUiThread { binding.tvDistance.text = getString(R.string.sampling_line_second, cur, total) }
            }) { result2 ->
                val d2 = result2?.depthCm ?: 0f
                val d1 = firstDistance

                val vw = cachedViewWidth.takeIf { it > 0 } ?: binding.surfaceView.width.toFloat()
                val vh = cachedViewHeight.takeIf { it > 0 } ?: binding.surfaceView.height.toFloat()

                // Unzoom for camera-plane math; overlay keeps the raw screen coords
                val p1Screen = unzoom(p1.x, p1.y)
                val p2Screen = unzoom(x, y)

                if (d1 < 5f || d2 < 5f || d1 > 2000f || d2 > 2000f) {
                    hapticWarning()
                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        binding.tvDistance.text = getString(R.string.err_no_depth_retry)
                        overlayPoints.clear(); updateOverlay()
                        firstPoint = null
                    }
                    return@collectDepthSamples
                }

                // Depth consistency check: large d1/d2 ratio suggests occlusion or noise
                val depthRatio = if (d1 > d2) d1 / d2 else d2 / d1
                val consistencyPenalty = if (depthRatio > AppConstants.DEPTH_CONSISTENCY_MAX_RATIO) {
                    hapticWarning()
                    depthRatio // multiply uncertainty by ratio
                } else 1f

                // 3D reconstruction in the first point's camera frame:
                // both points are projected via the depth-camera intrinsics,
                // then p2 is rotated back through the gyro-integrated rotation
                val p1w = screenTo3D(p1Screen.x, p1Screen.y, d1)
                val p2w = screenTo3D(p2Screen.x, p2Screen.y, d2)
                val p2r = if (rotationToP1Frame != null)
                    MeasurementEngine.applyRotation(p2w.first, p2w.second, p2w.third, rotationToP1Frame)
                else p2w
                val dist = MeasurementEngine.distance3D(p1w, p2r)

                val rawUnc = if (vw > 0 && vh > 0) {
                    val (intr, arr) = depthIntrinsics()
                    if (intr != null && intr.size >= 4 && arr != null) {
                        MeasurementEngine.compute3DDistanceUncertaintyIntrinsic(
                            p1Screen.x, p1Screen.y, p2Screen.x, p2Screen.y, d1, d2,
                            firstUncertainty, result2?.uncertaintyCm ?: 0f,
                            vw, vh, intr, arr.width(), arr.height())
                    } else {
                        MeasurementEngine.compute3DDistanceUncertaintyFOV(
                            p1Screen.x, p1Screen.y, p2Screen.x, p2Screen.y, d1, d2,
                            firstUncertainty, result2?.uncertaintyCm ?: 0f,
                            vw, vh, getHfovDegrees(), getVfovDegrees())
                    }
                } else 0f
                val motionConfidence = when {
                    !imuAvail -> 1f; motion?.excessive == true -> 1.5f
                    motion != null && motion.rotationDeg > ImuFusionHelper.ROTATION_NOISE_FLOOR_DEG * 3 -> 1.25f
                    else -> 1f
                }
                val totalUnc = rawUnc * motionConfidence * consistencyPenalty

                binding.overlayView.lineConfirmed = true
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    measuredResult = formatDistance(dist)
                    val uncStr = if (showUncertainty && totalUnc > 0.5f) "  ±${String.format("%.1f", totalUnc)}cm" else ""
                    val displayText = listOfNotNull(measuredResult + uncStr, motionWarn).joinToString("  ")
                    binding.tvDistance.text = displayText
                    binding.overlayView.lines = listOf(Pair(PointF(p1.x, p1.y), PointF(x, y)))
                    binding.overlayView.showLineLabels = true
                    binding.overlayView.lineDistanceLabels = listOf(displayText)
                    updateOverlay(); firstPoint = null
                    binding.overlayView.animateLineExpand()
                    hapticComplete(); saveToHistory(measuredResult, "两点")
                }
            }
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))
        binding.overlayView.animateNewPoint()
        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints, depthCache)
            measuredResult = if (area > 0) formatArea(area) else getString(R.string.err_area_uncalculable)
            binding.tvDistance.text = measuredResult
            // Save history once per polygon (at first closure), not on every
            // extra tap that merely re-computes the same area
            if (area > 0 && overlayAreaPoints.size == 3 && !areaHistorySaved) {
                areaHistorySaved = true
                saveToHistory(measuredResult, "面积")
            }
        } else binding.tvDistance.text = getString(R.string.prompt_area_continue, overlayAreaPoints.size)
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i+1]))
        if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
        binding.overlayView.points = overlayAreaPoints.toList(); binding.overlayView.areaPoints = overlayAreaPoints.toList(); binding.overlayView.invalidate()
        if (overlayAreaPoints.size >= 3) { binding.progressBar.visibility = android.view.View.GONE; hapticComplete() }
    }

    private fun handleHeightTap(x: Float, y: Float) {
        // 两点采样进行中：忽略额外点击，避免采样等待期点出游离点（与两点模式同款守卫）
        if (heightPoints.size >= 2) return
        heightPoints.add(PointF(x, y))
        binding.overlayView.triggerPulse(x, y)
        haptic()

        if (heightPoints.size == 1) {
            binding.tvDistance.text = getString(R.string.prompt_height_top)
            overlayPoints.clear()
            overlayPoints.add(PointF(x, y))
            updateOverlay()
        } else if (heightPoints.size == 2) {
            overlayPoints.add(PointF(x, y))
            updateOverlay()

            val p1 = heightPoints[0]; val p2 = heightPoints[1]
            binding.tvDistance.text = getString(R.string.focus_error)
            binding.progressBar.visibility = android.view.View.VISIBLE

            // Capture each point's gravity (up) direction at ITS OWN tap time —
            // height = difference of the two vertical components, each projected
            // in the gravity frame of its own capture moment
            val up1 = if (imuHelper.isAvailable()) imuHelper.getUpVectorCam() else null
            if (imuHelper.isAvailable()) imuHelper.markPoint()

            // Collect depth at both points IN PARALLEL
            var r1: DepthResult? = null; var r2: DepthResult? = null
            var doneCount = 0
            val lock = Object()

            fun onOneDone() {
                synchronized(lock) {
                    doneCount++
                    if (doneCount < 2) return
                }
                val d1 = r1?.depthCm ?: 0f; val d2 = r2?.depthCm ?: 0f
                if (d1 <= 0 || d2 <= 0) {
                    runOnUiThread {
                        binding.tvDistance.text = getString(R.string.err_depth_failed)
                        binding.progressBar.visibility = android.view.View.GONE; heightPoints.clear()
                    }
                    return
                }
                // Second tap: capture up vector + rotation before the third markPoint
                val up2 = if (imuHelper.isAvailable()) imuHelper.getUpVectorCam() else null
                val rotationToP1Frame = if (imuHelper.isAvailable()) imuHelper.getDeltaRotationMatrix() else null
                if (imuHelper.isAvailable()) imuHelper.markPoint()

                // Unzoom for camera-plane math; overlay keeps raw screen coords
                val p1Screen = unzoom(p1.x, p1.y)
                val p2Screen = unzoom(p2.x, p2.y)

                // 3D points, each in its own capture-time camera frame
                val p1w = screenTo3D(p1Screen.x, p1Screen.y, d1)
                val p2w = screenTo3D(p2Screen.x, p2Screen.y, d2)

                // Gravity projection: vertical (z) component of each point in a
                // gravity-aligned frame built from the smoothed accelerometer up
                // vector at its own tap. Missing up vectors (IMU absent) fall
                // back to the device-Y assumption, matching the legacy behavior.
                val z1 = MeasurementEngine.toGravityFrame(p1w.first, p1w.second, p1w.third, up1 ?: floatArrayOf(0f, 1f, 0f)).third
                val p2r = if (rotationToP1Frame != null)
                    MeasurementEngine.applyRotation(p2w.first, p2w.second, p2w.third, rotationToP1Frame)
                else p2w
                val z2 = MeasurementEngine.toGravityFrame(p2r.first, p2r.second, p2r.third, up2 ?: up1 ?: floatArrayOf(0f, 1f, 0f)).third
                val height = Math.abs(z2 - z1)

                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    measuredResult = formatDistance(height)
                    binding.tvDistance.text = "↕ $measuredResult"
                    binding.overlayView.lines = listOf(Pair(PointF(p1.x, p1.y), PointF(p2.x, p2.y)))
                    binding.overlayView.lineDistanceLabels = listOf(measuredResult)
                    binding.overlayView.showLineLabels = true
                    binding.overlayView.lineConfirmed = true
                    binding.overlayView.animateLineExpand()
                    hapticComplete()
                    saveToHistory(measuredResult, "高度")
                    heightPoints.clear()
                }
            }

            collectDepthSamples(p1.x, p1.y) { result -> r1 = result; onOneDone() }
            collectDepthSamples(p2.x, p2.y) { result -> r2 = result; onOneDone() }
        }
    }

    private fun handleAngleTap(x: Float, y: Float) {
        // 三点采样进行中：忽略额外点击，避免采样等待期点出游离点（与两点模式同款守卫）
        if (anglePoints.size >= 3) return
        anglePoints.add(PointF(x, y))
        binding.overlayView.triggerPulse(x, y)
        haptic()

        when (anglePoints.size) {
            1 -> {
                // FIRST tap is the angle VERTEX (matches the README: 顶点+两端)
                binding.tvDistance.text = getString(R.string.prompt_angle_first_arm)
                overlayPoints.clear()
                overlayPoints.add(PointF(x, y))
                updateOverlay()
                if (imuHelper.isAvailable()) imuHelper.markPoint()
            }
            2 -> {
                // Second tap = first arm endpoint. Snapshot rotation since the
                // vertex BEFORE markPoint() clears the gyro integral
                angleRotation1 = if (imuHelper.isAvailable()) imuHelper.getDeltaRotationMatrix() else null
                if (imuHelper.isAvailable()) imuHelper.markPoint()
                binding.tvDistance.text = getString(R.string.prompt_angle_second_arm)
                overlayPoints.add(PointF(x, y))
                updateOverlay()
            }
            3 -> {
                // Third tap = second arm endpoint
                angleRotation2 = if (imuHelper.isAvailable()) imuHelper.getDeltaRotationMatrix() else null
                if (imuHelper.isAvailable()) imuHelper.markPoint()
                overlayPoints.add(PointF(x, y))
                updateOverlay()
                binding.tvDistance.text = getString(R.string.prompt_calculating_angle)
                binding.progressBar.visibility = android.view.View.VISIBLE

                // Get depths for all 3 points IN PARALLEL
                val results = arrayOfNulls<DepthResult?>(3)
                var doneCount = 0
                val lock = Object()

                fun onOneDone() {
                    synchronized(lock) {
                        doneCount++
                        if (doneCount < 3) return
                    }
                    val localDepths = results.map { it?.depthCm ?: 0f }
                    if (localDepths.any { it <= 0 }) {
                        runOnUiThread {
                            binding.tvDistance.text = getString(R.string.err_depth_failed)
                            binding.progressBar.visibility = android.view.View.GONE; anglePoints.clear()
                        }
                        return
                    }

                    // Unzoom for camera-plane math; overlay keeps raw screen coords
                    val vScreen = unzoom(anglePoints[0].x, anglePoints[0].y)
                    val a1Screen = unzoom(anglePoints[1].x, anglePoints[1].y)
                    val a2Screen = unzoom(anglePoints[2].x, anglePoints[2].y)

                    // Rebuild the arms in the vertex's camera frame:
                    // arm1 was captured in frame2 (rot12 back to frame1),
                    // arm2 in frame3 (rot23 back to frame2, then rot12 to frame1)
                    val vertex = screenTo3D(vScreen.x, vScreen.y, localDepths[0])
                    val a1 = screenTo3D(a1Screen.x, a1Screen.y, localDepths[1])
                    val a1r = angleRotation1?.let {
                        MeasurementEngine.applyRotation(a1.first, a1.second, a1.third, it)
                    } ?: a1
                    val a2 = screenTo3D(a2Screen.x, a2Screen.y, localDepths[2])
                    val a2f2 = angleRotation2?.let {
                        MeasurementEngine.applyRotation(a2.first, a2.second, a2.third, it)
                    } ?: a2
                    val a2r = angleRotation1?.let {
                        MeasurementEngine.applyRotation(a2f2.first, a2f2.second, a2f2.third, it)
                    } ?: a2f2
                    val angle = AngleMeasureHelper.computeAngle3D(vertex, a1r, a2r)

                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        measuredResult = String.format("%.1f°", angle)
                        binding.tvDistance.text = "∠ $measuredResult"
                        binding.overlayView.lines = listOf(
                            Pair(anglePoints[0], anglePoints[1]),
                            Pair(anglePoints[0], anglePoints[2])
                        )
                        binding.overlayView.lineDistanceLabels = listOf(measuredResult)
                        binding.overlayView.showLineLabels = true
                        binding.overlayView.lineConfirmed = true
                        binding.overlayView.animateLineExpand()
                        hapticComplete()
                        saveToHistory(measuredResult, "角度")
                        anglePoints.clear()
                    }
                }

                for (idx in 0 until 3) {
                    val capturedIdx = idx
                    collectDepthSamples(anglePoints[idx].x, anglePoints[idx].y) { result ->
                        results[capturedIdx] = result
                        onOneDone()
                    }
                }
            }
        }
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
                // 与线/角度模式同口径：先逆缩放（unzoom）再走统一的 3D 投影辅助函数，
                // 深度相机自带内参优先，FOV 模型兜底，避免面积与距离两套投影公式不一致
                val pts3d = pts.zip(depths).map { (p, d) ->
                    val uz = unzoom(p.x, p.y)
                    screenTo3D(uz.x, uz.y, d!!)
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
        // New measurement modes
        binding.btnHeightMode?.setOnClickListener { setMode(Mode.HEIGHT) }
        binding.btnAngleMode?.setOnClickListener { setMode(Mode.ANGLE) }
        binding.btnLevelMode?.setOnClickListener { setMode(Mode.LEVEL) }
        // Settings: 用 ActivityResult launcher 启动，便于校准入口回传 action
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.btnUndo.setOnClickListener { undoLastPoint() }
        binding.overlayView.onScale = { sf ->
            val sv = binding.surfaceView; val ns = (sv.scaleX * sf).coerceIn(0.5f, 5f)
            sv.scaleX = ns; sv.scaleY = ns; sv.pivotX = sv.width / 2f; sv.pivotY = sv.height / 2f
            // 同步记录当前变焦倍数，供深度采样/3D 投影做逆缩放
            zoomScale = ns
        }
        binding.btnHistory.setOnClickListener { startActivity(android.content.Intent(this, HistoryActivity::class.java)) }
        binding.tvDistance.setOnLongClickListener { cycleUnit(); true }
        binding.fabReset.setOnClickListener { resetMeasurement() }
        binding.btnSave.setOnClickListener { saveMeasurement() }
        setMode(Mode.POINT)
    }

    private fun setMode(mode: Mode) {
        // Clean up previous mode state before switching
        if (currentMode == Mode.LINE && mode != Mode.LINE) {
            firstPoint = null
        }
        // Clean up stale overlay lines when entering any mode
        if (mode != currentMode) {
            binding.overlayView.lines = emptyList()
            binding.overlayView.lineDistanceLabels = emptyList()
            binding.overlayView.showLineLabels = false
            binding.overlayView.lineConfirmed = false
            binding.overlayView.lineExpandProgress = 1f
        }
        currentMode = mode; calibrating = false; calibrationSampling = false
        // 模式提示文案走资源，保证与 overlay 教程/提示一致
        binding.tvMode.text = when (mode) {
            Mode.POINT -> getString(R.string.mode_hint_point); Mode.LINE -> getString(R.string.mode_hint_line)
            Mode.AREA -> getString(R.string.mode_hint_area); Mode.SWEEP -> getString(R.string.mode_hint_sweep)
            Mode.HEIGHT -> getString(R.string.mode_hint_height); Mode.ANGLE -> getString(R.string.mode_hint_angle)
            Mode.LEVEL -> getString(R.string.mode_hint_level)
        }
        binding.overlayView.sweepMode = (mode == Mode.SWEEP)
        binding.overlayView.levelMode = (mode == Mode.LEVEL)
        // Collect all mode buttons (some may be null if layout doesn't have them)
        val allBtns = listOfNotNull(
            binding.btnPointMode, binding.btnLineMode, binding.btnAreaMode, binding.btnSweepMode,
            binding.btnHeightMode, binding.btnAngleMode, binding.btnLevelMode
        )
        allBtns.forEach {
            it.setBackgroundColor(0x00000000)
            // Reset text color on child TextView if it's a LinearLayout
            val tv = (it as? android.widget.LinearLayout)?.getChildAt(1) as? android.widget.TextView
            tv?.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }
        val active = when (mode) {
            Mode.POINT -> binding.btnPointMode; Mode.LINE -> binding.btnLineMode
            Mode.AREA -> binding.btnAreaMode; Mode.SWEEP -> binding.btnSweepMode
            Mode.HEIGHT -> binding.btnHeightMode; Mode.ANGLE -> binding.btnAngleMode; Mode.LEVEL -> binding.btnLevelMode
        }
        active?.setBackgroundColor(android.graphics.Color.parseColor("#2200FF88"))
        val activeTv = (active as? android.widget.LinearLayout)?.getChildAt(1) as? android.widget.TextView
        activeTv?.setTextColor(android.graphics.Color.parseColor("#00FF88"))
    }

    private fun resetMeasurement() {
        overlayPoints.clear(); overlayAreaPoints.clear(); sweepHistory.clear(); firstPoint = null
        heightPoints.clear(); anglePoints.clear()
        // 复位各模式的中间状态：面积去重标记、线采样竞态守卫、角度帧间旋转缓存
        areaHistorySaved = false; firstSamplingDone = false
        angleRotation1 = null; angleRotation2 = null
        // 数码变焦同样一并还原，避免残留缩放导致后续深度采样错位
        binding.surfaceView.scaleX = 1f; binding.surfaceView.scaleY = 1f
        zoomScale = 1f
        synchronized(depthCacheLock) { depthCache.clear() }
        calibrating = false; calibrationSampling = false; lastRawCm = -1f; firstUncertainty = 0f
        measuredResult = "--"; binding.tvDistance.text = "--"
        binding.progressBar.visibility = android.view.View.GONE
        binding.overlayView.points = emptyList(); binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList(); binding.overlayView.showLineLabels = false
        binding.overlayView.sweepDistanceCm = -1f; binding.overlayView.sweepHistory = emptyList()
        binding.overlayView.lineDistanceLabels = emptyList(); binding.overlayView.lineConfirmed = false
        binding.overlayView.confirmFlash = false
        depthFilter.reset(); tofHelper.reset()
        if (imuHelper.isAvailable()) imuHelper.reset()
        currentFocusDistance = -1f; depthBuffer = null
        synchronized(temporalLock) { temporalFrames.clear() }
        updateCalibrationUI()
        binding.overlayView.postInvalidate()
    }

    // ── Save & Share ─────────────────────────────────────────

    private fun saveMeasurement() {
        // 水平仪模式没有可保存的测量：结果文本是实时倾角，不能当作测量记录入库
        if (currentMode == Mode.LEVEL) {
            Toast.makeText(this, R.string.err_no_measurements, Toast.LENGTH_SHORT).show(); return
        }
        if (measuredResult == "--" || measuredResult.contains("无")) {
            Toast.makeText(this, R.string.err_no_measurements, Toast.LENGTH_SHORT).show(); return
        }
        try {
            val sv = binding.surfaceView; val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); sv.getLocationInWindow(loc)
                android.view.PixelCopy.request(window, Rect(loc[0], loc[1], loc[0]+sv.width, loc[1]+sv.height), bitmap,
                    { r ->
                        // PixelCopy 抓的是整个窗口，测量覆盖层已包含在内；
                        // 不得再 draw 一遍，否则线条/读数叠影加深
                        if (r == android.view.PixelCopy.SUCCESS) saveBitmapToGallery(bitmap)
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
            runOnUiThread { Toast.makeText(this, getString(R.string.err_save_failed, e.message), Toast.LENGTH_SHORT).show() }
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
                        Toast.makeText(this, getString(R.string.save_done, measuredResult), Toast.LENGTH_SHORT).show()
                        showShareOption(uri)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val path = android.provider.MediaStore.Images.Media.insertImage(contentResolver, bitmap, displayName, "Distance: $measuredResult")
                runOnUiThread { Toast.makeText(this, getString(R.string.save_done, measuredResult), Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, getString(R.string.err_save_failed, e.message), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showShareOption(uri: android.net.Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.share_dialog_title)
            .setPositiveButton(R.string.share_dialog_ok) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, measuredResult))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
            }
            .setNegativeButton(R.string.share_dialog_cancel, null)
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
                    // 多边形已改变：面积回落到 3 点以下时重置去重标记，
                    // 重新补点闭合后才能再次写入历史
                    if (overlayAreaPoints.size < 3) areaHistorySaved = false
                    if (overlayAreaPoints.size >= 3) {
                        val a = computeArea(overlayAreaPoints, depthCache)
                        measuredResult = if (a > 0) formatArea(a) else getString(R.string.err_area_uncalculable)
                        binding.tvDistance.text = measuredResult
                    } else {
                        measuredResult = if (overlayAreaPoints.isEmpty()) "--"
                            else getString(R.string.prompt_area_continue, overlayAreaPoints.size)
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
            Mode.LINE -> { firstPoint = null; firstUncertainty = 0f; overlayPoints.clear(); binding.tvDistance.text = "--"; binding.overlayView.lines = emptyList(); updateOverlay() }
            Mode.HEIGHT -> {
                if (heightPoints.isNotEmpty()) heightPoints.removeAt(heightPoints.size - 1)
                overlayPoints.clear(); updateOverlay()
                binding.tvDistance.text = if (heightPoints.isEmpty()) "--" else getString(R.string.prompt_height_top)
            }
            Mode.ANGLE -> {
                if (anglePoints.isNotEmpty()) anglePoints.removeAt(anglePoints.size - 1)
                overlayPoints.clear()
                anglePoints.forEach { overlayPoints.add(it) }
                updateOverlay()
                binding.tvDistance.text = when (anglePoints.size) {
                    0 -> "--"
                    1 -> getString(R.string.prompt_angle_first_arm)
                    else -> getString(R.string.prompt_angle_second_arm)
                }
            }
            else -> resetMeasurement()
        }
    }

    private var lastRawCm: Float = -1f

    private fun cycleUnit() {
        currentUnit = when (currentUnit) { MeasureUnit.CM -> MeasureUnit.INCH; MeasureUnit.INCH -> MeasureUnit.M; MeasureUnit.M -> MeasureUnit.CM }
        // 单位标签走资源（与设置页单位选择一致）
        val label = when (currentUnit) {
            MeasureUnit.CM -> getString(R.string.unit_label_cm)
            MeasureUnit.INCH -> getString(R.string.unit_label_inch)
            MeasureUnit.M -> getString(R.string.unit_label_m)
        }
        Toast.makeText(this, getString(R.string.unit_prefix, label), Toast.LENGTH_SHORT).show()
        if (lastRawCm > 0 && currentMode == Mode.POINT) { measuredResult = formatDistance(lastRawCm); binding.tvDistance.text = measuredResult }
    }

    // ── Calibration ──────────────────────────────────────────

    private fun startCalibration() {
        if (calibrating) { calibrating = false; updateCalibrationUI(); return }
        calibrating = true
        binding.tvDistance.text = getString(R.string.prompt_set_calibration)
        binding.tvMode.text = getString(R.string.prompt_calibration_mode)
        updateCalibrationUI()
    }

    private fun performCalibration(sx: Float, sy: Float) {
        // Use same pipeline as two-point: collectDepthSamples with local filter
        //（skipImu 参数已随 IMU 统一修正移除：校准采样同样应走帧间旋转补偿，口径与测量一致）
        collectDepthSamples(sx, sy,
            sampleCount = AppConstants.CALIBRATION_SAMPLE_COUNT,
            intervalMs = AppConstants.CALIBRATION_SAMPLE_INTERVAL_MS
        ) { result ->
            val measured = result?.depthCm
            runOnUiThread {
                if (measured == null || measured <= 0) {
                    Toast.makeText(this, R.string.err_calibration_no_distance, Toast.LENGTH_SHORT).show()
                    calibrationSampling = false
                    return@runOnUiThread
                }
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    hint = getString(R.string.calibration_hint_known_cm); setText(String.format("%.0f", measured))
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.calibration_dialog_title, String.format("%.1f cm", measured)))
                    .setMessage(getString(R.string.calibration_dialog_message, AppConstants.CALIBRATION_SAMPLE_COUNT))
                    .setView(input)
                    .setPositiveButton(R.string.calibration) { _, _ ->
                        val known = input.text.toString().toFloatOrNull()
                        if (known != null && known > 0) {
                            calibrationFactor = known / measured; isCalibrated = true; calibrating = false
                            saveCalibration(); updateCalibrationUI(); setMode(currentMode)
                            Toast.makeText(this, getString(R.string.calibration_done, String.format("%.3f", calibrationFactor)), Toast.LENGTH_SHORT).show()
                        }
                        calibrationSampling = false
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> calibrating = false; calibrationSampling = false; updateCalibrationUI(); setMode(currentMode) }
                    // 系统返回键关闭对话框同样要复位标记，否则后续点击会被吞掉
                    .setOnCancelListener { calibrationSampling = false }
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
        // Calibration UI is now in SettingsActivity
        // Keep calibration state but don't reference removed views
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
            // 逆缩放：屏幕点若处于数码变焦状态，先映射回原始取景坐标，避免深度采样随 zoom 偏移
            val uz = unzoom(sx, sy)
            val ux = uz.x; val uy = uz.y
            val dx: Int; val dy: Int
            val sep = cameraCtrl.depthCameraDevice?.id != cameraCtrl.cameraDevice?.id
            if (sep && cameraCtrl.depthSensorActiveArray != null && cameraCtrl.rgbSensorActiveArray != null) {
                val dep = cameraCtrl.depthSensorActiveArray!!
                dx = (ux / vw * dep.width()).toInt().coerceIn(0, depthWidth - 1)
                dy = (uy / vh * dep.height()).toInt().coerceIn(0, depthHeight - 1)
            } else {
                dx = (ux / vw * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                dy = (uy / vh * depthHeight).toInt().coerceIn(0, depthHeight - 1)
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
            val depthSigma2 = 2f * AppConstants.DEPTH_BILATERAL_SIGMA_MM * AppConstants.DEPTH_BILATERAL_SIGMA_MM
            for (ddy in -radius..radius) for (ddx in -radius..radius) {
                val px = (dx+ddx).coerceIn(0, depthWidth-1); val py = (dy+ddy).coerceIn(0, depthHeight-1)
                val raw = buf[py*depthWidth+px].toInt() and 0x1FFF
                if (raw in 1..8191) {
                    val spatialD2 = (ddx * ddx + ddy * ddy).toFloat()
                    val wSpatial = 1f / (1f + spatialD2)
                    // Depth similarity weight via precomputed LUT (avoids per-pixel exp)
                    val depthDiff = Math.abs(raw - centerRaw.toInt())
                    val wDepth = if (depthDiff < BILATERAL_LUT.size) BILATERAL_LUT[depthDiff] else 0f
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

            // #3: Motion-adaptive Kalman process noise
            if (imuHelper.isAvailable() && filter === depthFilter) {
                val motion = imuHelper.checkMotionSince()
                val motionFactor = when {
                    motion.excessive -> 5f
                    motion.rotationDeg > 2f -> 2f
                    else -> 0.5f
                }
                filter.setProcessNoise(AppConstants.DEPTH_PROCESS_NOISE * motionFactor)
            }

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
