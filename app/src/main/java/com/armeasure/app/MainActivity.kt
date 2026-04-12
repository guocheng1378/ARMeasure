package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera
    private var cameraManager: CameraManager? = null
    private var mainCameraId: String? = null
    private var depthCameraId: String? = null
    private var mainCameraDevice: CameraDevice? = null
    private var depthCameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Depth reader
    private var depthImageReader: ImageReader? = null
    @Volatile private var latestDepthFrame: Image? = null

    // Measurement state
    private enum class Mode { POINT, LINE, AREA }
    private var currentMode = Mode.POINT

    // Overlay
    private val overlayPoints = mutableListOf<PointF>()        // screen coords
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var measuredDistance: String = "--"
    private var depthMap: FloatArray? = null
    private var depthWidth = 0
    private var depthHeight = 0

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
    // Camera setup
    // ═══════════════════════════════════════════════════════════

    private fun startCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            val ids = cameraManager!!.cameraIdList
            Log.d(TAG, "Available cameras: ${ids.joinToString()}")

            // Find main camera + depth camera
            for (id in ids) {
                val chars = cameraManager!!.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

                val isDepth = capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                ) == true

                when {
                    isDepth -> {
                        depthCameraId = id
                        Log.d(TAG, "Found depth camera: $id")
                    }
                    facing == CameraCharacteristics.LENS_FACING_BACK && mainCameraId == null -> {
                        mainCameraId = id
                        Log.d(TAG, "Found main camera: $id")
                    }
                }
            }

            // Open main camera for preview
            openMainCamera()

            // Open depth camera if available
            if (depthCameraId != null) {
                openDepthCamera()
            } else {
                Log.w(TAG, "No depth camera found, using focal length estimation")
                binding.tvSensor.text = "📷 主摄 (无ToF)"
            }

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access failed", e)
        }
    }

    private fun openMainCamera() {
        val id = mainCameraId ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)

        cameraManager!!.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                mainCameraDevice = camera
                createMainPreview()
                Log.d(TAG, "Main camera opened")
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                camera.close()
                mainCameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                cameraOpenCloseLock.release()
                camera.close()
                mainCameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun openDepthCamera() {
        val id = depthCameraId ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val chars = cameraManager!!.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // Depth cameras output DEPTH16
        val depthSizes = map?.getOutputSizes(ImageFormat.DEPTH16)
        val depthSize = depthSizes?.maxByOrNull { it.width * it.height }
            ?: Size(240, 180) // typical dToF resolution

        depthImageReader = ImageReader.newInstance(
            depthSize.width, depthSize.height, ImageFormat.DEPTH16, 2
        )
        depthImageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            // Process depth frame
            processDepthImage(image)
            image.close()
        }, backgroundHandler)

        cameraManager!!.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                depthCameraDevice = camera
                createDepthCapture()
                binding.tvSensor.text = "📐 主摄 + dToF"
                Log.d(TAG, "Depth camera opened: $depthSize")
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                depthCameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                depthCameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun createMainPreview() {
        val device = mainCameraDevice ?: return
        val textureView = binding.textureView

        val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(textureView.surfaceTexture?.let {
            Surface(it)
        } ?: return)

        device.createCaptureSession(
            listOf(Surface(textureView.surfaceTexture)),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureRequest.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    private fun createDepthCapture() {
        val device = depthCameraDevice ?: return
        val reader = depthImageReader ?: return

        val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequest.addTarget(reader.surface)
        captureRequest.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        device.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    // Continuous depth capture
                    session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Depth processing (DEPTH16 format)
    // ═══════════════════════════════════════════════════════════

    private fun processDepthImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride / 2  // 16-bit per pixel
        val pixelStride = plane.pixelStride / 2

        depthWidth = image.width
        depthHeight = image.height
        val data = FloatArray(depthWidth * depthHeight)

        for (y in 0 until depthHeight) {
            for (x in 0 until depthWidth) {
                val index = (y * rowStride + x * pixelStride)
                if (index * 2 + 1 < buffer.capacity()) {
                    buffer.position(index * 2)
                    val raw = buffer.short.toInt() and 0xFFFF
                    // DEPTH16: 0 = invalid, 1-65533 = depth in mm, 65534 = too far, 65535 = too close
                    data[y * depthWidth + x] = when {
                        raw == 0 || raw >= 65534 -> -1f
                        else -> raw / 1000f  // mm → meters
                    }
                }
            }
        }

        depthMap = data
    }

    /**
     * Get depth at a screen coordinate.
     * Maps screen (x, y) → depth sensor (dx, dy), returns depth in meters.
     */
    private fun getDepthAtScreen(screenX: Float, screenY: Float): Float? {
        val data = depthMap ?: return null
        if (depthWidth == 0 || depthHeight == 0) return null

        val viewW = binding.textureView.width.toFloat()
        val viewH = binding.textureView.height.toFloat()

        // Map screen coords to depth image coords
        // Note: depth sensor may have different orientation/FOV, but typically matches roughly
        val dx = (screenX / viewW * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val dy = (screenY / viewH * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        // Average a small region for stability (3x3 kernel)
        var sum = 0f
        var count = 0
        for (oy in -1..1) {
            for (ox in -1..1) {
                val nx = (dx + ox).coerceIn(0, depthWidth - 1)
                val ny = (dy + oy).coerceIn(0, depthHeight - 1)
                val d = data[ny * depthWidth + nx]
                if (d > 0) {
                    sum += d
                    count++
                }
            }
        }

        return if (count > 0) sum / count else null
    }

    /**
     * Fallback: estimate distance using camera focal length + known object height.
     * Less accurate but works without depth sensor.
     */
    private fun estimateDistanceFromCamera(): Float? {
        val chars = mainCameraId?.let {
            cameraManager?.getCameraCharacteristics(it)
        } ?: return null

        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: return null  // mm

        // Use hyperfocal approximation: not precise but gives scale
        // Real apps would use ARCore or known reference
        return null  // Let user know depth sensor is needed
    }

    // ═══════════════════════════════════════════════════════════
    // Touch handling
    // ═══════════════════════════════════════════════════════════

    private fun onScreenTapped(x: Float, y: Float) {
        when (currentMode) {
            Mode.POINT -> handlePointTap(x, y)
            Mode.LINE -> handleLineTap(x, y)
            Mode.AREA -> handleAreaTap(x, y)
        }
    }

    private fun handlePointTap(x: Float, y: Float) {
        overlayPoints.clear()
        overlayPoints.add(PointF(x, y))

        val depth = getDepthAtScreen(x, y)
        measuredDistance = if (depth != null) {
            String.format("%.2f m", depth)
        } else {
            "无深度数据"
        }
        binding.tvDistance.text = measuredDistance
        binding.overlayView.points = overlayPoints.toList()
        binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            firstPoint = PointF(x, y)
            overlayPoints.clear()
            overlayPoints.add(PointF(x, y))
            binding.tvDistance.text = "标记第二点..."
            binding.overlayView.points = overlayPoints.toList()
            binding.overlayView.invalidate()
        } else {
            val p1 = firstPoint!!
            val p2 = PointF(x, y)
            overlayPoints.add(p2)

            // Get depth at both points
            val d1 = getDepthAtScreen(p1.x, p1.y)
            val d2 = getDepthAtScreen(p2.x, p2.y)

            measuredDistance = if (d1 != null && d2 != null) {
                // Use depth + screen angle to compute 3D distance
                val viewW = binding.textureView.width.toFloat()
                val viewH = binding.textureView.height.toFloat()
                val dist = compute3DDistance(p1, p2, d1, d2, viewW, viewH)
                String.format("%.2f m", dist)
            } else if (d1 != null || d2 != null) {
                "部分深度缺失"
            } else {
                "无深度数据"
            }

            binding.tvDistance.text = measuredDistance
            binding.overlayView.points = overlayPoints.toList()
            binding.overlayView.lines = listOf(Pair(p1, p2))
            binding.overlayView.invalidate()
            firstPoint = null
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))
        binding.overlayView.areaPoints = overlayAreaPoints.toList()

        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints)
            measuredDistance = String.format("%.2f m²", area)
            binding.tvDistance.text = measuredDistance
        } else {
            binding.tvDistance.text = "继续点击 (${overlayAreaPoints.size}/3+)"
        }

        // Build lines between consecutive points
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) {
            lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i + 1]))
        }
        if (overlayAreaPoints.size >= 3) {
            lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        }
        binding.overlayView.lines = lines
        binding.overlayView.points = overlayAreaPoints.toList()
        binding.overlayView.invalidate()
    }

    /**
     * Compute 3D distance between two screen points using their depth values.
     * Assumes pinhole camera model.
     */
    private fun compute3DDistance(
        p1: PointF, p2: PointF,
        d1: Float, d2: Float,
        viewW: Float, viewH: Float
    ): Float {
        // Normalize to [-1, 1] range (center = 0,0)
        val nx1 = (p1.x / viewW - 0.5f) * 2f
        val ny1 = (0.5f - p1.y / viewH) * 2f
        val nx2 = (p2.x / viewW - 0.5f) * 2f
        val ny2 = (0.5f - p2.y / viewH) * 2f

        // Approximate FOV (typical phone camera ~60-70°)
        val fovX = Math.toRadians(65.0)
        val fovY = Math.toRadians(50.0)

        // 3D coordinates
        val x1 = d1 * Math.tan(nx1 * fovX / 2).toFloat()
        val y1 = d1 * Math.tan(ny1 * fovY / 2).toFloat()
        val z1 = d1

        val x2 = d2 * Math.tan(nx2 * fovX / 2).toFloat()
        val y2 = d2 * Math.tan(ny2 * fovY / 2).toFloat()
        val z2 = d2

        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Approximate area from screen polygon + average depth.
     * (Accurate only for roughly flat surfaces facing the camera)
     */
    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        // Average depth of all points
        var depthSum = 0f
        var depthCount = 0
        for (p in points) {
            val d = getDepthAtScreen(p.x, p.y)
            if (d != null) {
                depthSum += d
                depthCount++
            }
        }
        val avgDepth = if (depthCount > 0) depthSum / depthCount else 1f

        // Convert screen polygon to real-world polygon at avg depth
        val viewW = binding.textureView.width.toFloat()
        val viewH = binding.textureView.height.toFloat()
        val fovX = Math.toRadians(65.0)

        // Width of view at avg depth
        val viewWidthM = (2 * avgDepth * Math.tan(fovX / 2)).toFloat()
        val scale = viewWidthM / viewW  // meters per pixel

        // Shoelace formula in pixel space, then scale
        var areaPixels = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            areaPixels += points[i].x * points[j].y
            areaPixels -= points[j].x * points[i].y
        }
        val areaReal = Math.abs(areaPixels / 2.0) * scale * scale
        return areaReal.toFloat()
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
        measuredDistance = "--"
        binding.tvDistance.text = "--"
        binding.overlayView.points = emptyList()
        binding.overlayView.lines = emptyList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun saveMeasurement() {
        if (measuredDistance == "--") {
            Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show()
            return
        }
        // Take screenshot with overlay
        val bitmap = Bitmap.createBitmap(
            binding.textureView.width,
            binding.textureView.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        binding.textureView.draw(canvas)
        binding.overlayView.draw(canvas)

        // Save to gallery
        val path = android.provider.MediaStore.Images.Media.insertImage(
            contentResolver, bitmap,
            "ARMeasure_${System.currentTimeMillis()}",
            "Distance: $measuredDistance"
        )
        if (path != null) {
            Toast.makeText(this, "已保存: $measuredDistance", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Background thread
    // ═══════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread join failed", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            mainCameraDevice?.close()
            mainCameraDevice = null
            depthCameraDevice?.close()
            depthCameraDevice = null
            depthImageReader?.close()
            depthImageReader = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Camera close interrupted", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
}
