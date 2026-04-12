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
import android.media.ImageReader
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
 * AR Measure App — Camera2 RGB preview + DEPTH16 depth map + dToF sensor fallback.
 *
 * Depth source priority:
 *   1. Camera2 DEPTH16 — per-pixel depth map, query at tap coordinates
 *   2. ToF sensor (SensorManager) — single-point distance (center only)
 *   3. Camera2 LENS_FOCUS_DISTANCE — autofocus fallback
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding

    // ── Camera ──────────────────────────────────────────────
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Camera intrinsics
    private var sensorSize: SizeF? = null
    private var focalLengthMm: Float = 0f

    // ── DEPTH16 depth map ──────────────────────────────────
    private var depthReader: ImageReader? = null
    private var depthCameraDevice: CameraDevice? = null

    @Volatile
    private var depthBuffer: ShortArray? = null
    private var depthWidth: Int = 0
    private var depthHeight: Int = 0

    @Volatile
    private var hasDepthMap = false
    private var depthSensorActiveArray: Rect? = null

    // RGB sensor active array (for preview→depth coordinate mapping)
    private var rgbSensorActiveArray: Rect? = null

    // ── AF distance fallback ───────────────────────────────
    @Volatile
    private var currentFocusDistance: Float = -1f  // meters

    // ── ToF sensor (single-point fallback) ─────────────────
    private val KNOWN_TOF_TYPES = intArrayOf(33171040, 33171041, 65570, 65572)
    private lateinit var sensorManager: SensorManager
    private var tofSensor: Sensor? = null
    private var detectedTofType: Int = 0
    private var hasRealTof = false

    @Volatile
    private var tofDistanceMm: Float = -1f
    private val tofFilter = DistanceFilter(windowSize = 5, alpha = 0.4f, maxJumpMm = 800f, maxRangeMm = 5000f)
    private var tofWarmUpCount = 0
    private val TOF_WARM_UP_SAMPLES = 3

    // ── Measurement state ──────────────────────────────────
    private enum class Mode { POINT, LINE, AREA }
    private var currentMode = Mode.POINT

    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var firstDistance: Float = 0f
    private var measuredResult = "--"

    // Calibration
    private var scaleFactor = 1f
    private var isCalibrating = false

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 100
    }

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                java.io.File(getExternalFilesDir(null), "crash.log")
                    .appendText("${java.util.Date()} ${e.stackTraceToString()}\n\n")
            } catch (_: Exception) {}
            throw e
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }

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
        tofSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // ═══════════════════════════════════════════════════════════
    // ToF Sensor Detection
    // ═══════════════════════════════════════════════════════════

    private fun findTofSensor() {
        try {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            tofSensor = null
            var sensorLabel = "未找到"

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

            if (tofSensor == null) {
                for (s in allSensors) {
                    val name = s.name.lowercase(Locale.ROOT)
                    if (s.type < 65536) continue
                    if (name.contains("tof") || name.contains("vl53") || name.contains("d-tof")
                        || name.contains("dtof") || name.contains("range")) {
                        tofSensor = s
                        detectedTofType = s.type
                        sensorLabel = "${s.name} (type=${s.type} 匹配)"
                        hasRealTof = true
                        break
                    }
                }
            }

            if (tofSensor == null) {
                tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                if (tofSensor != null) {
                    sensorLabel = "${tofSensor!!.name} (Proximity降级)"
                    hasRealTof = false
                }
            }

            binding.tvSensor.text = "${if (hasRealTof) "📐 " else "⚠️ "}$sensorLabel"
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
        val isTofEvent = (detectedTofType > 0 && event.sensor.type == detectedTofType)
                || event.sensor.type == Sensor.TYPE_PROXIMITY
        if (!isTofEvent) return

        val raw = event.values[0]
        if (tofWarmUpCount < TOF_WARM_UP_SAMPLES) { tofWarmUpCount++; return }

        val overflowThresholdMm = tofSensor?.let { maxOf(it.maximumRange, 4000f) } ?: 4000f
        if (raw <= 0 || raw >= overflowThresholdMm) return

        val filtered = tofFilter.filter(raw)
        if (filtered > 0) tofDistanceMm = filtered
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ═══════════════════════════════════════════════════════════
    // Camera — open RGB + depth
    // ═══════════════════════════════════════════════════════════

    private fun startCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            val ids = cameraManager!!.cameraIdList
            var bestRgbId: String? = null
            var bestFov = 0f
            var depthCamId: String? = null

            for (id in ids) {
                val chars = cameraManager!!.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

                // Check for depth-capable camera
                if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
                    depthCamId = id
                    Log.d(TAG, "Depth camera found: $id")
                }

                // Find best back-facing RGB camera
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal = focalLengths?.firstOrNull() ?: continue
                    val sensor = sensorPhysicalSize ?: continue

                    val hfov = 2 * Math.toDegrees(Math.atan((sensor.width / 2.0 / focal)))
                    Log.d(TAG, "Camera $id: focal=${focal}mm sensor=${sensor.width}x${sensor.height}mm HFOV=${hfov}°")

                    if (hfov > bestFov) {
                        bestFov = hfov.toFloat()
                        bestRgbId = id
                        focalLengthMm = focal
                        sensorSize = SizeF(sensor.width, sensor.height)
                    }
                }
            }

            // If depth camera is also a back-facing RGB, prefer it
            if (depthCamId != null && bestRgbId != null) {
                val depthChars = cameraManager!!.getCameraCharacteristics(depthCamId)
                val depthFacing = depthChars.get(CameraCharacteristics.LENS_FACING)
                if (depthFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    bestRgbId = depthCamId
                    val focalLengths = depthChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorPhysicalSize = depthChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focalLengths != null && sensorPhysicalSize != null) {
                        focalLengthMm = focalLengths.firstOrNull() ?: focalLengthMm
                        sensorSize = SizeF(sensorPhysicalSize.width, sensorPhysicalSize.height)
                    }
                }
            }

            val rgbId = bestRgbId ?: ids.firstOrNull() ?: run {
                binding.tvSensor.text = "❌ 无可用摄像头"
                return
            }

            rgbSensorActiveArray = cameraManager!!.getCameraCharacteristics(rgbId)
                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            if (depthCamId != null) {
                depthSensorActiveArray = cameraManager!!.getCameraCharacteristics(depthCamId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            }

            hasDepthMap = depthCamId != null
            Log.d(TAG, "RGB=$rgbId, Depth=$depthCamId, hasDepthMap=$hasDepthMap")

            openCamera(rgbId, depthCamId)

        } catch (e: Exception) {
            Log.e(TAG, "Camera init failed", e)
            binding.tvSensor.text = "❌ 相机错误"
        }
    }

    private fun openCamera(rgbId: String, depthId: String?) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)

        cameraManager!!.openCamera(rgbId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera
                val depthStatus = if (hasDepthMap) "✅ DEPTH16" else if (hasRealTof) "📐 ToF" else "📷 AF"
                runOnUiThread { binding.tvSensor.text = depthStatus }

                // Open depth camera if different from RGB
                if (depthId != null && depthId != rgbId) {
                    openDepthCamera(depthId)
                }

                setupPreviewSession(camera, depthId == rgbId)
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

    /**
     * Open a separate depth-only camera (when depth and RGB are on different physical sensors).
     */
    private fun openDepthCamera(depthId: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            cameraManager!!.openCamera(depthId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    depthCameraDevice = camera
                    setupDepthOnlySession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    depthCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Depth camera error: $error")
                    camera.close()
                    depthCameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open depth camera", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Preview session (RGB + optional depth on same camera)
    // ═══════════════════════════════════════════════════════════

    private fun setupPreviewSession(camera: CameraDevice, sameCameraHasDepth: Boolean) {
        try {
            val surfaceView = binding.surfaceView
            val holder = surfaceView.holder
            val previewSurface = holder.surface

            val chars = cameraManager!!.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseOptimalSize(
                map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray(),
                surfaceView.width.coerceAtLeast(1), surfaceView.height.coerceAtLeast(1)
            )
            holder.setFixedSize(previewSize.width, previewSize.height)

            val targets = mutableListOf<Surface>(previewSurface)
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(previewSurface)

            // If same camera supports depth, add DEPTH16 reader as second output
            if (sameCameraHasDepth && map != null) {
                val depthSizes = map.getOutputSizes(ImageFormat.DEPTH16)
                if (depthSizes != null && depthSizes.isNotEmpty()) {
                    val depthSize = depthSizes.maxByOrNull { it.width * it.height }!!
                    depthWidth = depthSize.width
                    depthHeight = depthSize.height

                    depthReader = ImageReader.newInstance(depthWidth, depthHeight, ImageFormat.DEPTH16, 2)
                    depthReader!!.setOnImageAvailableListener({ reader ->
                        processDepthImage(reader)
                    }, backgroundHandler)

                    targets.add(depthReader!!.surface)
                    requestBuilder.addTarget(depthReader!!.surface)

                    Log.d(TAG, "DEPTH16 on same camera: ${depthWidth}x${depthHeight}")
                }
            }

            // Continuous autofocus
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            requestBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)

            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), captureCallback, backgroundHandler)
                        Log.d(TAG, "Preview started: ${previewSize}, depth targets=${targets.size}")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Preview failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Preview config failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setupPreviewSession failed", e)
        }
    }

    /**
     * Depth-only session (separate depth camera, no preview).
     */
    private fun setupDepthOnlySession(camera: CameraDevice) {
        try {
            val chars = cameraManager!!.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val depthSizes = map?.getOutputSizes(ImageFormat.DEPTH16) ?: return
            if (depthSizes.isEmpty()) return

            val depthSize = depthSizes.maxByOrNull { it.width * it.height }!!
            depthWidth = depthSize.width
            depthHeight = depthSize.height

            depthReader = ImageReader.newInstance(depthWidth, depthHeight, ImageFormat.DEPTH16, 2)
            depthReader!!.setOnImageAvailableListener({ reader ->
                processDepthImage(reader)
            }, backgroundHandler)

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(depthReader!!.surface)

            camera.createCaptureSession(listOf(depthReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        Log.d(TAG, "Depth-only session: ${depthWidth}x${depthHeight}")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Depth session failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Depth session config failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setupDepthOnlySession failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AF capture callback (fallback distance)
    // ═══════════════════════════════════════════════════════════

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (focusDiopters != null && focusDiopters > 0) {
                currentFocusDistance = 1f / focusDiopters * scaleFactor
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DEPTH16 processing — the core fix
    // ═══════════════════════════════════════════════════════════

    /**
     * Read DEPTH16 image from ImageReader and store to depthBuffer.
     * Runs on background thread.
     */
    private fun processDepthImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride / 2  // shorts per row
            val pixelStride = plane.pixelStride / 2
            val w = image.width
            val h = image.height

            val buf = ShortArray(w * h)
            for (row in 0 until h) {
                val rowStart = row * rowStride
                for (col in 0 until w) {
                    val idx = rowStart + col * pixelStride
                    if (idx < buffer.remaining()) {
                        buf[row * w + col] = buffer.getShort(idx * 2)
                    }
                }
            }
            depthBuffer = buf
            depthWidth = w
            depthHeight = h
        } catch (e: Exception) {
            Log.e(TAG, "processDepthImage error", e)
        } finally {
            image.close()
        }
    }

    /**
     * Query depth at screen coordinates from the depth map.
     * Returns depth in centimeters, or null if unavailable.
     *
     * Uses 5x5 median kernel around the mapped point for noise rejection.
     */
    private fun getDepthAtScreenPoint(screenX: Float, screenY: Float): Float? {
        val buf = depthBuffer ?: return null
        if (buf.isEmpty() || depthWidth <= 0 || depthHeight <= 0) return null

        // Map screen coordinates to depth image coordinates
        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return null

        val depthX: Int
        val depthY: Int

        if (depthSensorActiveArray != null && rgbSensorActiveArray != null
            && depthCameraDevice?.id != cameraDevice?.id) {
            // Two separate physical cameras — map via sensor active arrays
            val rgbArray = rgbSensorActiveArray!!
            val depthArray = depthSensorActiveArray!!

            val rgbSensorX = screenX / viewW * rgbArray.width()
            val rgbSensorY = screenY / viewH * rgbArray.height()

            depthX = (rgbSensorX / rgbArray.width() * depthArray.width()).toInt()
                .coerceIn(0, depthWidth - 1)
            depthY = (rgbSensorY / rgbArray.height() * depthArray.height()).toInt()
                .coerceIn(0, depthHeight - 1)
        } else {
            // Same camera or simple mapping — direct linear
            depthX = (screenX / viewW * depthWidth).toInt().coerceIn(0, depthWidth - 1)
            depthY = (screenY / viewH * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        }

        // 5x5 kernel median
        val radius = 2
        val samples = mutableListOf<Float>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = (depthX + dx).coerceIn(0, depthWidth - 1)
                val py = (depthY + dy).coerceIn(0, depthHeight - 1)
                val raw = buf[py * depthWidth + px].toInt() and 0xFFFF

                when {
                    raw == 0 -> {}  // invalid
                    raw >= 65534 -> {}  // too close/far
                    else -> samples.add(raw.toFloat())  // mm
                }
            }
        }

        if (samples.size < 3) return null

        samples.sort()
        val medianMm = samples[samples.size / 2]

        // EMA smoothing
        val alpha = 0.4f
        val smoothed = if (lastDepthEma < 0) {
            lastDepthEma = medianMm
            medianMm
        } else {
            lastDepthEma = alpha * medianMm + (1 - alpha) * lastDepthEma
            lastDepthEma
        }

        return smoothed / 10f  // mm -> cm
    }

    private var lastDepthEma = -1f

    // ═══════════════════════════════════════════════════════════
    // Distance estimation — unified entry point
    // ═══════════════════════════════════════════════════════════

    /**
     * Get distance in centimeters at the given screen point.
     * Priority: DEPTH16 map -> ToF sensor -> AF distance
     */
    private fun getDistanceAt(screenX: Float, screenY: Float): Float? {
        // 1. DEPTH16 per-pixel depth (most accurate, per-point)
        if (hasDepthMap) {
            val depth = getDepthAtScreenPoint(screenX, screenY)
            if (depth != null && depth > 0) return depth
        }

        // 2. ToF sensor — center only, no spatial mapping
        if (tofDistanceMm > 0) {
            return tofDistanceMm / 10f  // mm -> cm
        }

        // 3. AF distance fallback
        val dist = currentFocusDistance
        return if (dist > 0) dist * 100f else null  // m -> cm
    }

    // ═══════════════════════════════════════════════════════════
    // FOV
    // ═══════════════════════════════════════════════════════════

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
        triggerAutoFocus(x, y)
        backgroundHandler?.postDelayed({
            runOnUiThread { measureAtPoint(x, y) }
        }, 300)
    }

    private fun triggerAutoFocus(screenX: Float, screenY: Float) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        val surfaceView = binding.surfaceView
        val chars = cameraManager!!.getCameraCharacteristics(device.id)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val scaleX = sensorRect.width().toFloat() / surfaceView.width
        val scaleY = sensorRect.height().toFloat() / surfaceView.height
        val focusX = (screenX * scaleX).toInt().coerceIn(0, sensorRect.width() - 1)
        val focusY = (screenY * scaleY).toInt().coerceIn(0, sensorRect.height() - 1)

        val regionSize = (sensorRect.width() * 0.1f).toInt().coerceAtLeast(50)
        val afRegion = MeteringRectangle(
            (focusX - regionSize / 2).coerceIn(0, sensorRect.width() - regionSize),
            (focusY - regionSize / 2).coerceIn(0, sensorRect.height() - regionSize),
            regionSize, regionSize, MeteringRectangle.METERING_WEIGHT_MAX
        )

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(binding.surfaceView.holder.surface)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
                ) {
                    val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (focusDiopters != null && focusDiopters > 0) {
                        currentFocusDistance = 1f / focusDiopters * scaleFactor
                    }
                    // Resume continuous AF (only if depth reader not on same session)
                    if (!hasDepthMap) {
                        try {
                            val resume = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            resume.addTarget(binding.surfaceView.holder.surface)
                            resume.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            session.setRepeatingRequest(resume.build(), captureCallback, backgroundHandler)
                        } catch (_: Exception) {}
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "AF trigger failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Measurement handlers
    // ═══════════════════════════════════════════════════════════

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
        measuredResult = if (dist != null) String.format("%.0f cm", dist) else "对焦中..."
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

            measuredResult = String.format("%.0f cm", dist)
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
            measuredResult = if (area > 0) String.format("%.0f cm²", area) else "无法计算"
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

    // ═══════════════════════════════════════════════════════════
    // 3D distance & area computation
    // ═══════════════════════════════════════════════════════════

    /**
     * Compute 3D distance between two screen points given their depths.
     * d1, d2 are in centimeters. Returns centimeters.
     */
    private fun compute3DDistance(
        p1: PointF, p2: PointF,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float
    ): Float {
        val nx1 = (p1.x / viewW - 0.5f) * 2f
        val ny1 = (0.5f - p1.y / viewH) * 2f
        val nx2 = (p2.x / viewW - 0.5f) * 2f
        val ny2 = (0.5f - p2.y / viewH) * 2f

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

    /**
     * Compute area from polygon points using per-point depth when available.
     */
    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()
        val hfov = Math.toRadians(getHfovDegrees())

        // If depth map available, use per-point 3D coordinates
        if (hasDepthMap) {
            val depths = points.map { getDistanceAt(it.x, it.y) }
            if (depths.all { it != null && it > 0 }) {
                val pts3d = points.zip(depths).map { (p, d) ->
                    val nx = (p.x / viewW - 0.5f) * 2f
                    val ny = (0.5f - p.y / viewH) * 2f
                    val cx = d!! * Math.tan(nx * Math.toRadians(getHfovDegrees()) / 2).toFloat()
                    val cy = d * Math.tan(ny * Math.toRadians(getVfovDegrees()) / 2).toFloat()
                    Pair(cx, cy)
                }

                var area2d = 0.0
                val n = pts3d.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    area2d += pts3d[i].first * pts3d[j].second
                    area2d -= pts3d[j].first * pts3d[i].second
                }
                return Math.abs(area2d / 2.0).toFloat()  // already cm²
            }
        }

        // Fallback: average depth, flat-plane approximation
        val avgDist = getDistanceAt(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        ) ?: return 0f

        val viewWidthM = (2 * (avgDist / 100f) * Math.tan(hfov / 2)).toFloat()
        val scale = viewWidthM / viewW

        var areaPixels = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            areaPixels += points[i].x * points[j].y
            areaPixels -= points[j].x * points[i].y
        }
        return (Math.abs(areaPixels / 2.0) * scale * scale * 10000.0).toFloat()  // m² -> cm²
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
        lastDepthEma = -1f
        measuredResult = "--"
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
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val loc = IntArray(2)
                surfaceView.getLocationInWindow(loc)
                android.view.PixelCopy.request(window,
                    android.graphics.Rect(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + surfaceView.height),
                    bitmap, { copyResult ->
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            val canvas = Canvas(bitmap)
                            binding.overlayView.draw(canvas)
                            try {
                                android.provider.MediaStore.Images.Media.insertImage(
                                    contentResolver, bitmap,
                                    "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult")
                                runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() }
                            } catch (e: Exception) {
                                runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }, Handler(mainLooper))
            } else {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                binding.overlayView.draw(canvas)
                android.provider.MediaStore.Images.Media.insertImage(
                    contentResolver, bitmap,
                    "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult")
                Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        if (choices.isEmpty()) return Size(1920, 1080)
        val realW = if (width > 1) width else resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val realH = if (height > 1) height else resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val targetRatio = realH.toFloat() / realW
        return choices.minByOrNull {
            Math.abs(it.width.toFloat() / it.height - targetRatio)
        } ?: choices.first()
    }

    private fun startBackgroundThread() {
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
            cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)
            captureSession?.close(); captureSession = null
            depthReader?.close(); depthReader = null
            depthCameraDevice?.close(); depthCameraDevice = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            cameraOpenCloseLock.release()
        }
    }
}
