package com.jonas.x24.services

import android.content.Context
import android.app.Service
import android.content.Intent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import android.content.SharedPreferences
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
import android.content.BroadcastReceiver
import com.jonas.x24.ChatHistoryManager
import com.jonas.x24.LogManager
import com.jonas.x24.network.GroqRequest
import com.jonas.x24.network.GroqMessage
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.jonas.x24.R
import com.jonas.x24.commands.CommandManager
import com.jonas.x24.network.RetrofitClient
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
    private var lastInteractionTime = 0L

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.jonas.x24.NOTIFICATION_POSTED") {
                val title = intent.getStringExtra("title")
                val text = intent.getStringExtra("text")
                if (!title.isNullOrEmpty() && !text.isNullOrEmpty()) {
                    speak("New notification from $title. $text", shouldListenAfter = false)
                }
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

        LocalBroadcastManager.getInstance(this).registerReceiver(
            notificationReceiver,
            IntentFilter("com.jonas.x24.NOTIFICATION_POSTED")
        )
    }

    private fun setupFloatingView() {
        // Build view programmatically to avoid missing XML resource
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

        // Drag Logic
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
                        // Increased threshold to 30 to allow for slight movement during click
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
                (floatingView as ImageView).alpha = 0.5f // Processing
            }
            override fun onError(error: Int) {
                isListening = false
                (floatingView as ImageView).alpha = 0.8f

                // Graceful error handling for speech recognition
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions."
                    SpeechRecognizer.ERROR_NETWORK -> "Network error."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                    SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service is busy."
                    SpeechRecognizer.ERROR_SERVER -> "Audio recognition server error."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout."
                    else -> "Unknown error occurred."
                }

                // Only speak aloud for generic no-match or timeouts to avoid spamming system errors.
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    val timeSinceLast = System.currentTimeMillis() - lastInteractionTime
                    if (timeSinceLast < 60000) {
                        // Restart listening silently to keep the 1-minute window active
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Start listening directly without updating lastInteractionTime
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
                        }, 100)
                    } else {
                        speak("I didn't catch that.", shouldListenAfter = false)
                    }
                } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    speak("Network error.", shouldListenAfter = false)
                } else {
                    Log.e("OverlayService", "Speech recognizer error: $errorMessage")
                    LogManager.log("Speech Error: $errorMessage")
                }
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
                lastInteractionTime = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processInput(userText: String) {
        val lowerText = userText.lowercase()

        // If TTS is currently playing, we ONLY accept explicit interrupts to prevent feedback loops
        if (tts.isSpeaking) {
            if (lowerText.contains("wait") || lowerText.contains("stop") || lowerText.contains("cancel")) {
                tts.stop()
                try {
                    if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                } catch (e: Exception) {}
                isListening = false
                lastInteractionTime = System.currentTimeMillis()
                toggleListening()
            }
            // Ignore anything else heard while speaking
            return
        }

        if (lowerText == "stop" || lowerText == "cancel") {
            // Stop TTS and media immediately
            tts.stop()
            try {
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {}
            // Do not restart listening
            isListening = false
            return
        } else if (lowerText == "wait") {
            // Stop TTS and media, but restart listening immediately
            tts.stop()
            try {
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {}
            isListening = false
            lastInteractionTime = System.currentTimeMillis()
            toggleListening()
            return
        } else if (lowerText == "clear memory") {
            ChatHistoryManager.clear()
            speak("Memory cleared.", shouldListenAfter = false)
            return
        }

        // 1. Get Screen Context
        val screenContext = x24AccessibilityService.instance?.getScreenContext() ?: "No screen context."

        // 2. Format Message
        val prompt = "[SCREEN_CONTEXT: $screenContext]\n\nUser: $userText"
        Log.d("Overlay", "Prompt: $prompt")
        LogManager.log("User (Overlay): $userText")

        // Add user message to history
        ChatHistoryManager.addMessage("user", prompt)

        // 3. Send to AI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
                com.jonas.x24.TokenManager.init(this@OverlayService)
                val systemPrompt = prefs.getString("system_prompt", "You are x24, a helpful AI assistant.") ?: "You are x24, a helpful AI assistant."

                if (com.jonas.x24.TokenManager.getTokens().isEmpty()) {
                    withContext(Dispatchers.Main) {
                        speak("Please set your Groq API token in the main app.", shouldListenAfter = false)
                    }
                    return@launch
                }

                val messages = mutableListOf(GroqMessage("system", systemPrompt))
                messages.addAll(ChatHistoryManager.getHistory())

                val request = GroqRequest(messages = messages)
                val responseBody = com.jonas.x24.TokenManager.chatCompletionsStream(request)
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                var fullReply = StringBuilder()
                var currentSentence = java.lang.StringBuilder()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.substring(6)
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                            if (delta.has("content")) {
                                val content = delta.getString("content")
                                fullReply.append(content)
                                currentSentence.append(content)

                                // Simple sentence splitting for TTS
                                if (content.contains(".") || content.contains("?") || content.contains("!") || content.contains("\n")) {
                                    val sentenceToSpeak = currentSentence.toString().trim()
                                    if (sentenceToSpeak.isNotEmpty() && !sentenceToSpeak.startsWith("[[")) { // Don't speak commands
                                         withContext(Dispatchers.Main) {
                                              speak(sentenceToSpeak, shouldListenAfter = false) // Stream speaking
                                         }
                                    }
                                    currentSentence.clear()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // Flush remaining text
                val finalSentence = currentSentence.toString().trim()
                if (finalSentence.isNotEmpty() && !finalSentence.startsWith("[[")) {
                     withContext(Dispatchers.Main) {
                          speak(finalSentence, shouldListenAfter = true) // Final listen
                     }
                } else {
                     withContext(Dispatchers.Main) {
                          // No text to speak, but we should start listening
                          if (listenAfterSpeech && !isListening) {
                              toggleListening()
                          }
                     }
                }

                ChatHistoryManager.addMessage("assistant", fullReply.toString())
                LogManager.log("x24 (Overlay): ${fullReply.toString()}")


                // Execute commands AFTER full generation to ensure context
                val commandOutput = commandManager.executeCommand(fullReply.toString())
                if (commandOutput.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        speak(commandOutput, shouldListenAfter = true)
                    }
                }



            } catch (e: com.jonas.x24.network.AllTokensFailedException) {
                withContext(Dispatchers.Main) {
                    Log.e("OverlayService", "All tokens failed: 429 Cooling off")
                    LogManager.log("All tokens failed: 429 Cooling off")
                    speak("Cooling off", shouldListenAfter = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("OverlayService", "Network Error: ${e.message}", e)
                LogManager.log("Network Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    speak("Connection error: ${e.message}", shouldListenAfter = false)
                }
            }
        }
    }

    // Default to true for standard responses, false for errors/notifications
    private var listenAfterSpeech = false

    // Reuse TTS logic
    private fun speak(text: String, shouldListenAfter: Boolean = true) {
        listenAfterSpeech = shouldListenAfter
        // Stop any current playback
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        tts.stop()

        // Speak using local TTS
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "TTS_ID")

        // Try to start listening immediately so user can interrupt with "wait"
        if (!isListening) {
            Handler(Looper.getMainLooper()).postDelayed({
                toggleListening()
            }, 500)
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
                    // Restart listening after playback
                    if (listenAfterSpeech && !isListening) {
                        toggleListening()
                    }
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
                        if (listenAfterSpeech && !isListening) {
                            val timeSinceLast = System.currentTimeMillis() - lastInteractionTime
                            if (timeSinceLast < 60000) { // 60 seconds
                                toggleListening()
                            }
                        }
                    }

                }

                override fun onError(utteranceId: String?) {}
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        speechRecognizer.destroy()
        tts.shutdown()
        mediaPlayer?.release()
    }
}
