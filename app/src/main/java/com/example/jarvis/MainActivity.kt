package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("jarvis_settings", MODE_PRIVATE) }
    private val deviceId by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var config by mutableStateOf(JarvisConfig("", "", ""))
    private var status by mutableStateOf("SYSTEM STANDBY")
    private var busy by mutableStateOf(false)
    private var wakeRequested by mutableStateOf(false)
    private var listening = false
    private var speaking = false
    private var wakeCapturing = false
    private var openSettings by mutableStateOf(false)
    private var pending by mutableStateOf<ActionTicket?>(null)
    private val messages = mutableStateListOf<ChatLine>()
    private var recognizer: SpeechRecognizer? = null
    private var wakeManager: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = JarvisConfig(
            prefs.getString("server", "") ?: "",
            prefs.getString("token", "") ?: "",
            prefs.getString("picovoice", "") ?: ""
        )
        messages.add(ChatLine(false, "JARVIS online. Configure the server, then tap the microphone or activate the wake word."))
        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = runOnUiThread { speaking = true }
                override fun onDone(utteranceId: String?) = runOnUiThread { speaking = false; resumeWakeIfNeeded() }
                override fun onError(utteranceId: String?) = runOnUiThread { speaking = false; resumeWakeIfNeeded() }
            })
        }
        setContent {
            JarvisScreen(
                status = status, busy = busy, messages = messages, config = config,
                showSettings = openSettings, wakeEnabled = wakeRequested, action = pending,
                onSend = { sendMessage(it) }, onMic = { checkMicThenListen() },
                onWakeToggle = { toggleWake() },
                onSettingsToggle = { openSettings = !openSettings },
                onSave = { newConfig ->
                    config = newConfig
                    prefs.edit().putString("server", newConfig.baseUrl)
                        .putString("token", newConfig.appToken)
                        .putString("picovoice", newConfig.picovoiceKey).apply()
                    stopWakeEngine()
                    if (wakeRequested) resumeWakeIfNeeded()
                    status = "Configuration saved"
                    openSettings = false
                },
                onConfirm = { executePending() }, onCancel = { pending = null },
                onClearMemory = { eraseMemory() }
            )
        }
    }

    private fun checkMicThenListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (busy || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "No Android speech recognition service available"
            return
        }
        stopWakeCapture()
        tts?.stop()
        speaking = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status = "LISTENING" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status = "PROCESSING VOICE" }
                override fun onError(error: Int) {
                    listening = false
                    status = "Speech recognition error $error — try again"
                    resumeWakeIfNeeded()
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!words.isNullOrBlank()) sendMessage(words) else resumeWakeIfNeeded()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listening = true
        status = "LISTENING"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            recognizer?.startListening(intent)
        } catch (ex: Exception) {
            listening = false
            status = "Unable to start microphone: ${ex.message}"
            resumeWakeIfNeeded()
        }
    }

    private fun sendMessage(input: String) {
        val message = input.trim()
        if (message.isEmpty() || busy) return
        if (config.baseUrl.isBlank() || config.appToken.isBlank()) {
            status = "Open settings and add your backend URL and app token"
            openSettings = true
            return
        }
        stopWakeCapture()
        messages.add(ChatLine(true, message))
        busy = true
        status = "THINKING"
        lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { ApiClient.chat(config, deviceId, message) }
                messages.add(ChatLine(false, reply.reply))
                pending = reply.action
                status = if (reply.action == null) "SYSTEM ONLINE" else "ACTION AWAITING APPROVAL"
                speak(reply.reply)
            } catch (ex: Exception) {
                status = "Connection error"
                messages.add(ChatLine(false, "Request failed: ${ex.message}"))
                resumeWakeIfNeeded()
            } finally {
                busy = false
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            speaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
        } else {
            resumeWakeIfNeeded()
        }
    }

    private fun toggleWake() {
        if (!wakeRequested && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Allow microphone permission first using the mic button"
            return
        }
        wakeRequested = !wakeRequested
        if (wakeRequested) resumeWakeIfNeeded() else { stopWakeEngine(); status = "WAKE WORD OFF" }
    }

    private fun resumeWakeIfNeeded() {
        if (!wakeRequested || busy || listening || speaking || isFinishing) return
        if (config.picovoiceKey.isBlank()) {
            status = "Add your Picovoice AccessKey in Settings"
            return
        }
        try {
            if (wakeManager == null) {
                wakeManager = PorcupineManager.Builder()
                    .setAccessKey(config.picovoiceKey)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .build(this, PorcupineManagerCallback { _ ->
                        runOnUiThread {
                            stopWakeCapture()
                            checkMicThenListen()
                        }
                    })
            }
            if (!wakeCapturing) {
                wakeManager?.start()
                wakeCapturing = true
                status = "SAY JARVIS"
            }
        } catch (ex: Exception) {
            wakeRequested = false
            status = "Wake-word setup failed: ${ex.message}"
            stopWakeEngine()
        }
    }

    private fun stopWakeCapture() {
        if (wakeCapturing) {
            runCatching { wakeManager?.stop() }
            wakeCapturing = false
        }
    }

    private fun stopWakeEngine() {
        stopWakeCapture()
        runCatching { wakeManager?.delete() }
        wakeManager = null
    }

    private fun executePending() {
        val ticket = pending ?: return
        pending = null
        busy = true
        status = "EXECUTING APPROVED ACTION"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ApiClient.execute(config, deviceId, ticket.id) }
                if (ticket.kind == "android_app") {
                    launchAllowedApp(result.getString("launch"))
                }
                val msg = result.optString("message", "Action completed")
                messages.add(ChatLine(false, msg))
                status = "SYSTEM ONLINE"
            } catch (ex: Exception) {
                status = "ACTION FAILED"
                messages.add(ChatLine(false, "Action failed: ${ex.message}"))
            } finally {
                busy = false
                resumeWakeIfNeeded()
            }
        }
    }

    private fun launchAllowedApp(which: String) {
        val intent = when (which) {
            "youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "dialer" -> Intent(Intent.ACTION_DIAL)
            else -> return
        }
        try { startActivity(intent) } catch (ex: Exception) { status = "No app available for $which" }
    }

    private fun eraseMemory() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.eraseMemory(config, deviceId) }
                messages.clear()
                messages.add(ChatLine(false, "Saved conversations and memory erased on the backend."))
                pending = null
                status = "MEMORY ERASED"
            } catch (ex: Exception) {
                status = "Unable to erase memory: ${ex.message}"
            } finally { busy = false }
        }
    }

    override fun onStop() {
        stopWakeCapture() // Foreground-only wake word. No stealth/background microphone capture.
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (wakeRequested) resumeWakeIfNeeded()
    }

    override fun onDestroy() {
        stopWakeEngine()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
