package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.graphics.SurfaceTexture
import android.view.Surface
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
    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Depth
    private var depthImageReader: ImageReader? = null
    @Volatile private var depthMap: FloatArray? = null
    private var depthWidth = 0
    private var depthHeight = 0
    private var hasDepth = false

    // Measurement state
    private enum class Mode { POINT, LINE, AREA }
    private var currentMode = Mode.POINT

    // Overlay data
    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var measuredDistance = "--"

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

            // Log all cameras and their capabilities
            for (id in ids) {
                val chars = cameraManager!!.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isDepth = caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                ) == true

                Log.d(TAG, "Camera $id: facing=$facing caps=${caps?.joinToString()} depthOutput=$isDepth")

                // Pick back camera as main
                if (facing == CameraCharacteristics.LENS_FACING_BACK && mainCameraId == null) {
                    mainCameraId = id
                }

                // Also try depth-specific camera
                if (isDepth && id != mainCameraId) {
                    mainCameraId = id // Prefer depth camera if available
                    Log.d(TAG, "Found depth camera: $id")
                }
            }

            val id = mainCameraId ?: run {
                binding.tvSensor.text = "❌ 无可用摄像头"
                return
            }

            Log.d(TAG, "Using camera: $id")
            openCamera(id)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access failed", e)
            binding.tvSensor.text = "❌ 相机访问失败"
        }
    }

    private fun openCamera(cameraId: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)

        cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera

                val chars = cameraManager!!.getCameraCharacteristics(cameraId)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                hasDepth = caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                ) == true

                if (hasDepth) {
                    binding.tvSensor.text = "📐 dToF 深度传感器"
                    setupDepthCapture(camera, chars)
                } else {
                    binding.tvSensor.text = "📷 主摄 (尝试深度流)"
                    // Try to add DEPTH16 anyway (some devices support it without advertising)
                    tryDepthFallback(camera, chars)
                }

                createPreview(camera)
                Log.d(TAG, "Camera opened, depth=$hasDepth")
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
     * Setup depth capture on a camera that advertises DEPTH_OUTPUT.
     */
    private fun setupDepthCapture(camera: CameraDevice, chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // Check available output sizes for DEPTH16
        val depthSizes = map.getOutputSizes(ImageFormat.DEPTH16)
        if (depthSizes.isNullOrEmpty()) {
            Log.w(TAG, "No DEPTH16 sizes available")
            hasDepth = false
            binding.tvSensor.text = "📷 主摄 (无深度格式)"
            return
        }

        val depthSize = depthSizes.maxByOrNull { it.width * it.height } ?: Size(240, 180)
        Log.d(TAG, "Depth size: $depthSize")

        depthImageReader = ImageReader.newInstance(
            depthSize.width, depthSize.height, ImageFormat.DEPTH16, 2
        )
        depthImageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processDepthImage(image)
            image.close()
        }, backgroundHandler)
    }

    /**
     * Fallback: try to use DEPTH16 on main camera even if not advertised.
     */
    private fun tryDepthFallback(camera: CameraDevice, chars: CameraCharacteristics) {
        try {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            // Check if DEPTH16 is available as output
            val depthSizes = map.getOutputSizes(ImageFormat.DEPTH16)
            if (depthSizes.isNullOrEmpty()) {
                Log.d(TAG, "DEPTH16 not available on this camera")
                binding.tvSensor.text = "📷 主摄 (无深度输出)"
                return
            }

            val depthSize = depthSizes.maxByOrNull { it.width * it.height } ?: Size(240, 180)
            Log.d(TAG, "Fallback depth size: $depthSize")

            depthImageReader = ImageReader.newInstance(
                depthSize.width, depthSize.height, ImageFormat.DEPTH16, 2
            )
            depthImageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processDepthImage(image)
                image.close()
            }, backgroundHandler)

            hasDepth = true
            binding.tvSensor.text = "📐 dToF (兼容模式)"
            Log.d(TAG, "Fallback depth capture enabled")

        } catch (e: Exception) {
            Log.w(TAG, "Depth fallback failed", e)
            binding.tvSensor.text = "📷 主摄 (深度不可用)"
        }
    }

    private fun createPreview(camera: CameraDevice) {
        val textureView = binding.textureView
        val texture = textureView.surfaceTexture ?: return

        // Get optimal preview size
        val chars = cameraManager!!.getCameraCharacteristics(camera.id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = chooseOptimalSize(
            map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(),
            textureView.width, textureView.height
        )

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val surfaces = mutableListOf<Surface>(previewSurface)
        depthImageReader?.let { surfaces.add(it.surface) }

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(previewSurface)
        depthImageReader?.let { captureRequest.addTarget(it.surface) }

        captureRequest.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                        Log.d(TAG, "Preview started with ${surfaces.size} surfaces")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Preview failed", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Preview config failed")
                    // Retry without depth
                    retryPreviewWithoutDepth(camera, previewSurface)
                }
            },
            backgroundHandler
        )
    }

    /**
     * If preview with depth fails, retry without depth stream.
     */
    private fun retryPreviewWithoutDepth(camera: CameraDevice, previewSurface: Surface) {
        Log.w(TAG, "Retrying preview without depth")
        hasDepth = false
        binding.tvSensor.text = "📷 主摄 (深度不可用)"

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(previewSurface)
        captureRequest.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        camera.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Preview retry failed", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    private fun chooseOptimalSize(choices: Array<android.util.Size>, width: Int, height: Int): android.util.Size {
        val targetRatio = if (width > height) width.toFloat() / height else height.toFloat() / width
        return choices.minByOrNull {
            val ratio = it.width.toFloat() / it.height
            Math.abs(ratio - targetRatio)
        } ?: choices.firstOrNull() ?: android.util.Size(1920, 1080)
    }

    // ═══════════════════════════════════════════════════════════
    // Depth processing (DEPTH16 format)
    // ═══════════════════════════════════════════════════════════

    private fun processDepthImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        depthWidth = image.width
        depthHeight = image.height
        val data = FloatArray(depthWidth * depthHeight)

        buffer.rewind()
        for (y in 0 until depthHeight) {
            for (x in 0 until depthWidth) {
                val byteOffset = y * rowStride + x * pixelStride
                if (byteOffset + 1 < buffer.capacity()) {
                    buffer.position(byteOffset)
                    val raw = buffer.short.toInt() and 0xFFFF
                    // DEPTH16: 0 = invalid, 1-65533 = depth in mm
                    // 65534 = too far, 65535 = too close
                    data[y * depthWidth + x] = when {
                        raw == 0 || raw >= 65534 -> -1f
                        else -> raw / 1000f
                    }
                }
            }
        }

        depthMap = data
    }

    /**
     * Get depth at screen coordinate.
     */
    private fun getDepthAtScreen(screenX: Float, screenY: Float): Float? {
        if (!hasDepth) return null
        val data = depthMap ?: return null
        if (depthWidth == 0 || depthHeight == 0) return null

        val viewW = binding.textureView.width.toFloat()
        val viewH = binding.textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return null

        // Map screen coords to depth image coords
        val dx = (screenX / viewW * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val dy = (screenY / viewH * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        // 3x3 kernel average for stability
        var sum = 0f
        var count = 0
        for (oy in -2..2) {
            for (ox in -2..2) {
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
        } else if (!hasDepth) {
            "无深度传感器"
        } else {
            "该区域无深度数据\n(可能距离过远或过近)"
        }
        binding.tvDistance.text = measuredDistance
        updateOverlay()
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            firstPoint = PointF(x, y)
            overlayPoints.clear()
            overlayPoints.add(PointF(x, y))
            binding.tvDistance.text = "标记第二点..."
            updateOverlay()
        } else {
            val p1 = firstPoint!!
            val p2 = PointF(x, y)
            overlayPoints.add(p2)

            val d1 = getDepthAtScreen(p1.x, p1.y)
            val d2 = getDepthAtScreen(p2.x, p2.y)

            measuredDistance = if (d1 != null && d2 != null) {
                val viewW = binding.textureView.width.toFloat()
                val viewH = binding.textureView.height.toFloat()
                val dist = compute3DDistance(p1, p2, d1, d2, viewW, viewH)
                String.format("%.2f m", dist)
            } else if (!hasDepth) {
                "无深度传感器"
            } else {
                "部分区域无深度数据"
            }

            binding.tvDistance.text = measuredDistance
            binding.overlayView.lines = listOf(Pair(p1, p2))
            updateOverlay()
            firstPoint = null
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))

        if (overlayAreaPoints.size >= 3) {
            val area = computeArea(overlayAreaPoints)
            measuredDistance = if (area > 0) String.format("%.2f m²", area) else "无法计算面积"
            binding.tvDistance.text = measuredDistance
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

        val fovX = Math.toRadians(65.0)
        val fovY = Math.toRadians(50.0)

        val x1 = d1 * Math.tan(nx1 * fovX / 2).toFloat()
        val y1 = d1 * Math.tan(ny1 * fovY / 2).toFloat()
        val x2 = d2 * Math.tan(nx2 * fovX / 2).toFloat()
        val y2 = d2 * Math.tan(ny2 * fovY / 2).toFloat()

        val dx = x1 - x2
        val dy = y1 - y2
        val dz = d1 - d2

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        var depthSum = 0f
        var depthCount = 0
        for (p in points) {
            val d = getDepthAtScreen(p.x, p.y)
            if (d != null) { depthSum += d; depthCount++ }
        }
        val avgDepth = if (depthCount > 0) depthSum / depthCount else return 0f

        val viewW = binding.textureView.width.toFloat()
        val fovX = Math.toRadians(65.0)
        val viewWidthM = (2 * avgDepth * Math.tan(fovX / 2)).toFloat()
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
        if (measuredDistance == "--" || measuredDistance.contains("无")) {
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
                "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredDistance"
            )
            Toast.makeText(this, "已保存: $measuredDistance", Toast.LENGTH_SHORT).show()
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
            cameraDevice?.close(); cameraDevice = null
            depthImageReader?.close(); depthImageReader = null
        } catch (_: InterruptedException) {} finally { cameraOpenCloseLock.release() }
    }
}
