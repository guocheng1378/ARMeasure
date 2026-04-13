package com.armeasure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.app.ActivityCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraController(
    private val context: Context,
    private val cameraManager: CameraManager,
    var backgroundHandler: Handler?,
    private val surfaceView: SurfaceView
) {
    companion object {
        private const val TAG = "CameraCtrl"
    }

    var cameraDevice: CameraDevice? = null
        private set
    var captureSession: CameraCaptureSession? = null
        private set
    var depthCameraDevice: CameraDevice? = null
        private set
    var depthReader: ImageReader? = null
        private set

    var rgbSensorActiveArray: Rect? = null
        private set
    var depthSensorActiveArray: Rect? = null
        private set
    var sensorSize: SizeF? = null
        private set
    var focalLengthMm: Float = 0f
        private set
    /** Camera intrinsic calibration: [fx, fy, cx, cy, skew] or null if unavailable */
    var intrinsicCalibration: FloatArray? = null
        private set
    var hasDepthMap: Boolean = false
        private set
    var depthStreamActive: Boolean = false
        private set

    var depthWidth: Int = 0
        private set
    var depthHeight: Int = 0
        private set

    var depthCameraEnabled: Boolean = false
    var hasSeparateDepthCamera: Boolean = false
        private set
    private var lastSelection: CameraSelection? = null

    var torchOn: Boolean = false
        private set

    fun toggleTorch() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        torchOn = !torchOn
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            // Preserve all existing surfaces (preview + depth if active)
            req.addTarget(surfaceView.holder.surface)
            if (depthStreamActive && depthReader != null) {
                req.addTarget(depthReader!!.surface)
            }
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (torchOn) {
                req.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            session.setRepeatingRequest(req.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            torchOn = !torchOn
            Log.e(TAG, "Torch toggle failed", e)
        }
    }

    private val cameraOpenCloseLock = Semaphore(1)

    var onDepthImageAvailable: ((ImageReader) -> Unit)? = null
    var captureCallback: CameraCaptureSession.CaptureCallback? = null

    data class CameraSelection(
        val rgbId: String,
        val depthId: String?,
        val sameCameraHasDepth: Boolean
    )

    fun selectCameras(): CameraSelection? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val ids = cameraManager.cameraIdList
        var bestRgbId: String? = null
        var bestFov = 0f
        var depthCamId: String? = null

        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()

            if (capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                )
            ) {
                depthCamId = id
                Log.d(TAG, "Depth camera found: $id")
            }

            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal = focalLengths?.firstOrNull() ?: continue
                val sensor = sensorPhysicalSize ?: continue

                val hfov = 2 * Math.toDegrees(Math.atan(sensor.width / 2.0 / focal))
                if (hfov > bestFov) {
                    bestFov = hfov.toFloat()
                    bestRgbId = id
                    focalLengthMm = focal
                    sensorSize = SizeF(sensor.width, sensor.height)
                }
            }
        }

        // Do NOT replace RGB preview camera with depth camera

        val rgbId = bestRgbId ?: ids.firstOrNull() ?: return null

        val rgbChars = cameraManager.getCameraCharacteristics(rgbId)
        rgbSensorActiveArray = rgbChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // Read intrinsic calibration: [fx, fy, cx, cy, skew]
        intrinsicCalibration = rgbChars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

        if (depthCamId != null) {
            depthSensorActiveArray = cameraManager.getCameraCharacteristics(depthCamId)
                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }

        hasDepthMap = depthCamId != null
        hasSeparateDepthCamera = depthCamId != null && depthCamId != rgbId
        lastSelection = CameraSelection(rgbId, depthCamId, depthCamId == rgbId)
        return lastSelection
    }

    fun openCamera(selection: CameraSelection, onReady: (Boolean) -> Unit, onError: (() -> Unit)? = null) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Camera open timeout — lock not acquired")
            onError?.invoke()
            return
        }

        try {
            cameraManager.openCamera(selection.rgbId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera

                    if (depthCameraEnabled && selection.depthId != null && selection.depthId != selection.rgbId) {
                        openDepthCamera(selection.depthId)
                    }

                    // Use surface validity as the gate, NOT surfaceView.width.
                    // width may still be 0 before the first layout pass, but the surface
                    // holder is already valid. Using width > 0 causes the preview session
                    // to be skipped when camera opens before layout completes.
                    if (surfaceView.holder.surface.isValid) {
                        val useSameCameraDepth = selection.sameCameraHasDepth && depthCameraEnabled
                        setupPreviewSession(camera, useSameCameraDepth)
                    }
                    onReady(selection.sameCameraHasDepth && depthCameraEnabled)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    onError?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    onError?.invoke()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            // Fix #5: release lock if openCamera itself throws (e.g. CameraAccessException)
            cameraOpenCloseLock.release()
            Log.e(TAG, "openCamera threw", e)
            onError?.invoke()
        }
    }

    private fun openDepthCamera(depthId: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            cameraManager.openCamera(depthId, object : CameraDevice.StateCallback() {
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

    fun reopenCamera(onReady: (Boolean) -> Unit, onError: (() -> Unit)? = null) {
        close()
        val selection = lastSelection ?: selectCameras() ?: return
        openCamera(selection, onReady, onError)
    }

    /**
     * Toggle depth camera without disturbing RGB preview.
     * Returns true if depth is now enabled.
     */
    fun toggleDepthCamera(onDone: (Boolean) -> Unit) {
        if (depthCameraEnabled) {
            // Turning OFF: just close depth, RGB stays
            closeDepthOnly()
            depthCameraEnabled = false
            onDone(false)
        } else {
            // Turning ON: open depth only, RGB stays
            depthCameraEnabled = true
            openDepthOnly { onDone(true) }
        }
    }

    fun setupPreviewSession(camera: CameraDevice, sameCameraHasDepth: Boolean) {
        try {
            val holder = surfaceView.holder
            depthStreamActive = sameCameraHasDepth
            val previewSurface = holder.surface

            val chars = cameraManager.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseOptimalSize(
                map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(),
                surfaceView.width.coerceAtLeast(1),
                surfaceView.height.coerceAtLeast(1)
            )
            holder.setFixedSize(previewSize.width, previewSize.height)

            val targets = mutableListOf<Surface>(previewSurface)
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(previewSurface)

            if (sameCameraHasDepth && map != null) {
                val depthSizes = map.getOutputSizes(ImageFormat.DEPTH16)
                if (!depthSizes.isNullOrEmpty()) {
                    val depthSize = depthSizes.maxByOrNull { it.width * it.height }!!
                    depthWidth = depthSize.width
                    depthHeight = depthSize.height

                    depthReader = ImageReader.newInstance(
                        depthWidth, depthHeight, ImageFormat.DEPTH16, 2
                    )
                    depthReader!!.setOnImageAvailableListener(
                        { reader -> onDepthImageAvailable?.invoke(reader) },
                        backgroundHandler
                    )

                    targets.add(depthReader!!.surface)
                    requestBuilder.addTarget(depthReader!!.surface)
                }
            }

            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            if (torchOn) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(), captureCallback, backgroundHandler
                            )
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
            Log.e(TAG, "setupPreviewSession failed", e)
        }
    }

    fun setupDepthOnlySession(camera: CameraDevice) {
        try {
            val chars = cameraManager.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val depthSizes = map?.getOutputSizes(ImageFormat.DEPTH16) ?: return
            if (depthSizes.isEmpty()) return

            val depthSize = depthSizes.maxByOrNull { it.width * it.height }!!
            depthWidth = depthSize.width
            depthHeight = depthSize.height

            depthReader = ImageReader.newInstance(
                depthWidth, depthHeight, ImageFormat.DEPTH16, 2
            )
            depthReader!!.setOnImageAvailableListener(
                { reader -> onDepthImageAvailable?.invoke(reader) },
                backgroundHandler
            )

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(depthReader!!.surface)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(depthReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(), null, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Depth session failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Depth session config failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "setupDepthOnlySession failed", e)
        }
    }

 
    /** Close only the depth camera, keep RGB preview running */
    fun closeDepthOnly() {
        try {
            depthReader?.close(); depthReader = null
            depthCameraDevice?.close(); depthCameraDevice = null
        } catch (_: Exception) {}
        depthStreamActive = false
    }

    /** Open only the depth camera (RGB must already be open) */
    fun openDepthOnly(onReady: () -> Unit) {
        val sel = lastSelection ?: return
        if (sel.depthId != null && sel.depthId != sel.rgbId) {
            // Separate depth camera — open it independently, no RGB session change
            val origDepthId = sel.depthId
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return
            try {
                cameraManager.openCamera(origDepthId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        depthCameraDevice = camera
                        setupDepthOnlySession(camera)
                        onReady()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close(); depthCameraDevice = null
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Depth camera error: $error"); camera.close(); depthCameraDevice = null
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open depth camera", e)
            }
        } else if (sel.sameCameraHasDepth && cameraDevice != null) {
            // Fix #4 & #7: same-camera depth — close old depthReader before rebuilding session
            depthReader?.close(); depthReader = null
            // Rebuild preview session with depth surface added
            setupPreviewSession(cameraDevice!!, true)
            onReady()
        }
    }
   fun close() {
        try {
            cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)
            captureSession?.close(); captureSession = null
            depthReader?.close(); depthReader = null
            depthCameraDevice?.close(); depthCameraDevice = null
            cameraDevice?.close(); cameraDevice = null; torchOn = false
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        if (choices.isEmpty()) return Size(1920, 1080)
        val realW = if (width > 1) width
        else context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val realH = if (height > 1) height
        else context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val targetRatio = realH.toFloat() / realW
        return choices.minByOrNull {
            Math.abs(it.width.toFloat() / it.height - targetRatio)
        } ?: choices.first()
    }
}

