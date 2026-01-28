package com.jonas.x24.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.jonas.x24.R
import com.jonas.x24.commands.CommandManager
import com.jonas.x24.network.ChatRequest
import com.jonas.x24.network.Message
import com.jonas.x24.network.RetrofitClient
import com.jonas.x24.network.TtsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var commandManager: CommandManager
    private var mediaPlayer: MediaPlayer? = null
    private var isListening = false

    // Conversation History
    private val history = mutableListOf<Message>()

    // Notification Receiver
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text")
            if (!text.isNullOrEmpty()) {
                Log.d("Overlay", "Announcing: $text")
                // If currently listening, stop to speak
                if (isListening) {
                    speechRecognizer.stopListening()
                    isListening = false
                }
                speak(text)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        commandManager = CommandManager(this)
        tts = TextToSpeech(this, this)

        setupFloatingView()
        setupSpeechRecognizer()

        // Register Notification Receiver
        val filter = IntentFilter("com.jonas.x24.ANNOUNCE_NOTIFICATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
    }

    private fun setupFloatingView() {
        val icon = ImageView(this)
        icon.setImageResource(R.mipmap.ic_launcher_round)
        icon.alpha = 0.8f
        floatingView = icon

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 30 || Math.abs(dy) > 30) isClick = false
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            toggleListening()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@OverlayService, "Listening...", Toast.LENGTH_SHORT).show()
                (floatingView as ImageView).alpha = 1.0f
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                (floatingView as ImageView).alpha = 0.5f
            }
            override fun onError(error: Int) {
                isListening = false
                (floatingView as ImageView).alpha = 0.8f
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processInput(matches[0])
                }
                (floatingView as ImageView).alpha = 0.8f
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun toggleListening() {
        // Must run on Main Thread
        Handler(Looper.getMainLooper()).post {
            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
            } else {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                try {
                    speechRecognizer.startListening(intent)
                    isListening = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun processInput(userText: String) {
        // Reset Logic
        if (userText.contains("forget everything", ignoreCase = true) ||
            userText.contains("reset chat", ignoreCase = true)) {
            history.clear()
            speak("Memory wiped, boss.")
            return
        }

        // 1. Get Contexts
        val screenContext = x24AccessibilityService.instance?.getScreenContext() ?: "No screen context."
        val notificationContext = x24NotificationService.getContextString()

        // 2. Format Message
        val fullPrompt = """
[CONTEXT]
Screen: $screenContext
Notifications: $notificationContext
[/CONTEXT]

$userText
""".trim()

        Log.d("Overlay", "Prompt: $fullPrompt")

        // 3. Update History
        history.add(Message("user", fullPrompt))

        // Keep history manageable
        if (history.size > 20) {
            history.removeAt(0)
            history.removeAt(0)
        }

        // Check if user wants to use vision immediately
        val needsVision = userText.contains("look at this", ignoreCase = true) ||
                          userText.contains("what is this", ignoreCase = true) ||
                          userText.contains("see this", ignoreCase = true)

        if (needsVision) {
             handleVisionRequest()
             return
        }

        // 4. Send to AI (Text Flow)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send HISTORY list
                val response = RetrofitClient.api.chat(ChatRequest(history))
                val reply = response.reply

                // Add Assistant Reply
                history.add(Message("assistant", reply))

                // Check for REQUEST_SCREENSHOT from AI
                if (reply.contains("[[COMMAND:REQUEST_SCREENSHOT]]")) {
                    speak("Taking a look...")
                    handleVisionRequest()
                    return@launch
                }

                val cleanReply = commandManager.executeCommand(reply)

                withContext(Dispatchers.Main) {
                    speak(cleanReply)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    speak("I'm having trouble connecting.")
                }
            }
        }
    }

    private fun handleVisionRequest() {
        val service = x24AccessibilityService.instance
        if (service == null) {
            speak("I can't see the screen right now.")
            return
        }

        service.captureScreen { bitmap ->
            if (bitmap == null) {
                CoroutineScope(Dispatchers.Main).launch { speak("Failed to capture screen.") }
                return@captureScreen
            }

            val base64 = bitmapToBase64(bitmap)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Send request with Image
                    val response = RetrofitClient.api.chat(ChatRequest(history, image = base64))
                    val reply = response.reply

                    history.add(Message("assistant", reply))
                    val cleanReply = commandManager.executeCommand(reply)

                    withContext(Dispatchers.Main) {
                        speak(cleanReply)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                     withContext(Dispatchers.Main) {
                        speak("Error analyzing image.")
                    }
                }
            }
        }
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        // Resize to max 800x800 to save bandwidth
        val maxDim = 800
        var w = bitmap.width
        var h = bitmap.height
        if (w > maxDim || h > maxDim) {
             val ratio = w.toFloat() / h.toFloat()
             if (w > h) {
                 w = maxDim
                 h = (maxDim / ratio).toInt()
             } else {
                 h = maxDim
                 w = (maxDim * ratio).toInt()
             }
        }
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
        return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun speak(text: String) {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        tts.stop()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseBody = RetrofitClient.api.tts(TtsRequest(text))
                val bytes = responseBody.bytes()
                val tempFile = java.io.File.createTempFile("tts_overlay", ".mp3", cacheDir)
                java.io.FileOutputStream(tempFile).use { it.write(bytes) }

                withContext(Dispatchers.Main) {
                    playAudio(tempFile)
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
                 }
            }
        }
    }

    private fun playAudio(file: java.io.File) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    file.delete()
                    toggleListening()
                }
            }
        } catch (e: Exception) { }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post {
                        toggleListening()
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        speechRecognizer.destroy()
        tts.shutdown()
        mediaPlayer?.release()
    }
}
