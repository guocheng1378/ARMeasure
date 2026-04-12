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

/**
 * Manages Camera2 lifecycle: opening RGB + depth cameras, preview sessions,
 * depth-only sessions, and autofocus control.
 */
class CameraController(
    private val context: Context,
    private val cameraManager: CameraManager,
    var backgroundHandler: Handler?,
    private val surfaceView: SurfaceView
) {
    companion object {
        private const val TAG = "CameraCtrl"
    }

    // ── State ──────────────────────────────────────────────
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
    var hasDepthMap: Boolean = false
        private set
    var depthStreamActive: Boolean = false
        private set

    var depthWidth: Int = 0
        private set
    var depthHeight: Int = 0
        private set

    private val cameraOpenCloseLock = Semaphore(1)

    // Callbacks set by owner
    var onDepthImageAvailable: ((ImageReader) -> Unit)? = null
    var captureCallback: CameraCaptureSession.CaptureCallback? = null

    // ═══════════════════════════════════════════════════════════
    // Camera Discovery
    // ═══════════════════════════════════════════════════════════

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

        // Prefer depth camera if it's also back-facing
        if (depthCamId != null && bestRgbId != null) {
            val depthChars = cameraManager.getCameraCharacteristics(depthCamId)
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

        val rgbId = bestRgbId ?: ids.firstOrNull() ?: return null

        rgbSensorActiveArray = cameraManager.getCameraCharacteristics(rgbId)
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        if (depthCamId != null) {
            depthSensorActiveArray = cameraManager.getCameraCharacteristics(depthCamId)
                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }

        hasDepthMap = depthCamId != null
        return CameraSelection(rgbId, depthCamId, depthCamId == rgbId)
    }

    // ═══════════════════════════════════════════════════════════
    // Open / Close
    // ═══════════════════════════════════════════════════════════

    fun openCamera(selection: CameraSelection, onReady: (Boolean) -> Unit, onError: (() -> Unit)? = null) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)

        cameraManager.openCamera(selection.rgbId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera

                if (selection.depthId != null && selection.depthId != selection.rgbId) {
                    openDepthCamera(selection.depthId)
                }

                if (surfaceView.width > 0 && surfaceView.holder.surface.isValid) {
                    setupPreviewSession(camera, selection.sameCameraHasDepth)
                }
                onReady(selection.sameCameraHasDepth)
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

    // ═══════════════════════════════════════════════════════════
    // Sessions
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════

    fun close() {
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

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

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
