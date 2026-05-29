package com.jonas.x24

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object CameraStreamManager {
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureSession: CameraCaptureSession? = null

    private var isStreaming = false
    private var currentSessionKey: String? = null

    fun startStreaming(context: Context, sessionKey: String, useFrontCamera: Boolean) {
        if (isStreaming) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission denied.")
            return
        }
        currentSessionKey = sessionKey
        isStreaming = true

        startBackgroundThread()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var targetCameraId: String? = null
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    targetCameraId = cameraId
                    break
                } else if (!useFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = cameraId
                    break
                }
            }

            if (targetCameraId == null) {
                targetCameraId = manager.cameraIdList.firstOrNull()
            }

            if (targetCameraId == null) {
                postError("No camera found.")
                stopStreaming()
                return
            }

            val characteristics = manager.getCameraCharacteristics(targetCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Use a low resolution for streaming
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.firstOrNull { it.width <= 640 && it.height <= 480 } ?: sizes?.lastOrNull() ?: android.util.Size(320, 240)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    if (!isStreaming) return@setOnImageAvailableListener
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val buffer: ByteBuffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.capacity())
                            buffer.get(bytes)

                            // Post frame directly, but scale if needed, though we already picked a small size.
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                var scaledBitmap = bitmap
                                if (bitmap.width > 640 || bitmap.height > 640) {
                                    val ratio = Math.min(640f / bitmap.width, 640f / bitmap.height)
                                    val width = Math.round(ratio * bitmap.width)
                                    val height = Math.round(ratio * bitmap.height)
                                    scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                                }

                                val baos = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos) // High compression
                                val imageBytes = baos.toByteArray()
                                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                                val key = currentSessionKey
                                if (key != null) {
                                    FirebaseDatabase.getInstance().reference
                                        .child("sessions").child(key).child("camera_stream").child("frame")
                                        .setValue(base64Image)
                                }

                                if (scaledBitmap != bitmap) {
                                    scaledBitmap.recycle()
                                }
                                bitmap.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        image?.close()
                    }
                }, backgroundHandler)
            }

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    stopStreaming()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    postError("Camera error: $error")
                    stopStreaming()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            postError("Camera exception: ${e.message}")
            stopStreaming()
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: return
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureBuilder?.addTarget(surface)

            // Optimize for frame rate
            captureBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        // Repeating request for stream
                        session.setRepeatingRequest(captureBuilder!!.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        postError("Capture failed: ${e.message}")
                        stopStreaming()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    postError("Capture session configuration failed.")
                    stopStreaming()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopBackgroundThread()
        currentSessionKey = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraStreamBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            if (Thread.currentThread() != backgroundThread) {
                backgroundThread?.join()
            }
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun postError(msg: String) {
        val key = currentSessionKey ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(key).child("results").push()
        val payload = mapOf(
            "type" to "ERROR",
            "data" to msg,
            "command" to "LIVE_CAMERA",
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(payload)
    }
}
