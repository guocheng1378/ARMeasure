package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.graphics.SizeF
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.armeasure.app.databinding.ActivityMainBinding
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * AR Measure App - using Camera2 autofocus distance + reference calibration.
 *
 * Since Xiaomi 17 Pro Max doesn't expose dToF via standard Camera2 API,
 * we use LENS_FOCUS_DISTANCE (diopters) to estimate distance.
 * Combined with reference object calibration for better accuracy.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Focus distance (meters) - updated continuously
    @Volatile private var currentFocusDistance: Float = -1f

    // Camera intrinsics for FOV calculation
    private var sensorSize: SizeF? = null
    private var focalLengthMm: Float = 0f

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }

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
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
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
                binding.tvSensor.text = "📷 自动对焦测距"
                createPreviewSession(camera)
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
        val textureView = binding.textureView
        val texture = textureView.surfaceTexture ?: return

        // Set buffer size
        val chars = cameraManager!!.getCameraCharacteristics(camera.id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = chooseOptimalSize(
            map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(),
            textureView.width.coerceAtLeast(1), textureView.height.coerceAtLeast(1)
        )
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

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
    }

    /**
     * Capture callback - reads focus distance from each frame.
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

        val textureView = binding.textureView
        val chars = cameraManager!!.getCameraCharacteristics(device.id)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Map screen coords to sensor coords
        val scaleX = sensorRect.width().toFloat() / textureView.width
        val scaleY = sensorRect.height().toFloat() / textureView.height
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
            request.addTarget(Surface(binding.textureView.surfaceTexture))
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
                        resume.addTarget(Surface(binding.textureView.surfaceTexture))
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
        val targetRatio = width.toFloat() / height
        return choices.minByOrNull {
            Math.abs(it.width.toFloat() / it.height - targetRatio)
        } ?: choices.first()
    }

    // ═══════════════════════════════════════════════════════════
    // Distance estimation
    // ═══════════════════════════════════════════════════════════

    /**
     * Get distance at screen point.
     * Primary: tap-to-focus → read LENS_FOCUS_DISTANCE
     * Fallback: continuous AF distance
     */
    private fun getDistanceAt(screenX: Float, screenY: Float): Float? {
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

            val viewW = binding.textureView.width.toFloat()
            val viewH = binding.textureView.height.toFloat()
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

    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        // Average focus distance
        val avgDist = currentFocusDistance.takeIf { it > 0 } ?: return 0f

        val viewW = binding.textureView.width.toFloat()
        val hfov = Math.toRadians(getHfovDegrees())
        val viewWidthM = (2 * avgDist * Math.tan(hfov / 2)).toFloat()
        val scale = viewWidthM / viewW

        var areaPixels = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            areaPixels += points[i].x * points[j].y
            areaPixels -= points[j].x * points[i].y
        }
        return (Math.abs(areaPixels / 2.0) * scale * scale).toFloat()
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
            val bitmap = Bitmap.createBitmap(
                binding.textureView.width, binding.textureView.height, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            binding.textureView.draw(canvas)
            binding.overlayView.draw(canvas)
            android.provider.MediaStore.Images.Media.insertImage(
                contentResolver, bitmap,
                "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult"
            )
            Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Background thread
    // ═══════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (_: Exception) {}
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: InterruptedException) {} finally { cameraOpenCloseLock.release() }
    }
}
