package com.jonas.x24

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.EditText
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.jonas.x24.commands.CommandManager
import com.jonas.x24.network.GroqMessage
import com.jonas.x24.network.GroqRequest
import com.jonas.x24.network.RetrofitClient
import com.jonas.x24.services.AutomationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var mediaPlayer: android.media.MediaPlayer? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var commandManager: CommandManager
    private lateinit var btnOpenLogs: Button
    private lateinit var btnTalk: Button
    private lateinit var btnChangeVoice: Button
    private lateinit var etGroqToken: EditText
    private lateinit var etBrainUrl: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnRefetchBrain: Button

    private lateinit var btnOverlay: Button
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("x24_prefs", MODE_PRIVATE)

        btnOpenLogs = findViewById(R.id.btnOpenLogs)
        btnOpenLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        btnTalk = findViewById(R.id.btnTalk)
        btnChangeVoice = findViewById(R.id.btnChangeVoice)
        btnOverlay = findViewById(R.id.btnOverlay)

        commandManager = CommandManager(this)
        tts = TextToSpeech(this, this)

        setupPermissions()
        setupSpeechRecognizer()

        // Start Automation Service
        startForegroundService(Intent(this, AutomationService::class.java))

        btnTalk.setOnClickListener {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                startListening()
            } else {
                log("Speech recognition not available on this device.")
            }
        }

                etGroqToken = findViewById(R.id.etGroqToken)
        etBrainUrl = findViewById(R.id.etBrainUrl)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnRefetchBrain = findViewById(R.id.btnRefetchBrain)

        // Load existing settings
        etGroqToken.setText(sharedPreferences.getString("groq_token", ""))
        etBrainUrl.setText(sharedPreferences.getString("brain_url", "https://www.preasx24.co.za/brain.txt"))

        btnSaveSettings.setOnClickListener {
            val token = etGroqToken.text.toString().trim()
            val url = etBrainUrl.text.toString().trim()
            sharedPreferences.edit().apply {
                putString("groq_token", token)
                putString("brain_url", url)
                apply()
            }
            log("Settings saved.")
            speak("Settings saved.")
        }

        btnRefetchBrain.setOnClickListener {
            val url = sharedPreferences.getString("brain_url", "https://www.preasx24.co.za/brain.txt") ?: return@setOnClickListener
            log("Fetching brain from: $url")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val brainText = response.body?.string() ?: ""
                        if (brainText.isNotEmpty()) {
                            sharedPreferences.edit().putString("system_prompt", brainText).apply()
                            withContext(Dispatchers.Main) {
                                log("Brain refetched successfully.")
                                speak("Brain updated.")
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            log("Failed to fetch brain: ${response.code}")
                            speak("Failed to fetch brain.")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        log("Error fetching brain: ${e.message}")
                        speak("Error fetching brain.")
                    }
                }
            }
        }

        btnChangeVoice.setOnClickListener {
            showVoiceSelectionDialog()
        }

        btnOverlay.setOnClickListener {
            toggleOverlayService()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("START_LISTENING", false) == true) {
            // Delay slightly to ensure UI is ready
            btnTalk.postDelayed({
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    startListening()
                }
            }, 500)
        }
    }

    private fun toggleOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
            log("Please grant 'Display over other apps' permission.")
            return
        }

        val intent = Intent(this, com.jonas.x24.services.OverlayService::class.java)
        startService(intent)
        log("Overlay Service Started. Look for the floating icon.")
    }

    // Check Accessibility Status
    private fun checkAccessibilityPermission(): Boolean {
        var accessEnabled = 0
        try {
            accessEnabled = android.provider.Settings.Secure.getInt(
                this.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: android.provider.Settings.SettingNotFoundException) {
            e.printStackTrace()
        }

        if (accessEnabled == 0) {
            return false
        } else {
            val service = "${packageName}/${com.jonas.x24.services.x24AccessibilityService::class.java.canonicalName}"
            val settingValue = android.provider.Settings.Secure.getString(
                this.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return settingValue?.contains(service) == true
        }
    }

    private fun setupPermissions() {
       val permissions = arrayOf(
           Manifest.permission.RECORD_AUDIO,
           Manifest.permission.CAMERA,
           Manifest.permission.CALL_PHONE,
           Manifest.permission.SEND_SMS,
           Manifest.permission.RECEIVE_SMS,
           Manifest.permission.READ_SMS,
           Manifest.permission.BLUETOOTH,
           Manifest.permission.BLUETOOTH_ADMIN,
           Manifest.permission.BLUETOOTH_CONNECT,
           Manifest.permission.ACCESS_FINE_LOCATION,
           Manifest.permission.ACCESS_COARSE_LOCATION
       )
       ActivityCompat.requestPermissions(this, permissions, 101)

       // Prompt for Accessibility if needed
       if (!checkAccessibilityPermission()) {
           // We could show a dialog here asking user to enable it
           // For now, we just log it or rely on user knowing
           log("TIP: Enable x24 Accessibility Service in Settings for full control.")
       }
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
        ChatHistoryManager.addMessage("user", input)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val groqToken = sharedPreferences.getString("groq_token", "") ?: ""
                if (groqToken.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        speak("Please set your Groq API token.")
                        btnTalk.text = "TALK"
                    }
                    return@launch
                }

                val systemPrompt = sharedPreferences.getString("system_prompt", "You are x24, a helpful AI assistant.") ?: "You are x24, a helpful AI assistant."
                val messages = mutableListOf(GroqMessage("system", systemPrompt))
                messages.addAll(ChatHistoryManager.getHistory())

                val request = GroqRequest(messages = messages, stream = false)
                val response = RetrofitClient.groqApi.chatCompletions("Bearer $groqToken", request)

                val rawReply = response.choices.firstOrNull()?.message?.content ?: ""

                // Execute commands (background thread for Geocoder/etc)
                val cleanReply = commandManager.executeCommand(rawReply)

                withContext(Dispatchers.Main) {
                    log("x24: $cleanReply")
                    // Save the final spoken result to history so the AI knows the outcome
                    ChatHistoryManager.addMessage("assistant", cleanReply)

                    speak(cleanReply)
                    btnTalk.text = "TALK"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    log("Network Error: ${e.message}")
                    speak("I cannot reach the server right now.")
                    btnTalk.text = "TALK"
                }
            }
        }
    }

    private fun showVoiceSelectionDialog() {
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_settings, null)
            val spinnerVoices = dialogView.findViewById<Spinner>(R.id.spinnerVoices)
            val seekBarPitch = dialogView.findViewById<SeekBar>(R.id.seekBarPitch)
            val seekBarRate = dialogView.findViewById<SeekBar>(R.id.seekBarRate)

            // Get all voices (no filter)
            val voices = tts.voices.sortedBy { it.name }
            val voiceNames = voices.map {
                val type = if (it.isNetworkConnectionRequired) "Network" else "Local"
                val locale = it.locale.displayName
                "${it.name} ($type, $locale)"
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerVoices.adapter = adapter

            // Set current selection
            val currentVoiceName = tts.voice?.name
            if (currentVoiceName != null) {
                val index = voices.indexOfFirst { it.name == currentVoiceName }
                if (index >= 0) {
                    spinnerVoices.setSelection(index)
                }
            }

            // Load saved pitch and rate
            val savedPitch = sharedPreferences.getFloat("pitch", 1.0f)
            val savedRate = sharedPreferences.getFloat("rate", 1.0f)

            // Map 0.5f - 2.0f to 0 - 200 progress (100 is default/1.0f)
            seekBarPitch.progress = (savedPitch * 100).toInt()
            seekBarRate.progress = (savedRate * 100).toInt()

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Voice Settings")
            builder.setView(dialogView)

            val dialog = builder.create()

            // Update listeners
            spinnerVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    try {
                        val selectedVoice = voices[position]
                        tts.voice = selectedVoice
                    } catch (e: Exception) {
                         log("Error setting voice: ${e.message}")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                     // Wait for stop? Or real-time?
                     // Real-time update to TTS instance, but maybe not speak immediately
                     val value = progress / 100f
                     if (seekBar == seekBarPitch) {
                         tts.setPitch(value)
                     } else {
                         tts.setSpeechRate(value)
                     }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }

            seekBarPitch.setOnSeekBarChangeListener(seekBarListener)
            seekBarRate.setOnSeekBarChangeListener(seekBarListener)

            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save") { _, _ ->
                val selectedIndex = spinnerVoices.selectedItemPosition
                if (selectedIndex >= 0) {
                    val selectedVoice = voices[selectedIndex]
                    val pitch = seekBarPitch.progress / 100f
                    val rate = seekBarRate.progress / 100f

                    with(sharedPreferences.edit()) {
                        putString("voice_name", selectedVoice.name)
                        putFloat("pitch", pitch)
                        putFloat("rate", rate)
                        apply()
                    }
                    log("Settings saved: ${selectedVoice.name}, P:$pitch, R:$rate")
                    speak("Settings updated.")
                }
            }

            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
                // Revert to saved or defaults if cancelled?
                // Currently changes are applied live to TTS object.
                // If we cancel, we should probably reload from prefs in onDismiss,
                // but for now, let's just close.
            }

            // "Auto Adjust" button as requested
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Auto") { _, _ ->
                 // Logic to pick a "best" voice and default settings
                 try {
                    val targetVoice = voices.find { it.name.contains("en-us-x-iom-network") }
                            ?: voices.find { it.name.contains("en-us-x-tpd-network") }
                            ?: voices.find { it.name.contains("en-us") && it.quality > 300 }
                            ?: voices.find { it.locale == Locale.US }
                            ?: voices.firstOrNull()

                    if (targetVoice != null) {
                         tts.voice = targetVoice
                         tts.setPitch(1.0f)
                         tts.setSpeechRate(1.0f)

                         // Update UI (though dialog closes after button press usually)
                         // Since neutral button closes dialog, we just save and speak.
                         with(sharedPreferences.edit()) {
                            putString("voice_name", targetVoice.name)
                            putFloat("pitch", 1.0f)
                            putFloat("rate", 1.0f)
                            apply()
                        }
                        log("Auto-adjusted voice to: ${targetVoice.name}")
                        speak("Voice settings auto-adjusted.")
                    }
                 } catch (e: Exception) {
                     log("Error auto-adjusting: ${e.message}")
                 }
            }

            dialog.show()
        } catch (e: Exception) {
            log("Error showing settings: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun speak(text: String) {
        // Stop any current playback
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Stop local TTS
        tts.stop()

        // Speak using local TTS
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun playAudio(file: java.io.File) {
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    file.delete()
                }
            }
        } catch (e: Exception) {
            log("Audio Playback Error: ${e.message}")
            // Fallback if playback fails
            tts.speak("Error playing audio.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun log(text: String) {
        LogManager.log(text)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
               log("Language not supported")
            } else {
                try {
                    // Load saved preferences
                    val savedVoiceName = sharedPreferences.getString("voice_name", null)
                    val savedPitch = sharedPreferences.getFloat("pitch", 1.0f)
                    val savedRate = sharedPreferences.getFloat("rate", 1.0f)

                    val voices = tts.voices
                    if (voices != null) {
                        var targetVoice: android.speech.tts.Voice? = null

                        // 1. Try saved voice
                        if (savedVoiceName != null) {
                            targetVoice = voices.find { it.name == savedVoiceName }
                        }

                        // 2. Fallback to auto-selection if no saved voice or saved voice not found
                        if (targetVoice == null) {
                            targetVoice = voices.find { it.name.contains("en-us-x-iom-network") }
                                ?: voices.find { it.name.contains("en-us-x-tpd-network") }
                                ?: voices.find { it.name.contains("en-us") && it.quality > 300 }
                                ?: voices.find { it.locale == Locale.US }
                        }

                        if (targetVoice != null) {
                            tts.voice = targetVoice
                        }
                    }
                    tts.setPitch(savedPitch)
                    tts.setSpeechRate(savedRate)
                } catch (e: Exception) {
                    // Fallback to defaults
                    tts.setPitch(1.0f)
                    tts.setSpeechRate(1.0f)
                }
                speak("Systems online. I am ready.")
            }
        } else {
            log("TTS Initialization failed!")
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        mediaPlayer?.release()
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
