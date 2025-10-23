package com.example.aimesdes.AsistenteViewModel

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.aimesdes.AsistenteUIState
import com.example.aimesdes.Comando
import com.example.aimesdes.ComandosWrapper
import com.example.aimesdes.orientation.Distance
import com.example.aimesdes.orientation.OrientationResult
import com.example.aimesdes.orientation.Position
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.Normalizer
import java.util.Locale
import kotlin.collections.indices
import kotlin.collections.isNotEmpty

class AsistenteViewModel(application: Application) : AndroidViewModel(application) {

    val uiState = mutableStateOf(AsistenteUIState())
    // dentro de AsistenteViewModel
    val navigateToCamera = mutableStateOf(false)
    private var pendingNavToCamera = false

    private var tts: TextToSpeech? = null
    private val speechRecognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(application)
    private val comandos: List<Comando>

    private val handler = Handler(Looper.getMainLooper())
    private var continuousListening = true
    private var lastStartTime = 0L
    private var lastIntent: Intent? = null
    private var utteranceCounter = 0

    // Para guiado/confirmaciones
    private var ultimaEstacionConfirmada: String? = null

    // Anti-flood para guías de orientación
    private var lastGuideKey: String? = null
    private var lastGuideAtMs: Long = 0

    private var isSpeaking = false
    private var nextAllowedSpeakAtMs: Long = 0L

    private val lastSpokenAtByKey = mutableMapOf<String, Long>()
    private val GLOBAL_COOLDOWN_MS = 2000L
    private val PER_KEY_COOLDOWN_MS = 2000L
    private var visionMode: Boolean = false


    init {
        comandos = cargarComandos()
        setupTts()
        setupSpeechRecognizer()
        greetUser()
    }

