package com.jonas.x24

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.jonas.x24.commands.CommandManager
import com.jonas.x24.network.ChatRequest
import com.jonas.x24.network.Message
import com.jonas.x24.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var commandManager: CommandManager
    private lateinit var tvLog: TextView
    private lateinit var btnTalk: Button

    // Keep limited history to avoid token limits
    private val history = mutableListOf<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        btnTalk = findViewById(R.id.btnTalk)

        commandManager = CommandManager(this)
        tts = TextToSpeech(this, this)

        setupPermissions()
        setupSpeechRecognizer()

        btnTalk.setOnClickListener {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                startListening()
            } else {
                log("Speech recognition not available on this device.")
            }
        }
    }

    private fun setupPermissions() {
       val permissions = arrayOf(
           Manifest.permission.RECORD_AUDIO,
           Manifest.permission.CAMERA,
           Manifest.permission.CALL_PHONE,
           Manifest.permission.SEND_SMS,
           Manifest.permission.BLUETOOTH,
           Manifest.permission.BLUETOOTH_ADMIN,
           Manifest.permission.BLUETOOTH_CONNECT
       )
       ActivityCompat.requestPermissions(this, permissions, 101)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                btnTalk.text = "Listening..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                btnTalk.text = "Processing..."
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    val msg = "I didn't catch that, please try again."
                    log(msg)
                    speak(msg)
                } else {
                    log("Speech Error Code: $error")
                }
                btnTalk.text = "TALK"
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    log("You: $text")
                    processUserInput(text)
                } else {
                    btnTalk.text = "TALK"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            log("Error starting speech: ${e.message}")
        }
    }

    private fun processUserInput(input: String) {
        history.add(Message("user", input))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = RetrofitClient.api.chat(ChatRequest(history))

                withContext(Dispatchers.Main) {
                    val rawReply = response.reply
                    // Execute commands and clean text
                    val cleanReply = commandManager.executeCommand(rawReply)

                    log("x24: $cleanReply")
                    history.add(Message("assistant", rawReply))

                    speak(cleanReply)
                    btnTalk.text = "TALK"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log("Network Error: ${e.message}")
                    speak("I cannot reach the server right now.")
                    btnTalk.text = "TALK"
                }
            }
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun log(text: String) {
        tvLog.append("\n$text")
        // Scroll to bottom
        val scroll = tvLog.parent as? android.widget.ScrollView
        scroll?.post { scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Systems online. I am ready.")
        } else {
            log("TTS Initialization failed!")
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
