package com.example

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class GymViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    val bleManager = BleManager(application)

    // Observable states exposed to Composable views
    private val _repCount = MutableStateFlow(0)
    val repCount: StateFlow<Int> = _repCount

    private val _speedState = MutableStateFlow("Idle")
    val speedState: StateFlow<String> = _speedState

    private val _formState = MutableStateFlow("Good form")
    val formState: StateFlow<String> = _formState

    private val _vitalsWarning = MutableStateFlow("Vitals Normal")
    val vitalsWarning: StateFlow<String> = _vitalsWarning

    private val _coachResponseFlow = MutableStateFlow("Trainer: Connect a sensor or simulation to start training!")
    val coachResponseFlow: StateFlow<String> = _coachResponseFlow

    private val _currentHr = MutableStateFlow(0)
    val currentHr: StateFlow<Int> = _currentHr

    private val _currentEmg = MutableStateFlow(0)
    val currentEmg: StateFlow<Int> = _currentEmg

    private val _currentAccel = MutableStateFlow(0f)
    val currentAccel: StateFlow<Float> = _currentAccel

    private val _currentGyro = MutableStateFlow(0f)
    val currentGyro: StateFlow<Float> = _currentGyro

    private val _currentAngle = MutableStateFlow(0.0)
    val currentAngle: StateFlow<Double> = _currentAngle

    // TTS engine
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Periodic coach job
    private var coachJob: Job? = null
    private var lastCoachCallTime = 0L

    init {
        tts = TextToSpeech(application, this)
        observeBleData()
        startCoachingSchedule()
        observeRepsForCoaching()
    }

    private fun observeRepsForCoaching() {
        viewModelScope.launch {
            var lastReps = 0
            repCount.collect { reps ->
                if (reps > lastReps && reps > 0) {
                    val currentTime = System.currentTimeMillis()
                    // Allow prompt coaching on new reps with a 4-second minimal cooldown
                    if (currentTime - lastCoachCallTime > 4000) {
                        lastCoachCallTime = currentTime
                        executeGeminiCoachCall()
                    }
                }
                lastReps = reps
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                Log.d("GymViewModel", "TextToSpeech initialized successfully.")
            }
        } else {
            Log.e("GymViewModel", "TextToSpeech init failed.")
        }
    }

    fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach_utterance_id")
        } else {
            Log.e("GymViewModel", "TTS is not ready.")
        }
    }

    private fun observeBleData() {
        viewModelScope.launch {
            bleManager.gymDataFlow.collect { data ->
                if (data != null) {
                    processRealtimeData(data)
                }
            }
        }
    }

    private fun processRealtimeData(data: GymData) {
        _currentHr.value = data.hr
        _currentEmg.value = data.emg
        _currentAccel.value = data.accel
        _currentGyro.value = data.gyro
        _currentAngle.value = data.angle
        _repCount.value = data.reps

        // Since ESP32 now counts reps and handles sensor fusion locally, 
        // the phone app acts as a dumb display. We retain simple direct metrics
        // calculations so that the AI Chat Coach still gets rich conversational context.
        _speedState.value = when {
            data.gyro > 80f -> "Too Fast"
            data.accel > 0.8f && data.gyro < 15f -> "Too Slow"
            data.accel > 0.4f -> "Ideal Speed"
            else -> "Stationary"
        }

        _formState.value = when {
            data.emg > 400 -> "Peak Activation"
            data.emg > 50 -> "Good Activation"
            else -> "Low Engagement"
        }

        _vitalsWarning.value = when {
            data.hr > 170 -> "CRITICAL (HR > 170)"
            data.hr > 140 -> "High Workload"
            data.hr > 40 -> "Vitals Active"
            else -> "Resting"
        }
    }

    private fun startCoachingSchedule() {
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            // Give initial delay so user can start
            delay(5000)
            while (true) {
                // If there's an active streaming session (simulated or real BLE connection)
                if (bleManager.connectionState.value == BleConnectionState.CONNECTED &&
                    _currentHr.value > 0) {
                    val currentTime = System.currentTimeMillis()
                    // Don't issue periodic speech if we just spoke within the last 5 seconds to prevent overlaps
                    if (currentTime - lastCoachCallTime > 5000) {
                        lastCoachCallTime = currentTime
                        executeGeminiCoachCall()
                    }
                }
                delay(8000) // Trigger check every 8 seconds for real-time responsiveness
            }
        }
    }

    private suspend fun executeGeminiCoachCall() {
        val count = _repCount.value
        val speed = _speedState.value
        val form = _formState.value
        val vitals = _vitalsWarning.value
        val hr = _currentHr.value
        val emg = _currentEmg.value

        val prompt = if (count > 0) {
            """
Current Workout Telemetry:
- Repetition count achieved: $count reps
- Movement speed: $speed
- Form / Muscle activation: $form ($emg uV)
- Vital heart rate: $hr BPM

You are their direct personal gym trainer. Give them immediate energetic motivation directly celebrating that they have hit $count reps! Motivate them to smash the next repetition with perfect form! Limit response to 1 or 2 fiery sentences.
""".trimIndent()
        } else {
            """
Current Workout Telemetry:
- Reps: 0 (Set is about to start)
- Muscle activation: $form ($emg uV)
- Heart rate: $hr BPM

Instruct them with fiery motivation to lift the weight and begin their bicep contraction, focusing on explosive power. Limit response to 1 short motivating sentence.
""".trimIndent()
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
            // Local high-fidelity mock advice generator in case API Key is unconfigured
            val mockPromptFeedback = generateLocalMotivationalSpeech(count, speed, form, hr)
            _coachResponseFlow.value = mockPromptFeedback
            speak(mockPromptFeedback)
        } else {
            try {
                val systemInstruction = Content(parts = listOf(Part(text = "You are a fiery, motivating, and direct personal gym coach. Limit answers to max 2 short sentences.")))
                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                    systemInstruction = systemInstruction,
                    generationConfig = GenerationConfig(temperature = 0.85f, maxOutputTokens = 120)
                )
                val response = RetrofitClient.service.generateContent(apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!responseText.isNullOrBlank()) {
                    _coachResponseFlow.value = responseText
                    speak(responseText)
                } else {
                    val defaultMsg = "That is $count reps, spectacular! Push through the pain, squeeze at the peak!"
                    _coachResponseFlow.value = defaultMsg
                    speak(defaultMsg)
                }
            } catch (e: Exception) {
                Log.e("GymViewModel", "Gemini API Client failed: ${e.message}")
                val fallbackMsg = if (count > 0) "That's $count reps! Keep squeezing, push for those extra reps!" else "Begin your first repetition, let's smash this set!"
                _coachResponseFlow.value = "Coach Fallback: $fallbackMsg"
                speak(fallbackMsg)
            }
        }
    }

    private fun generateLocalMotivationalSpeech(reps: Int, speed: String, form: String, hr: Int): String {
        return when {
            hr > 170 -> "Whoa, your heart rate is $hr BPM! Slow down, catch your breath before your next set!"
            reps > 0 && reps % 3 == 0 -> "Incredible job! You have smashed $reps reps! Stay focused and squeeze the bicep!"
            reps > 0 -> "That's $reps reps! Keep squeezing, don't stop now! Power through!"
            form.contains("Failure") -> "Muscle fatigue detected! Form is breaking, grind out one last safe repetition!"
            form.contains("Not Activated") -> "Focus on the mind-muscle connection. Squeeze at the peak of the movement!"
            speed.contains("Fast") -> "Slow down your eccentric speed! Control the weight for maximum hypertrophy!"
            speed.contains("Slow") -> "Pick up the velocity! Explode on the upward movement!"
            else -> "Perfect form and tempo! Let's get after it, push yourself!"
        }
    }

    fun startScanning() {
        bleManager.startScan()
    }

    fun disconnectSensor() {
        bleManager.disconnect()
    }

    fun startSimulate(preset: SimulationPreset) {
        resetTelemetry()
        bleManager.startSimulation(preset)
    }

    fun resetTelemetry() {
        _repCount.value = 0
        _speedState.value = "Idle"
        _formState.value = "Perfect Form"
        _vitalsWarning.value = "Vitals Normal"
        _currentHr.value = 0
        _currentEmg.value = 0
        _currentAccel.value = 0f
        _currentGyro.value = 0f
        _currentAngle.value = 0.0
        _coachResponseFlow.value = "Trainer: Ready. Initiate simulation or Bluetooth capture!"
    }

    override fun onCleared() {
        super.onCleared()
        coachJob?.cancel()
        bleManager.disconnect()
        tts?.stop()
        tts?.shutdown()
    }
}
