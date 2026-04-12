package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.armeasure.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * AR Measure App - Camera2 preview + dToF distance sensor.
 *
 * Distance source priority:
 *   1. ToF sensor (SensorManager) — Xiaomi custom VL53L1X dToF, mm precision
 *   2. Camera2 LENS_FOCUS_DISTANCE — autofocus fallback, less accurate
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding

    // Camera
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Focus distance (meters) - updated continuously, used as fallback
    @Volatile private var currentFocusDistance: Float = -1f

    // Camera intrinsics for FOV calculation
    private var sensorSize: SizeF? = null
    private var focalLengthMm: Float = 0f

    // ═══════════════════════════════════════════════════════════
    // ToF Sensor (from tof-ranger approach)
    // ═══════════════════════════════════════════════════════════

    // Xiaomi custom ToF sensor types (non-AOSP, defined by MIUI/HyperOS)
    private val KNOWN_TOF_TYPES = intArrayOf(33171040, 33171041, 65570, 65572)

    private lateinit var sensorManager: SensorManager
    private var tofSensor: Sensor? = null
    private var detectedTofType: Int = 0

    // ToF distance in mm (filtered), -1 = unavailable
    @Volatile private var tofDistanceMm: Float = -1f

    // ToF signal processing
    private val tofFilter = DistanceFilter(windowSize = 3, alpha = 0.6f, maxJumpMm = 1500f, maxRangeMm = 4000f)
    private var tofWarmUpCount = 0
    private val TOF_WARM_UP_SAMPLES = 3

    // Whether we found a real ToF sensor (not just proximity fallback)
    private var hasRealTof = false

    // Measurement state
    private enum class Mode { POINT, LINE, AREA }
    private var currentMode = Mode.POINT

    // Overlay data
    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var firstDistance: Float = 0f
    private var measuredResult = "--"

    // Calibration: reference object
    private var scaleFactor = 1f  // user-calibrated scale
    private var isCalibrating = false
    private var calibRefDistance = 0f  // known real distance for calibration

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 100
    }

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Log uncaught exceptions to file for debugging
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val logFile = java.io.File(getExternalFilesDir(null), "crash.log")
                logFile.appendText("${java.util.Date()} ${e.stackTraceToString()}\n\n")
            } catch (_: Exception) {}
            throw e
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }

        // Initialize ToF sensor detection
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        findTofSensor()

        setupUI()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CAMERA && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // Register ToF sensor listener
        tofSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Reopen camera if it was closed in onPause
        if (cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onPause() {
        // Unregister all sensor listeners
        sensorManager.unregisterListener(this)
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // ═══════════════════════════════════════════════════════════
    // ToF Sensor Detection (from tof-ranger)
    // ═══════════════════════════════════════════════════════════

    private fun findTofSensor() {
        try {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            tofSensor = null
            var sensorLabel = "未找到"

            // Round 1: Match by known Xiaomi custom type values
            for (tofType in KNOWN_TOF_TYPES) {
                for (s in allSensors) {
                    if (s.type == tofType) {
                        tofSensor = s
                        detectedTofType = tofType
                        sensorLabel = "${s.name} (type=$tofType)"
                        hasRealTof = true
                        break
                    }
                }
                if (tofSensor != null) break
            }

            // Round 2: Fuzzy match by name (non-standard types only)
            if (tofSensor == null) {
                for (s in allSensors) {
                    val name = s.name.lowercase(Locale.ROOT)
                    val type = s.type
                    // Skip standard sensor types (<65536)
                    if (type < 65536) continue
                    if (name.contains("tof") || name.contains("vl53") || name.contains("d-tof")
                        || name.contains("dtof") || name.contains("range")) {
                        tofSensor = s
                        detectedTofType = type
                        sensorLabel = "${s.name} (type=$type 匹配)"
                        hasRealTof = true
                        break
                    }
                }
            }

            // Round 3: Fallback to proximity sensor
            if (tofSensor == null) {
                tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                if (tofSensor != null) {
                    sensorLabel = "${tofSensor!!.name} (Proximity降级)"
                    hasRealTof = false
                }
            }

            // Update UI
            val statusPrefix = if (hasRealTof) "📐 " else "⚠️ "
            binding.tvSensor.text = "$statusPrefix$sensorLabel"

            Log.d(TAG, "ToF sensor: $sensorLabel (hasRealTof=$hasRealTof)")
        } catch (e: Exception) {
            Log.e(TAG, "findTofSensor failed", e)
            binding.tvSensor.text = "⚠️ 传感器检测异常"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SensorEventListener — ToF readings
    // ═══════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type

        // Only process ToF / proximity events
        val isTofEvent = (detectedTofType > 0 && type == detectedTofType)
                || type == Sensor.TYPE_PROXIMITY
        if (!isTofEvent) return

        val raw = event.values[0]

        // Warm-up: skip first few unstable samples
        if (tofWarmUpCount < TOF_WARM_UP_SAMPLES) {
            tofWarmUpCount++
            return
        }

        // Overflow / invalid check
        // Some devices report unreliable max range (e.g. Xiaomi returns 1mm)
        val overflowThresholdMm = tofSensor?.let { sensor ->
            val maxRange = sensor.maximumRange
            if (maxRange > 100) maxRange else 4000f // default 4m for VL53L1X
        } ?: 4000f

        if (raw <= 0 || raw >= overflowThresholdMm) {
            // Out of range — don't update
            return
        }

        // Apply filter pipeline: spike rejection → median → EMA
        val filtered = tofFilter.filter(raw)
        if (filtered > 0) {
            tofDistanceMm = filtered
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    // ═══════════════════════════════════════════════════════════
    // Camera
    // ═══════════════════════════════════════════════════════════

    private fun startCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            val ids = cameraManager!!.cameraIdList

            // Find back camera with max FOV
            var bestId: String? = null
            var bestFov = 0f

            for (id in ids) {
                val chars = cameraManager!!.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // Get focal length and sensor size
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal = focalLengths?.firstOrNull() ?: continue
                val sensor = sensorPhysicalSize ?: continue

                val hfov = 2 * Math.toDegrees(Math.atan((sensor.width / 2.0 / focal)))
                Log.d(TAG, "Camera $id: focal=${focal}mm sensor=${sensor.width}x${sensor.height}mm HFOV=${hfov}°")

                if (hfov > bestFov) {
                    bestFov = hfov.toFloat()
                    bestId = id
                    focalLengthMm = focal
                    sensorSize = SizeF(sensor.width, sensor.height)
                }
            }

            val id = bestId ?: ids.firstOrNull() ?: run {
                binding.tvSensor.text = "❌ 无可用摄像头"
                return
            }

            Log.d(TAG, "Using camera: $id (focal=${focalLengthMm}mm)")
            openCamera(id)

        } catch (e: Exception) {
            Log.e(TAG, "Camera init failed", e)
            binding.tvSensor.text = "❌ 相机错误"
        }
    }

    private fun openCamera(cameraId: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)

        cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera
                val tofStatus = if (hasRealTof) "✅ ToF" else "📷 AF估算"
                runOnUiThread { binding.tvSensor.text = tofStatus }

                try {
                    val surfaceView = binding.surfaceView
                    val holder = surfaceView.holder
                    if (holder.surface.isValid && surfaceView.width > 0) {
                        createPreviewSession(camera)
                    } else {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                surfaceView.post { createPreviewSession(camera) }
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) {}
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Camera session setup failed", e)
                }
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
                binding.tvSensor.text = "❌ 相机错误($error)"
            }
        }, backgroundHandler)
    }

    private fun createPreviewSession(camera: CameraDevice) {
        try {
            val surfaceView = binding.surfaceView
            val holder = surfaceView.holder
            val previewSurface = holder.surface

            // Set buffer size to match SurfaceView dimensions
            val chars = cameraManager!!.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseOptimalSize(
                map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(),
                surfaceView.width.coerceAtLeast(1), surfaceView.height.coerceAtLeast(1)
            )
            holder.setFixedSize(previewSize.width, previewSize.height)

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(previewSurface)

            // Continuous autofocus
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            // Enable 3A stats for focus distance
            requestBuilder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )

            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), captureCallback, backgroundHandler)
                            Log.d(TAG, "Preview started: ${previewSize}")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Preview failed", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview config failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSession failed", e)
        }
    }

    /**
     * Capture callback - reads focus distance from each frame (used as fallback).
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // Get focus distance in diopters (1/meters)
            val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (focusDiopters != null && focusDiopters > 0) {
                currentFocusDistance = 1f / focusDiopters * scaleFactor  // meters
            }
        }
    }

    /**
     * Tap-to-focus: trigger AF at tapped point, then read the resulting focus distance.
     */
    private fun triggerAutoFocus(screenX: Float, screenY: Float) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        val surfaceView = binding.surfaceView
        val chars = cameraManager!!.getCameraCharacteristics(device.id)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Map screen coords to sensor coords
        val scaleX = sensorRect.width().toFloat() / surfaceView.width
        val scaleY = sensorRect.height().toFloat() / surfaceView.height
        val focusX = (screenX * scaleX).toInt().coerceIn(0, sensorRect.width() - 1)
        val focusY = (screenY * scaleY).toInt().coerceIn(0, sensorRect.height() - 1)

        // Create AF region (small area around tap point)
        val regionSize = (sensorRect.width() * 0.1f).toInt().coerceAtLeast(50)
        val afRegion = MeteringRectangle(
            (focusX - regionSize / 2).coerceIn(0, sensorRect.width() - regionSize),
            (focusY - regionSize / 2).coerceIn(0, sensorRect.height() - regionSize),
            regionSize, regionSize,
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        try {
            // Trigger AF at tap point
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(binding.surfaceView.holder.surface)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (focusDiopters != null && focusDiopters > 0) {
                        currentFocusDistance = 1f / focusDiopters * scaleFactor
                        Log.d(TAG, "AF at ($screenX,$screenY): ${currentFocusDistance}m (diopters=$focusDiopters)")
                    }

                    // Return to continuous AF
                    try {
                        val resume = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        resume.addTarget(binding.surfaceView.holder.surface)
                        resume.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(resume.build(), captureCallback, backgroundHandler)
                    } catch (_: Exception) {}
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "AF trigger failed", e)
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        if (choices.isEmpty()) return Size(1920, 1080)
        // Use display size as fallback if view dimensions are invalid
        val realW: Int
        val realH: Int
        if (width > 1 && height > 1) {
            realW = width
            realH = height
        } else {
            val dm = resources.displayMetrics
            realW = dm.widthPixels.coerceAtLeast(1)
            realH = dm.heightPixels.coerceAtLeast(1)
        }
        // In portrait, camera buffer is landscape (rotated 90° in transform)
        // So pick size closest to our portrait ratio inverted (h:w → w:h)
        val targetRatio = realH.toFloat() / realW
        return choices.minByOrNull {
            Math.abs(it.width.toFloat() / it.height - targetRatio)
        } ?: choices.first()
    }

    // SurfaceView handles transform automatically — no Matrix needed

    // ═══════════════════════════════════════════════════════════
    // Distance estimation — ToF first, AF fallback
    // ═══════════════════════════════════════════════════════════

    /**
     * Get distance in meters.
     * Priority: ToF sensor (mm precision) → Camera2 AF distance (rough estimate)
     */
    // Buffer of recent ToF readings for tap-time averaging
    private val recentTofReadings = mutableListOf<Float>()
    private val TOF_AVERAGE_WINDOW = 7

    private fun getDistanceAt(screenX: Float, screenY: Float): Float? {
        // Priority 1: ToF sensor — collect multiple samples for accuracy
        if (tofDistanceMm > 0) {
            val distM = tofDistanceMm / 1000f

            // Collect samples over ~100ms for tap-time averaging
            recentTofReadings.add(distM)
            if (recentTofReadings.size > TOF_AVERAGE_WINDOW) {
                recentTofReadings.removeAt(0)
            }

            // Use median of recent readings (robust against outliers)
            if (recentTofReadings.size >= 3) {
                val sorted = recentTofReadings.sorted()
                return sorted[sorted.size / 2]
            }
            return distM
        }

        // Priority 2: Camera2 autofocus distance (fallback)
        val dist = currentFocusDistance
        return if (dist > 0) dist else null
    }

    /**
     * Compute FOV from camera intrinsics.
     */
    private fun getHfovDegrees(): Double {
        val sensor = sensorSize ?: return 65.0
        val focal = focalLengthMm.takeIf { it > 0 } ?: return 65.0
        return 2 * Math.toDegrees(Math.atan((sensor.width / 2.0 / focal)))
    }

    private fun getVfovDegrees(): Double {
        val sensor = sensorSize ?: return 50.0
        val focal = focalLengthMm.takeIf { it > 0 } ?: return 50.0
        return 2 * Math.toDegrees(Math.atan((sensor.height / 2.0 / focal)))
    }

    // ═══════════════════════════════════════════════════════════
    // Touch handling
    // ═══════════════════════════════════════════════════════════

    private fun onScreenTapped(x: Float, y: Float) {
        // Always trigger tap-to-focus
        triggerAutoFocus(x, y)

        // Small delay to let AF settle, then measure
        backgroundHandler?.postDelayed({
            runOnUiThread { measureAtPoint(x, y) }
        }, 300)
    }

    private fun measureAtPoint(x: Float, y: Float) {
        when (currentMode) {
            Mode.POINT -> handlePointTap(x, y)
            Mode.LINE -> handleLineTap(x, y)
            Mode.AREA -> handleAreaTap(x, y)
        }
    }

    private fun handlePointTap(x: Float, y: Float) {
        overlayPoints.clear()
        overlayPoints.add(PointF(x, y))

        val dist = getDistanceAt(x, y)
        measuredResult = if (dist != null) {
            String.format("%.2f m", dist)
        } else {
            "对焦中..."
        }
        binding.tvDistance.text = measuredResult
        updateOverlay()
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            firstPoint = PointF(x, y)
            firstDistance = getDistanceAt(x, y) ?: 0f
            overlayPoints.clear()
            overlayPoints.add(PointF(x, y))
            binding.tvDistance.text = "标记第二点..."
            updateOverlay()
        } else {
            val p1 = firstPoint!!
            val p2 = PointF(x, y)
            overlayPoints.add(p2)

            val d1 = firstDistance
            val d2 = getDistanceAt(x, y) ?: d1

            val viewW = binding.surfaceView.width.toFloat()
            val viewH = binding.surfaceView.height.toFloat()
            val dist = compute3DDistance(p1, p2, d1, d2, viewW, viewH)

            measuredResult = String.format("%.2f m", dist)
            binding.tvDistance.text = measuredResult
            binding.overlayView.lines = listOf(Pair(p1, p2))
            updateOverlay()
            firstPoint = null
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))

        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints)
            measuredResult = if (area > 0) String.format("%.2f m²", area) else "无法计算"
            binding.tvDistance.text = measuredResult
        } else {
            binding.tvDistance.text = "继续点击 (${overlayAreaPoints.size}/3+)"
        }

        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) {
            lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i + 1]))
        }
        if (overlayAreaPoints.size >= 3) {
            lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        }
        binding.overlayView.lines = lines
        binding.overlayView.points = overlayAreaPoints.toList()
        binding.overlayView.areaPoints = overlayAreaPoints.toList()
        binding.overlayView.invalidate()
    }

    private fun updateOverlay() {
        binding.overlayView.points = overlayPoints.toList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun compute3DDistance(
        p1: PointF, p2: PointF,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float
    ): Float {
        // Normalize to [-1, 1], where (0,0) = screen center
        val nx1 = (p1.x / viewW - 0.5f) * 2f
        val ny1 = (0.5f - p1.y / viewH) * 2f
        val nx2 = (p2.x / viewW - 0.5f) * 2f
        val ny2 = (0.5f - p2.y / viewH) * 2f

        // FOV: full angle from left edge to right edge
        // Normalized coord * 1.0 = edge → angle = FOV/2 from center
        // So: angle = nx * FOV/2, and x = d * tan(angle)
        val hfov = Math.toRadians(getHfovDegrees())
        val vfov = Math.toRadians(getVfovDegrees())

        val x1 = d1 * Math.tan(nx1 * hfov / 2).toFloat()
        val y1 = d1 * Math.tan(ny1 * vfov / 2).toFloat()
        val x2 = d2 * Math.tan(nx2 * hfov / 2).toFloat()
        val y2 = d2 * Math.tan(ny2 * vfov / 2).toFloat()

        val dx = x1 - x2
        val dy = y1 - y2
        val dz = d1 - d2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()
        val hfov = Math.toRadians(getHfovDegrees())
        val vfov = Math.toRadians(getVfovDegrees())

        // Convert each screen point to 3D world coordinates using its own depth
        val worldPoints = points.map { p ->
            val depth = getDistanceAt(p.x, p.y) ?: return 0f
            val nx = (p.x / viewW - 0.5f) * 2f
            val ny = (0.5f - p.y / viewH) * 2f
            val wx = depth * Math.tan(nx * hfov / 2).toFloat()
            val wy = depth * Math.tan(ny * vfov / 2).toFloat()
            Triple(wx, wy, depth)
        }

        // Compute 3D polygon area using cross product method
        // Project onto best-fit plane or use triangulation from centroid
        val n = worldPoints.size
        // Centroid
        var cx = 0f; var cy = 0f; var cz = 0f
        for (p in worldPoints) { cx += p.first; cy += p.second; cz += p.third }
        cx /= n; cy /= n; cz /= n

        // Sum triangle areas from centroid
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            // Two vectors from centroid
            val v1x = worldPoints[i].first - cx
            val v1y = worldPoints[i].second - cy
            val v1z = worldPoints[i].third - cz
            val v2x = worldPoints[j].first - cx
            val v2y = worldPoints[j].second - cy
            val v2z = worldPoints[j].third - cz
            // Cross product magnitude = area of parallelogram
            val cpx = v1y * v2z - v1z * v2y
            val cpy = v1z * v2x - v1x * v2z
            val cpz = v1x * v2y - v1y * v2x
            area += Math.sqrt((cpx * cpx + cpy * cpy + cpz * cpz).toDouble())
        }
        return (area / 2.0).toFloat()
    }

    // ═══════════════════════════════════════════════════════════
    // Calibration
    // ═══════════════════════════════════════════════════════════

    private fun startCalibration() {
        isCalibrating = true
        binding.tvDistance.text = "放一个已知尺寸的物体\n点击近端标记距离"
        Toast.makeText(this, "放一把尺子或A4纸，点击近端", Toast.LENGTH_LONG).show()
    }

    // ═══════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.btnPointMode.setOnClickListener {
            currentMode = Mode.POINT
            binding.tvMode.text = "📏 点击测距"
            highlightButton(it as com.google.android.material.button.MaterialButton)
        }
        binding.btnLineMode.setOnClickListener {
            currentMode = Mode.LINE
            binding.tvMode.text = "📐 两点测距"
            firstPoint = null
            highlightButton(it as com.google.android.material.button.MaterialButton)
        }
        binding.btnAreaMode.setOnClickListener {
            currentMode = Mode.AREA
            binding.tvMode.text = "⬜ 面积测量"
            highlightButton(it as com.google.android.material.button.MaterialButton)
        }
        binding.btnReset.setOnClickListener { resetMeasurement() }
        binding.btnSave.setOnClickListener { saveMeasurement() }
        highlightButton(binding.btnPointMode)
    }

    private fun highlightButton(btn: com.google.android.material.button.MaterialButton) {
        listOf(binding.btnPointMode, binding.btnLineMode, binding.btnAreaMode).forEach {
            it.setBackgroundColor(0x00000000)
        }
        btn.setBackgroundColor(0x3300FF88.toInt())
    }

    private fun resetMeasurement() {
        overlayPoints.clear()
        overlayAreaPoints.clear()
        firstPoint = null
        measuredResult = "--"
        recentTofReadings.clear()
        binding.tvDistance.text = "--"
        binding.overlayView.points = emptyList()
        binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun saveMeasurement() {
        if (measuredResult == "--" || measuredResult.contains("无")) {
            Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val surfaceView = binding.surfaceView
            val bitmap = Bitmap.createBitmap(
                surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val loc = IntArray(2)
                surfaceView.getLocationInWindow(loc)
                android.view.PixelCopy.request(
                    window,
                    android.graphics.Rect(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + surfaceView.height),
                    bitmap,
                    { copyResult ->
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            // Draw overlay on top
                            val canvas = Canvas(bitmap)
                            binding.overlayView.draw(canvas)
                            try {
                                android.provider.MediaStore.Images.Media.insertImage(
                                    contentResolver, bitmap,
                                    "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult"
                                )
                                runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() }
                            } catch (e: Exception) {
                                runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                        } else {
                            runOnUiThread { Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    Handler(mainLooper)
                )
            } else {
                // Fallback: capture overlay only
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                binding.overlayView.draw(canvas)
                android.provider.MediaStore.Images.Media.insertImage(
                    contentResolver, bitmap,
                    "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult"
                )
                Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Background thread
    // ═══════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        // Clean up any existing thread first
        if (backgroundThread?.isAlive == true) {
            backgroundThread?.quitSafely()
            try { backgroundThread?.join(1000) } catch (_: Exception) {}
        }
        backgroundThread = HandlerThread("CameraBG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (_: Exception) {}
    }

    private fun closeCamera() {
        try {
            // Use timeout to avoid deadlock if camera callback never arrives
            cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            cameraOpenCloseLock.release()
        }
    }
}
