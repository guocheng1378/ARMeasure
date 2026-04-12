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

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraCtrl: CameraController
    private lateinit var tofHelper: TofSensorHelper
    private lateinit var imuHelper: ImuFusionHelper

    @Volatile private var depthBuffer: ShortArray? = null
    @Volatile private var depthWidth: Int = 0
    @Volatile private var depthHeight: Int = 0
    private val depthFilter = DistanceFilter(windowSize = 5, alpha = 0.4f, maxJumpMm = 800f, maxRangeMm = 5000f)

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    @Volatile private var currentFocusDistance: Float = -1f

    private enum class Mode { POINT, LINE, AREA, SWEEP }
    private var currentMode = Mode.POINT
    @Volatile private var cameraOpening = false

    private val overlayPoints = mutableListOf<PointF>()
    private val overlayAreaPoints = mutableListOf<PointF>()
    private var firstPoint: PointF? = null
    private var firstDistance: Float = 0f
    private var measuredResult = "--"
    private val sweepHistory = mutableListOf<Pair<Float, Float>>()
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
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        cameraCtrl = CameraController(this, cm, backgroundHandler, binding.surfaceView)
        tofHelper = TofSensorHelper(sm)
        imuHelper = ImuFusionHelper(sm)
        binding.overlayView.onTap = { x, y -> onScreenTapped(x, y) }
        binding.overlayView.onSweepMove = { x, y -> onSweepMoved(x, y) }
        binding.surfaceView.holder.addCallback(this)
        tofHelper.detect()
        setupUI()
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
        if (cameraCtrl.cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    override fun onPause() {
        tofHelper.unregisterListener(this); imuHelper.stop()
        cameraCtrl.close(); stopBackgroundThread(); super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) { tofHelper.onSensorEvent(event) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Camera ──────────────────────────────────────────────

    private fun startCamera() {
        if (cameraOpening || cameraCtrl.cameraDevice != null) return
        cameraOpening = true
        val selection = cameraCtrl.selectCameras() ?: run { binding.tvSensor.text = "❌ 无可用摄像头"; cameraOpening = false; return }
        cameraCtrl.onDepthImageAvailable = { reader -> processDepthImage(reader) }
        cameraCtrl.captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (fd != null && fd > 0) currentFocusDistance = 1f / fd
            }
        }
        cameraCtrl.openCamera(selection, onReady = { same ->
            cameraOpening = false
            val ds = when { cameraCtrl.hasDepthMap -> "✅ DEPTH16" + if (imuHelper.isAvailable()) "+IMU" else ""; tofHelper.hasRealTof -> "📐 ToF"; else -> "📷 AF" }
            runOnUiThread { binding.tvSensor.text = ds }
            if (binding.surfaceView.width > 0 && binding.surfaceView.holder.surface.isValid) cameraCtrl.setupPreviewSession(cameraCtrl.cameraDevice!!, same)
        }, onError = { cameraOpening = false })
    }

    // ── Distance with IMU compensation ──────────────────────

    private fun getDistanceAt(sx: Float, sy: Float): Float? {
        var raw: Float? = null
        if (cameraCtrl.hasDepthMap) raw = getDepthAtScreenPoint(sx, sy)
        if (raw == null || raw <= 0) raw = tofHelper.getDistanceCm()
        if (raw == null || raw <= 0) { val d = currentFocusDistance; if (d > 0) raw = d * 100f }
        if (raw != null && raw > 0 && imuHelper.isAvailable()) {
            val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
            return if (vw > 0 && vh > 0) raw * imuHelper.getCorrectionFactor(sx, sy, vw, vh) else imuHelper.compensateDepth(raw)
        }
        return raw
    }

    // ── FOV ─────────────────────────────────────────────────

    private fun getHfovDegrees(): Double {
        val s = cameraCtrl.sensorSize ?: return 65.0; val f = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 65.0
        return 2 * Math.toDegrees(Math.atan(s.width / 2.0 / f))
    }
    private fun getVfovDegrees(): Double {
        val s = cameraCtrl.sensorSize ?: return 50.0; val f = cameraCtrl.focalLengthMm.takeIf { it > 0 } ?: return 50.0
        return 2 * Math.toDegrees(Math.atan(s.height / 2.0 / f))
    }

    // ── Touch ───────────────────────────────────────────────

    private fun onScreenTapped(x: Float, y: Float) {
        triggerAutoFocus(x, y); haptic()
        backgroundHandler?.postDelayed({ runOnUiThread { measureAtPoint(x, y) } }, 300)
    }

    private fun onSweepMoved(x: Float, y: Float) {
        backgroundHandler?.post {
            val dist = getDistanceAt(x, y)
            runOnUiThread {
                if (dist != null && dist > 0) {
                    binding.overlayView.sweepDistanceCm = dist
                    sweepHistory.add(Pair(x, dist)); if (sweepHistory.size > maxSweepHistory) sweepHistory.removeAt(0)
                    binding.overlayView.sweepHistory = sweepHistory.toList()
                    binding.tvDistance.text = String.format("%.1f cm", dist)
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
            req.addTarget(sv.holder.surface); req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region)); req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE); if (fd != null && fd > 0) currentFocusDistance = 1f / fd
                    if (!cameraCtrl.depthStreamActive) try {
                        val res = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); res.addTarget(sv.holder.surface)
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

    // ── Measurement ─────────────────────────────────────────

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
        val dist = getDistanceAt(x, y)
        measuredResult = when { dist != null -> String.format("%.0f cm", dist); tofHelper.tofDistanceMm > 0 -> String.format("~%.0f cm (近)", tofHelper.tofDistanceMm / 10f); else -> "对焦中..." }
        binding.tvDistance.text = measuredResult; updateOverlay()
    }

    private fun handleLineTap(x: Float, y: Float) {
        if (firstPoint == null) {
            firstPoint = PointF(x, y); firstDistance = getDistanceAt(x, y) ?: 0f
            overlayPoints.clear(); overlayPoints.add(PointF(x, y))
            binding.tvDistance.text = "标记第二点..."; updateOverlay()
        } else {
            val p1 = firstPoint!!; val p2 = PointF(x, y); overlayPoints.add(p2)
            val d1 = firstDistance; val d2 = getDistanceAt(x, y) ?: d1
            val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
            val dist = MeasurementEngine.compute3DDistance(p1.x, p1.y, p2.x, p2.y, d1, d2, vw, vh, getHfovDegrees(), getVfovDegrees())
            measuredResult = String.format("%.0f cm", dist); binding.tvDistance.text = measuredResult
            binding.overlayView.lines = listOf(Pair(p1, p2)); binding.overlayView.showLineLabels = true
            updateOverlay(); firstPoint = null
        }
    }

    private fun handleAreaTap(x: Float, y: Float) {
        overlayAreaPoints.add(PointF(x, y))
        if (overlayAreaPoints.size >= 3) { val area = computeArea(overlayAreaPoints); measuredResult = if (area > 0) String.format("%.0f cm²", area) else "无法计算"; binding.tvDistance.text = measuredResult }
        else binding.tvDistance.text = "继续点击 (${overlayAreaPoints.size}/3+)"
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (i in 0 until overlayAreaPoints.size - 1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i+1]))
        if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
        binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
        binding.overlayView.points = overlayAreaPoints.toList(); binding.overlayView.areaPoints = overlayAreaPoints.toList(); binding.overlayView.invalidate()
    }

    private fun updateOverlay() { binding.overlayView.points = overlayPoints.toList(); binding.overlayView.areaPoints = emptyList(); binding.overlayView.invalidate() }

    private fun computeArea(pts: List<PointF>): Float {
        if (pts.size < 3) return 0f
        val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
        if (cameraCtrl.hasDepthMap) {
            val depths = pts.map { getDistanceAt(it.x, it.y) }
            if (depths.all { it != null && it > 0 }) {
                val pts3d = pts.zip(depths).map { (p, d) ->
                    val nx = (p.x/vw - 0.5f)*2f; val ny = (0.5f - p.y/vh)*2f
                    Pair(d!! * Math.tan(nx * Math.toRadians(getHfovDegrees()) / 2).toFloat(), d * Math.tan(ny * Math.toRadians(getVfovDegrees()) / 2).toFloat())
                }
                return MeasurementEngine.computePolygonArea(pts3d)
            }
        }
        val avg = getDistanceAt(pts.map{it.x}.average().toFloat(), pts.map{it.y}.average().toFloat()) ?: return 0f
        return MeasurementEngine.computeFlatArea(pts.map{it.x}.toFloatArray(), pts.map{it.y}.toFloatArray(), avg, vw, getHfovDegrees())
    }

    // ── UI ──────────────────────────────────────────────────

    private fun setupUI() {
        binding.tvSensor.text = "${if (tofHelper.hasRealTof) "📐 " else "⚠️ "}${tofHelper.sensorLabel}" + if (imuHelper.isAvailable()) " +IMU" else ""
        binding.btnPointMode.setOnClickListener { setMode(Mode.POINT) }
        binding.btnLineMode.setOnClickListener { setMode(Mode.LINE) }
        binding.btnAreaMode.setOnClickListener { setMode(Mode.AREA) }
        binding.btnSweepMode.setOnClickListener { setMode(Mode.SWEEP) }
        binding.btnReset.setOnClickListener {
            if (currentMode == Mode.AREA && overlayAreaPoints.isNotEmpty()) {
                overlayAreaPoints.removeAt(overlayAreaPoints.size - 1)
                if (overlayAreaPoints.size >= 3) { val a = computeArea(overlayAreaPoints); measuredResult = if (a>0) String.format("%.0f cm²", a) else "无法计算"; binding.tvDistance.text = measuredResult }
                else { measuredResult = if (overlayAreaPoints.isEmpty()) "--" else "继续点击 (${overlayAreaPoints.size}/3+)"; binding.tvDistance.text = measuredResult }
                val lines = mutableListOf<Pair<PointF, PointF>>()
                for (i in 0 until overlayAreaPoints.size-1) lines.add(Pair(overlayAreaPoints[i], overlayAreaPoints[i+1]))
                if (overlayAreaPoints.size >= 3) lines.add(Pair(overlayAreaPoints.last(), overlayAreaPoints.first()))
                binding.overlayView.lines = lines; binding.overlayView.showLineLabels = false
                binding.overlayView.points = overlayAreaPoints.toList(); binding.overlayView.areaPoints = overlayAreaPoints.toList(); binding.overlayView.invalidate()
            } else resetMeasurement()
        }
        binding.btnSave.setOnClickListener { saveMeasurement() }
        setMode(Mode.POINT)
    }

    private fun setMode(mode: Mode) {
        currentMode = mode; resetMeasurement()
        binding.tvMode.text = when(mode) { Mode.POINT -> "📏 点击测距"; Mode.LINE -> "📐 两点测距"; Mode.AREA -> "⬜ 面积测量"; Mode.SWEEP -> "👆 扫掠测距" }
        binding.overlayView.sweepMode = (mode == Mode.SWEEP)
        listOf(binding.btnPointMode, binding.btnLineMode, binding.btnAreaMode, binding.btnSweepMode).forEach { it.setBackgroundColor(0x00000000) }
        when(mode) { Mode.POINT -> binding.btnPointMode; Mode.LINE -> binding.btnLineMode; Mode.AREA -> binding.btnAreaMode; Mode.SWEEP -> binding.btnSweepMode }.setBackgroundColor(0x3300FF88.toInt())
    }

    private fun resetMeasurement() {
        overlayPoints.clear(); overlayAreaPoints.clear(); sweepHistory.clear(); firstPoint = null; depthFilter.reset()
        measuredResult = "--"; binding.tvDistance.text = "--"
        binding.overlayView.points = emptyList(); binding.overlayView.lines = emptyList(); binding.overlayView.areaPoints = emptyList()
        binding.overlayView.showLineLabels = false; binding.overlayView.sweepDistanceCm = -1f; binding.overlayView.sweepHistory = emptyList(); binding.overlayView.invalidate()
    }

    private fun saveMeasurement() {
        if (measuredResult == "--" || measuredResult.contains("无")) { Toast.makeText(this, "请先进行测量", Toast.LENGTH_SHORT).show(); return }
        try {
            val sv = binding.surfaceView; val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); sv.getLocationInWindow(loc)
                android.view.PixelCopy.request(window, Rect(loc[0], loc[1], loc[0]+sv.width, loc[1]+sv.height), bitmap,
                    { r -> if (r == android.view.PixelCopy.SUCCESS) { val c = Canvas(bitmap); binding.overlayView.draw(c); try { android.provider.MediaStore.Images.Media.insertImage(contentResolver, bitmap, "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult"); runOnUiThread { Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show() } } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() } } } }, Handler(mainLooper))
            } else {
                val c = Canvas(bitmap); c.drawColor(Color.BLACK); binding.overlayView.draw(c)
                android.provider.MediaStore.Images.Media.insertImage(contentResolver, bitmap, "ARMeasure_${System.currentTimeMillis()}", "Distance: $measuredResult")
                Toast.makeText(this, "已保存: $measuredResult", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (cameraCtrl.cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (cameraCtrl.cameraDevice != null) cameraCtrl.setupPreviewSession(cameraCtrl.cameraDevice!!, cameraCtrl.depthStreamActive)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // ── DEPTH16 ─────────────────────────────────────────────

    private fun processDepthImage(reader: android.media.ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]; val buffer = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride; val w = image.width; val h = image.height
            val buf = ShortArray(w * h)
            for (row in 0 until h) { val rowStart = row * rs; for (col in 0 until w) { val bi = rowStart + col * ps; if (bi + 1 < buffer.capacity()) buf[row * w + col] = buffer.getShort(bi) } }
            depthBuffer = buf; depthWidth = w; depthHeight = h
        } catch (e: Exception) { Log.e(TAG, "depth err", e) } finally { image.close() }
    }

    private fun getDepthAtScreenPoint(sx: Float, sy: Float): Float? {
        val buf = depthBuffer ?: return null
        if (buf.isEmpty() || depthWidth <= 0 || depthHeight <= 0) return null
        val vw = binding.surfaceView.width.toFloat(); val vh = binding.surfaceView.height.toFloat()
        if (vw <= 0 || vh <= 0) return null
        val dx: Int; val dy: Int
        val sep = cameraCtrl.depthCameraDevice?.id != cameraCtrl.cameraDevice?.id
        if (sep && cameraCtrl.depthSensorActiveArray != null && cameraCtrl.rgbSensorActiveArray != null) {
            val rgb = cameraCtrl.rgbSensorActiveArray!!; val dep = cameraCtrl.depthSensorActiveArray!!
            dx = (sx / vw * rgb.width() / rgb.width() * dep.width()).toInt().coerceIn(0, depthWidth - 1)
            dy = (sy / vh * rgb.height() / rgb.height() * dep.height()).toInt().coerceIn(0, depthHeight - 1)
        } else { dx = (sx / vw * depthWidth).toInt().coerceIn(0, depthWidth - 1); dy = (sy / vh * depthHeight).toInt().coerceIn(0, depthHeight - 1) }

        // 7x7 weighted kernel
        var wSum = 0.0; var wtSum = 0.0; var cnt = 0
        for (ddy in -3..3) for (ddx in -3..3) {
            val px = (dx+ddx).coerceIn(0, depthWidth-1); val py = (dy+ddy).coerceIn(0, depthHeight-1)
            val raw = buf[py*depthWidth+px].toInt() and 0xFFFF
            if (raw in 1..65533) { val d2 = (ddx*ddx+ddy*ddy).toFloat(); val w = 1f/(1f+d2); wSum += raw*w; wtSum += w; cnt++ }
        }
        if (cnt < 5) return null
        val filtered = depthFilter.filter((wSum / wtSum).toFloat())
        return if (filtered > 0) filtered / 10f else null
    }

    // ── Background thread ───────────────────────────────────

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) { backgroundThread?.quitSafely(); try { backgroundThread?.join(1000) } catch (_: Exception) {} }
        backgroundThread = HandlerThread("CameraBG").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper)
    }
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely(); try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (_: Exception) {}
    }
}