    override fun onCleared() {
        super.onCleared()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    private fun greetUser() {
        val mensaje = "Hola. ¿En qué puedo ayudarte?"
        reproducirTexto(mensaje)
        uiState.value = uiState.value.copy(assistantResponse = mensaje)
    }

    fun setVisionMode(active: Boolean) {
        visionMode = active
    }

    /* ------------------- Entrada por voz ------------------- */

    fun startListening() {
        // Si TTS está hablando: pedir que termine primero
        continuousListening = true
        try { tts?.stop() } catch (e: Exception) {}

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL") // priorizar español-Chile
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getApplication<Application>().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // cambia a true si quieres offline
            // Ajustes de timeouts: pueden ayudar a que finalice más rápido tras silencio
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 800L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 600L)
        }
        lastIntent = intent
        try {
            speechRecognizer.startListening(intent)
            uiState.value = uiState.value.copy(isListening = true, recognizedText = "Escuchando...")
            lastStartTime = System.currentTimeMillis()
            Log.d("AsistenteVM", "startListening()")
        } catch (e: Exception) {
            e.printStackTrace()
            uiState.value = uiState.value.copy(isListening = false, recognizedText = "Error al iniciar micrófono")
        }
    }

    fun stopListening() {
        continuousListening = false
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        uiState.value = uiState.value.copy(isListening = false)
    }

    private fun setupTts() {
        tts = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CL")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            isSpeaking = false
                            nextAllowedSpeakAtMs = System.currentTimeMillis() + GLOBAL_COOLDOWN_MS

                            if (continuousListening && !visionMode) {
                                handler.postDelayed({ startListening() }, 200)
                            }
                            if (pendingNavToCamera) {
                                pendingNavToCamera = false
                                navigateToCamera.value = true
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        handler.post { isSpeaking = false }
                    }
                })
            }
        }
    }

    private fun setupSpeechRecognizer() {
        val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                uiState.value = uiState.value.copy(
                    isListening = true,
                    recognizedText = "Escuchando...",
                    micRmsDb = 0f
                )
                Log.d("AsistenteVM", "onReadyForSpeech")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0]
                    uiState.value = uiState.value.copy(recognizedText = partial)
                    Log.d("AsistenteVM", "partial: $partial")

                    // Intent heurístico: si vemos "ir a ..." o "estoy en ..." lo procesamos ya
                    val station = extractStationFromText(partial)
                    if (station != null) {
                        // Llamamos a procesar como comando de destino inmediatamente
                        procesarComando(partial)
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                uiState.value = uiState.value.copy(micRmsDb = rmsdB)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (!matches.isNullOrEmpty()) {
                    // Elegimos el resultado con mayor confianza si está disponible
                    var bestIndex = 0
                    if (confidences != null && confidences.isNotEmpty()) {
                        var maxConf = -1f
                        for (i in confidences.indices) {
                            if (confidences[i] > maxConf) {
                                maxConf = confidences[i]; bestIndex = i
                            }
                        }
                    }
                    val spokenText = matches[bestIndex]
                    uiState.value = uiState.value.copy(
                        recognizedText = spokenText,
                        isListening = false,
                        micRmsDb = 0f
                    )
                    Log.d("AsistenteVM", "results: $spokenText")
                    procesarComando(spokenText)
                } else {
                    uiState.value = uiState.value.copy(isListening = false, recognizedText = "No se entendió", micRmsDb = 0f)
                }
                // Reiniciar escucha si modo continuo está activo (pequeña demora para evitar loops)
                if (continuousListening) handler.postDelayed({ safeRestartListening() }, 200)
            }

            override fun onError(error: Int) {
                Log.d("AsistenteVM", "onError: $error")
                uiState.value = uiState.value.copy(
                    isListening = false,
                    recognizedText = "Error en el reconocimiento",
                    micRmsDb = 0f
                )
                // Reiniciar con debounce salvo que el usuario haya pedido detener
                if (continuousListening) handler.postDelayed({ safeRestartListening() }, 400)
            }

            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(recognitionListener)
    }

    private fun safeRestartListening() {
        val now = System.currentTimeMillis()
        if (now - lastStartTime < 400) {
            handler.postDelayed({ safeRestartListening() }, 300)
            return
        }
        try {
            lastIntent?.let {
                speechRecognizer.startListening(it)
                lastStartTime = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private fun cargarComandos(): List<Comando> {
        return try {
            val json = getApplication<Application>().assets.open("comandos.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<ComandosWrapper>() {}.type
            Gson().fromJson<ComandosWrapper>(json, type).comandos
        } catch (_: Exception) {
            emptyList()
        }
    }

    /* ------------------- Procesamiento de comandos ------------------- */

    private fun procesarComando(texto: String) {
        val normText = normalize(texto)

        // 1) Matching flexible contra JSON
        val comandoEncontrado = comandos.find { matchesCommand(normText, it) }

        if (comandoEncontrado != null) {
            var comandoFinal = comandoEncontrado

            // Caso destino_estacion sin estación explícita en JSON: intenta extraer del texto
            if (comandoEncontrado.intencion == "destino_estacion" && comandoEncontrado.estacion.isNullOrEmpty()) {
                val est = extractStationFromText(texto)
                if (!est.isNullOrEmpty()) {
                    comandoFinal = comandoEncontrado.copy(estacion = est)
                } else {
                    reproducirTexto("No entendí a qué estación te diriges. Por favor, inténtalo de nuevo.")
                    uiState.value = uiState.value.copy(assistantResponse = "No se especificó la estación.")
                    stopListening()
                    return
                }
            }

            ejecutarAccion(comandoFinal.intencion, comandoFinal)
            return
        }

        // 2) Si no coincidió, intenta extracción directa de estación
        extractStationFromText(texto)?.let { est ->
            val cmd = Comando(entrada = texto, intencion = "destino_estacion", estacion = est)
            ejecutarAccion(cmd.intencion, cmd)
            return
        }

        // 3) Nada entendido
        reproducirTexto("No entendí bien. Presiona de nuevo para hablar.")
        uiState.value = uiState.value.copy(assistantResponse = "No entendí bien.")
        pendingNavToCamera = false
        stopListening()
    }

    private fun ejecutarAccion(intencion: String, parametros: Comando) {
        val respuesta = when (intencion) {
            "buscar_objeto"   -> "Buscando ${parametros.objeto ?: "el objeto"}. Por favor, espere."
            "ubicar"          -> "Usted está en ${parametros.objeto ?: "un lugar desconocido"}."
            "activar_guiado"  -> "Iniciando guía en modo ${parametros.modo ?: "predeterminado"}."
            "confirmacion"    -> "Acción confirmada."
            "cancelar"        -> "Acción cancelada."
            "modificar"       -> "Dime qué quieres modificar."
            "repetir"         -> "Repitiendo la última instrucción."
            "estado_entorno"  -> "Describiendo el entorno. A su izquierda hay una pared y a su derecha el andén."
            "destino_estacion" -> {
                val est = parametros.estacion ?: "el destino"
                if (ultimaEstacionConfirmada == est) {
                    "Reanudando guía hacia $est."
                } else {
                    ultimaEstacionConfirmada = est
                    stopListening() // evitar cortar el inicio del guiado
                    "Iniciando guía hacia $est."
                }
            }
            else -> "Intención no reconocida."
        }

        reproducirTexto(respuesta)
        uiState.value = uiState.value.copy(assistantResponse = respuesta)
        if (intencion in listOf("buscar_objeto", "destino_estacion", "activar_guiado")) {
            pendingNavToCamera = true
        } else {
            pendingNavToCamera = false      // cualquier otra NO navega
        }
    }

    private fun reproducirTexto(texto: String) {
        utteranceCounter++
        val id = "utt-$utteranceCounter"
        try { tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id) } catch (_: Exception) {}
    }

    /* ------------------- Helpers NLU ------------------- */

    private fun normalize(text: String): String {
        var s = text.lowercase(Locale.getDefault()).trim()
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        s = s.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
        return s
    }

    private fun matchesCommand(text: String, comando: Comando): Boolean {
        val normText = normalize(text)
        return comando.entrada.split("|").any { variant ->
            val normVar = normalize(variant)
            val tokens = normVar.split(" ").filter { it.isNotBlank() }
            tokens.isNotEmpty() && tokens.all { token -> normText.contains(token) }
        }
    }

    private fun extractStationFromText(text: String): String? {
        val t = text.trim()
        val patterns = listOf(
            Regex("ir a (la |el )?estaci[oó]n ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("voy a (la |el )?estaci[oó]n ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("me dirijo a ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("quiero ir a ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("estoy en ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(t)
            if (m != null) {
                val lastGroup = m.groupValues.last().trim().replace(Regex("[\\.,]$"), "")
                if (lastGroup.length > 2) return lastGroup
            }
        }
        return null
    }

    /* ------------------- Guía por orientación (desde visión) ------------------- */

    fun speakOrientation(
        result: OrientationResult,
        minIntervalMs: Long = 0L,    // ya no lo usamos
        minConfidence: Float = 0.25f
    ) {
        if (result.confidence < minConfidence) return

        val now = System.currentTimeMillis()
        if (isSpeaking) return                      // no hablar si el TTS está en curso
        if (now < nextAllowedSpeakAtMs) return      // respeta la pausa global de 2 s

        // Clave estable: misma etiqueta + misma posición + mismo bucket de distancia
        val key = "${result.label.lowercase(Locale.getDefault())}|${result.position}|${result.distance}"

        // Cooldown por clave (misma cosa no más de cada 2 s)
        val last = lastSpokenAtByKey[key] ?: 0L
        if (now - last < PER_KEY_COOLDOWN_MS) return

        val pos = when (result.position) {
            Position.IZQUIERDA -> "a la izquierda"
            Position.CENTRO_IZQUIERDA -> "al centro izquierda"
            Position.CENTRO -> "al centro"
            Position.CENTRO_DERECHA -> "al centro derecha"
            Position.DERECHA -> "a la derecha"
        }
        val dist = when (result.distance) {
            Distance.CERCA -> "cerca"
            Distance.MEDIO -> "a media distancia"
            Distance.LEJOS -> "lejos"
        }

        val texto = "${result.label} $pos, $dist."
        reproducirTexto(texto)
        uiState.value = uiState.value.copy(assistantResponse = texto)

        // registramos el momento del aviso para esta clave
        lastSpokenAtByKey[key] = now
    }
}