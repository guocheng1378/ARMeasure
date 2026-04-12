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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.armeasure.app.databinding.ActivityMainBinding

/**
 * AR Measure — orchestrates camera, depth, ToF, and measurement UI.
 *
 * Depth source priority:
 *   1. Camera2 DEPTH16 — per-pixel depth map
 *   2. ToF sensor — single-point fallback
 *   3. LENS_FOCUS_DISTANCE — last resort
 */
class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding

    // ── Components ─────────────────────────────────────────
    private lateinit var cameraCtrl: CameraController
    private lateinit var tofHelper: TofSensorHelper

    // ── Depth map state ────────────────────────────────────
    @Volatile
    private var depthBuffer: ShortArray? = null
    @Volatile
    private var depthWidth: Int = 0
    @Volatile
    private var depthHeight: Int = 0
    private var lastDepthEma = -1f

    // ── Background thread ──────────────────────────────────
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // ── AF distance fallback ───────────────────────────────
    @Volatile
    private var currentFocusDistance: Float = -1f

    // ── Measurement state ──────────────────────────────────
    private enum class Mode { POINT, LINE, AREA }
    private var currentMode = Mode.POINT
    @Volatile
    private var cameraOpening = false

    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var firstDistance: Float = 0f
    private var measuredResult = "--"

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
                    .run {
                        if (length() > 512 * 1024) delete() // cap at 512KB
                        appendText("${java.util.Date()} ${e.stackTraceToString()}\n\n")
                    }
            } catch (_: Exception) {}
            throw e
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraCtrl = CameraController(this, cm, backgroundHandler, binding.surfaceView)
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }
        binding.surfaceView.holder.addCallback(this)

        tofHelper = TofSensorHelper(getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
        tofHelper.detect()
        setupUI()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
        // startCamera() moved to onResume() so backgroundHandler is ready
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CAMERA && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            // onResume will call startCamera
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        cameraCtrl.backgroundHandler = backgroundHandler
        tofHelper.registerListener(this)
        if (cameraCtrl.cameraDevice == null
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onPause() {
        tofHelper.unregisterListener(this)
        cameraCtrl.close()
        stopBackgroundThread()
        super.onPause()
    }

    // ═══════════════════════════════════════════════════════════
    // ToF SensorEventListener
    // ═══════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent) {
        tofHelper.onSensorEvent(event)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ═══════════════════════════════════════════════════════════
    // Camera
    // ═══════════════════════════════════════════════════════════

    private fun startCamera() {
        if (cameraOpening || cameraCtrl.cameraDevice != null) return
        cameraOpening = true
        val selection = cameraCtrl.selectCameras() ?: run {
            binding.tvSensor.text = "❌ 无可用摄像头"
            cameraOpening = false
            return
        }

        cameraCtrl.onDepthImageAvailable = { reader ->
            processDepthImage(reader)
        }
        cameraCtrl.captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (focusDiopters != null && focusDiopters > 0) {
                    currentFocusDistance = 1f / focusDiopters
                }
            }
        }

        cameraCtrl.openCamera(selection, onReady = { sameCameraHasDepth ->
            cameraOpening = false
            val depthStatus = when {
                cameraCtrl.hasDepthMap -> "✅ DEPTH16"
                tofHelper.hasRealTof -> "📐 ToF"
                else -> "📷 AF"
            }
            runOnUiThread { binding.tvSensor.text = depthStatus }

            if (binding.surfaceView.width > 0 && binding.surfaceView.holder.surface.isValid) {
                cameraCtrl.setupPreviewSession(
                    cameraCtrl.cameraDevice!!, sameCameraHasDepth
                )
            }
        }, onError = {
            cameraOpening = false
        })
    }

    // ═══════════════════════════════════════════════════════════
    // Distance estimation — unified entry point
    // ═══════════════════════════════════════════════════════════

    private fun getDistanceAt(screenX: Float, screenY: Float): Float? {
        // 1. DEPTH16
        if (cameraCtrl.hasDepthMap) {
            val depth = getDepthAtScreenPoint(screenX, screenY)
            if (depth != null && depth > 0) return depth
        }

        // 2. ToF
        tofHelper.getDistanceCm()?.let { return it }

        // 3. AF fallback
        val dist = currentFocusDistance
        return if (dist > 0) dist * 100f else null
    }

    // ═══════════════════════════════════════════════════════════
    // FOV helpers
    // ═══════════════════════════════════════════════════════════

    private fun getHfovDegrees(): Double {
        val sensor = cameraCtrl.sensorSize ?: return 65.0
        val focal = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 65.0
        return 2 * Math.toDegrees(Math.atan(sensor.width / 2.0 / focal))
    }

    private fun getVfovDegrees(): Double {
        val sensor = cameraCtrl.sensorSize ?: return 50.0
        val focal = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 50.0
        return 2 * Math.toDegrees(Math.atan(sensor.height / 2.0 / focal))
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
        val session = cameraCtrl.captureSession ?: return
        val device = cameraCtrl.cameraDevice ?: return

        val sensorRect = cameraCtrl.rgbSensorActiveArray ?: return
        val sv = binding.surfaceView

        val scaleX = sensorRect.width().toFloat() / sv.width
        val scaleY = sensorRect.height().toFloat() / sv.height
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
            request.addTarget(sv.holder.surface)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
                ) {
                    val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (focusDiopters != null && focusDiopters > 0) {
                        currentFocusDistance = 1f / focusDiopters
                    }
                    if (!cameraCtrl.depthStreamActive) {
                        try {
                            val resume = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            resume.addTarget(sv.holder.surface)
                            resume.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            session.setRepeatingRequest(
                                resume.build(), cameraCtrl.captureCallback, backgroundHandler
                            )
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
        measuredResult = when {
            dist != null -> String.format("%.0f cm", dist)
            tofHelper.tofDistanceMm > 0 -> String.format("~%.0f cm (近)", tofHelper.tofDistanceMm / 10f)
            else -> "对焦中..."
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

            val dist = MeasurementEngine.compute3DDistance(
                p1.x, p1.y, p2.x, p2.y, d1, d2, viewW, viewH, getHfovDegrees(), getVfovDegrees()
            )

            measuredResult = String.format("%.0f cm", dist)
            binding.tvDistance.text = measuredResult
            binding.overlayView.lines = listOf(Pair(p1, p2))
            binding.overlayView.showLineLabels = true
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
        binding.overlayView.showLineLabels = false
        binding.overlayView.points = overlayAreaPoints.toList()
        binding.overlayView.areaPoints = overlayAreaPoints.toList()
        binding.overlayView.invalidate()
    }

    private fun updateOverlay() {
        binding.overlayView.points = overlayPoints.toList()
        binding.overlayView.areaPoints = emptyList()
        binding.overlayView.invalidate()
    }

    private fun computeArea(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()

        // 3D area if depth map available
        if (cameraCtrl.hasDepthMap) {
            val depths = points.map { getDistanceAt(it.x, it.y) }
            if (depths.all { it != null && it > 0 }) {
                val pts3d = points.zip(depths).map { (p, d) ->
                    val nx = (p.x / viewW - 0.5f) * 2f
                    val ny = (0.5f - p.y / viewH) * 2f
                    val cx = d!! * Math.tan(nx * Math.toRadians(getHfovDegrees()) / 2).toFloat()
                    val cy = d * Math.tan(ny * Math.toRadians(getVfovDegrees()) / 2).toFloat()
                    Pair(cx, cy)
                }
                return MeasurementEngine.computePolygonArea(pts3d)
            }
        }

        // Flat-plane fallback
        val avgDist = getDistanceAt(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        ) ?: return 0f
        return MeasurementEngine.computeFlatArea(
            points.map { it.x }.toFloatArray(),
            points.map { it.y }.toFloatArray(),
            avgDist, viewW, getHfovDegrees()
        )
    }

    // ═══════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.tvSensor.text = "${if (tofHelper.hasRealTof) "📐 " else "⚠️ "}${tofHelper.sensorLabel}"

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
        binding.btnReset.setOnClickListener {
            if (currentMode == Mode.AREA && overlayAreaPoints.isNotEmpty()) {
                // Undo last area point
                overlayAreaPoints.removeAt(overlayAreaPoints.size - 1)
                if (overlayAreaPoints.size >= 3) {
                    val area = computeArea(overlayAreaPoints)
                    measuredResult = if (area > 0) String.format("%.0f cm²", area) else "无法计算"
                    binding.tvDistance.text = measuredResult
                } else {
                    measuredResult = if (overlayAreaPoints.isEmpty()) "--" else "继续点击 (${overlayAreaPoints.size}/3+)"
                    binding.tvDistance.text = measuredResult
                }
                val lines = mutableListOf<Pair<PointF, PointF>>()
                for (i in 0 until overlayAreaPoints.size - 1) {
                    lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i + 1]))
                }
                if (overlayAreaPoints.size >= 3) {
                    lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
                }
                binding.overlayView.lines = lines
                binding.overlayView.showLineLabels = false
                binding.overlayView.points = overlayAreaPoints.toList()
                binding.overlayView.areaPoints = overlayAreaPoints.toList()
                binding.overlayView.invalidate()
            } else {
                resetMeasurement()
            }
        }
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
        binding.overlayView.showLineLabels = false
        binding.overlayView.invalidate()
    }

    private fun saveMeasurement() {
        if (measuredResult == "--" || measuredResult.contains("无")) {
            Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sv = binding.surfaceView
            val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val loc = IntArray(2)
                sv.getLocationInWindow(loc)
                android.view.PixelCopy.request(
                    window,
                    android.graphics.Rect(loc[0], loc[1], loc[0] + sv.width, loc[1] + sv.height),
                    bitmap,
                    { copyResult ->
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            val canvas = Canvas(bitmap)
                            binding.overlayView.draw(canvas)
                            try {
                                android.provider.MediaStore.Images.Media.insertImage(
                                    contentResolver, bitmap,
                                    "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult"
                                )
                                runOnUiThread {
                                    Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    Handler(mainLooper)
                )
            } else {
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
    // SurfaceHolder.Callback
    // ═══════════════════════════════════════════════════════════

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (cameraCtrl.cameraDevice == null
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (cameraCtrl.cameraDevice != null) {
            cameraCtrl.setupPreviewSession(
                cameraCtrl.cameraDevice!!, cameraCtrl.depthStreamActive
            )
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // ═══════════════════════════════════════════════════════════
    // DEPTH16 processing
    // ═══════════════════════════════════════════════════════════

    private fun processDepthImage(reader: android.media.ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStrideBytes = plane.rowStride
            val pixelStrideBytes = plane.pixelStride
            val w = image.width
            val h = image.height

            val buf = ShortArray(w * h)
            for (row in 0 until h) {
                val rowStart = row * rowStrideBytes
                for (col in 0 until w) {
                    val byteIdx = rowStart + col * pixelStrideBytes
                    if (byteIdx + 1 < buffer.capacity()) {
                        buf[row * w + col] = buffer.getShort(byteIdx)
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

    private fun getDepthAtScreenPoint(screenX: Float, screenY: Float): Float? {
        val buf = depthBuffer ?: return null
        if (buf.isEmpty() || depthWidth <= 0 || depthHeight <= 0) return null

        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return null

        val depthX: Int
        val depthY: Int

        val separateDepth = cameraCtrl.depthCameraDevice?.id != cameraCtrl.cameraDevice?.id
        if (separateDepth && cameraCtrl.depthSensorActiveArray != null && cameraCtrl.rgbSensorActiveArray != null) {
            val rgbArray = cameraCtrl.rgbSensorActiveArray!!
            val depthArray = cameraCtrl.depthSensorActiveArray!!
            val rgbSensorX = screenX / viewW * rgbArray.width()
            val rgbSensorY = screenY / viewH * rgbArray.height()
            depthX = (rgbSensorX / rgbArray.width() * depthArray.width()).toInt().coerceIn(0, depthWidth - 1)
            depthY = (rgbSensorY / rgbArray.height() * depthArray.height()).toInt().coerceIn(0, depthHeight - 1)
        } else {
            depthX = (screenX / viewW * depthWidth).toInt().coerceIn(0, depthWidth - 1)
            depthY = (screenY / viewH * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        }

        // 5×5 kernel median
        val samples = mutableListOf<Float>()
        for (dy in -2..2) {
            for (dx in -2..2) {
                val px = (depthX + dx).coerceIn(0, depthWidth - 1)
                val py = (depthY + dy).coerceIn(0, depthHeight - 1)
                val raw = buf[py * depthWidth + px].toInt() and 0xFFFF
                if (raw in 1..65533) samples.add(raw.toFloat())
            }
        }
        if (samples.size < 3) return null

        samples.sort()
        val medianMm = samples[samples.size / 2]

        val smoothed = if (lastDepthEma < 0) {
            lastDepthEma = medianMm; medianMm
        } else {
            lastDepthEma = 0.4f * medianMm + 0.6f * lastDepthEma; lastDepthEma
        }
        return smoothed / 10f
    }

    // ═══════════════════════════════════════════════════════════
    // Background thread
    // ═══════════════════════════════════════════════════════════

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
        try {
            backgroundThread?.join(); backgroundThread = null; backgroundHandler = null
        } catch (_: Exception) {}
    }
}
