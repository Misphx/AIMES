package com.example.aimesdes

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.aimesdes.orientation.Distance
import com.example.aimesdes.orientation.OrientationResult
import com.example.aimesdes.orientation.Position
import java.util.Locale

data class AsistenteUIState(
    val isListening: Boolean = false,
    val recognizedText: String = "Toca el micrófono para hablar",
    val assistantResponse: String = "",
    val micRmsDb: Float = 0f
)

class AsistenteViewModel(app: Application) : AndroidViewModel(app) {

    // ======= Estado expuesto a la UI =======
    val uiState = mutableStateOf(AsistenteUIState())
    val navigateToCamera = mutableStateOf(false)

    // Modo visión
    private val inVisionMode = mutableStateOf(false)

    // Guía de voz
    private val voiceGuidanceEnabled = mutableStateOf(true)
    private val targetLabel = mutableStateOf<String?>(null)

    // Anti-flood de voz
    private val voiceMinConf = mutableStateOf(0.80f)
    private val voiceCooldownSec = mutableStateOf(2.5f)

    // ======= SR principal (Home) =======
    private var sr: SpeechRecognizer? = null
    private var srActive = false

    // ======= TTS =======
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        // Inicializa TTS
        tts = TextToSpeech(getApplication()) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = Locale("es", "CL")
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MIC PRINCIPAL (HOME)
    // ---------------------------------------------------------------------------------------------
    fun startListening() {
        if (inVisionMode.value) return // en modo visión el mic principal no se usa
        if (srActive) return
        val context = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            uiState.value = uiState.value.copy(
                assistantResponse = "El reconocimiento de voz no está disponible en este dispositivo."
            )
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    uiState.value = uiState.value.copy(isListening = true, recognizedText = "Escuchando…")
                }
                override fun onRmsChanged(rmsdB: Float) {
                    uiState.value = uiState.value.copy(micRmsDb = rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    try { recognizer.startListening(intent) } catch (_: Exception) {}
                }
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        uiState.value = uiState.value.copy(recognizedText = text)
                        procesarComando(text)
                    }
                    try { recognizer.startListening(intent) } catch (_: Exception) {}
                }
                override fun onPartialResults(partialResults: Bundle) {
                    val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        uiState.value = uiState.value.copy(recognizedText = text)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                recognizer.startListening(intent)
                srActive = true
                uiState.value = uiState.value.copy(isListening = true)
            } catch (_: Exception) {}
        }
    }

    fun stopListening() {
        try { sr?.stopListening() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
        srActive = false
        uiState.value = uiState.value.copy(isListening = false, micRmsDb = 0f)
    }

    fun setVisionMode(on: Boolean) {
        inVisionMode.value = on
        if (on) stopListening() // asegura que no compita con el mic del Test
    }

    // ---------------------------------------------------------------------------------------------
    // TTS
    // ---------------------------------------------------------------------------------------------
    fun ttsSpeak(text: String, utteranceId: String = "aimes-tts") {
        if (!ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) } catch (_: Exception) {}
        uiState.value = uiState.value.copy(assistantResponse = text)
    }

    // ---------------------------------------------------------------------------------------------
    // COMANDOS
    // ---------------------------------------------------------------------------------------------
    fun setVoiceGuidance(enabled: Boolean) { voiceGuidanceEnabled.value = enabled }

    fun setTargetLabel(label: String?) {
        targetLabel.value = label?.lowercase()?.trim()
        if (label.isNullOrBlank()) {
            ttsSpeak("Objetivo limpiado.")
        } else {
            ttsSpeak("Objetivo $label seleccionado.")
        }
    }

    fun procesarComando(texto: String) {
        val t = texto.lowercase().trim()

        if (t.contains("abrir cámara") || t.contains("iniciar cámara") || t.contains("prueba")) {
            navigateToCamera.value = true
            return
        }

        // Guía ON/OFF
        if (t.contains("detener guía") || t.contains("silenciar guía")) {
            setVoiceGuidance(false); ttsSpeak("Guía de voz desactivada."); return
        }
        if (t.contains("reanudar guía") || t.contains("activar guía")) {
            setVoiceGuidance(true); ttsSpeak("Guía de voz activada."); return
        }

        Regex("(objetivo|buscar|ubicar|encuentra|encontrar)\\s+([\\p{L}0-9_\\s]+)")
            .find(t)?.let { m ->
                val obj = m.groupValues.getOrNull(2)?.trim()
                if (!obj.isNullOrBlank()) {
                    setTargetLabel(obj)
                    return
                }
            }

        if (t.contains("limpiar objetivo") || t.contains("borrar objetivo")) {
            setTargetLabel(null); return
        }

        if (t.contains("estado") || t.startsWith("status")) {
            val onOff = if (voiceGuidanceEnabled.value) "activada" else "desactivada"
            val obj = targetLabel.value ?: "ninguno"
            ttsSpeak("Guía $onOff. Objetivo: $obj.")
            return
        }

        ttsSpeak("No entendí el comando. Intenta con 'objetivo puerta' o 'detener guía'.")
    }

    // ---------------------------------------------------------------------------------------------
    // VOZ PARA ORIENTACIÓN (desde PerformanceTestScreen)
    // ---------------------------------------------------------------------------------------------
    private data class SpeakState(var lastBucket: String? = null, var lastSide: String? = null, var lastTs: Long = 0L)
    private val speakPerLabel = mutableMapOf<String, SpeakState>()

    private fun shouldSpeak(label: String, bucket: String?, side: String?): Boolean {
        val now = System.currentTimeMillis()
        val st = speakPerLabel.getOrPut(label) { SpeakState() }
        if (now - st.lastTs < (voiceCooldownSec.value * 1000).toLong()) return false
        val changed = (bucket != null && bucket != st.lastBucket) || (side != null && side != st.lastSide)
        if (!changed) return false
        st.lastBucket = bucket
        st.lastSide = side
        st.lastTs = now
        return true
    }

    fun speakOrientation(o: OrientationResult) {
        if (!voiceGuidanceEnabled.value) return
        if (o.confidence < voiceMinConf.value) return

        targetLabel.value?.let { tgt ->
            if (!o.label.equals(tgt, ignoreCase = true)) return
        }

        val bucket = when (o.distance) {
            Distance.CERCA -> "cerca"
            Distance.MEDIO -> "medio"
            Distance.LEJOS -> "lejos"
        }
        val side = when (o.position) {
            Position.IZQUIERDA, Position.CENTRO_IZQUIERDA -> "a la izquierda"
            Position.CENTRO -> "al frente"
            Position.CENTRO_DERECHA, Position.DERECHA -> "a la derecha"
        }

        if (!shouldSpeak(o.label.lowercase(), bucket, side)) return

        val meters = o.distanceMeters?.let { String.format("%.1f", it) + " m" }
        val msg = buildString {
            append(o.label)
            append(" ").append(side)
            append(", ").append(bucket)
            if (meters != null) append(" (").append(meters).append(")")
        }
        ttsSpeak(msg)
    }

    override fun onCleared() {
        super.onCleared()
        try { sr?.stopListening() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
    }
}