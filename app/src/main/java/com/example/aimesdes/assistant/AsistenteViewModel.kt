package com.example.aimesdes.assistant

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
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.Normalizer
import java.util.Locale

// ----------------- Modelos JSON de comandos -----------------
data class Comando(
    val entrada: String,
    val intencion: String,
    val objeto: String? = null,
    val modo: String? = null,
    val estacion: String? = null
)
data class ComandosWrapper(val comandos: List<Comando>)

// ----------------- UI State visible para las pantallas -----------------
data class AsistenteUIState(
    val isListening: Boolean = false,
    val recognizedText: String = "Toca el micrófono para hablar",
    val assistantResponse: String = "",
    val micRmsDb: Float = 0f,
    val estacionActual: String? = null,     // <--- NUEVO
    val estacionDestino: String? = null     // <--- NUEVO
)

// ----------------- ViewModel -----------------
class AsistenteViewModel(application: Application) : AndroidViewModel(application) {
    val uiState = mutableStateOf(AsistenteUIState())

    private var tts: TextToSpeech? = null
    private val speechRecognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(application)
    private val comandos: List<Comando>

    private val handler = Handler(Looper.getMainLooper())
    private var continuousListening = true
    private var lastStartTime = 0L
    private var lastIntent: Intent? = null
    private var utteranceCounter = 0

    private var esperandoDestino: Boolean = false

    private var ultimaEstacionConfirmada: String? = null

    init {
        comandos = cargarComandos()
        setupTts()
        setupSpeechRecognizer()
        greetUser()
    }

    fun setContinuousListening(enabled: Boolean) {
        continuousListening = enabled
    }

    private fun greetUser() {
        val mensaje = "Hola. ¿En qué puedo ayudarte?"
        reproducirTexto(mensaje)
        uiState.value = uiState.value.copy(assistantResponse = mensaje)
    }

    fun startListening() {
        try { tts?.stop() } catch (_: Exception) {}
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getApplication<Application>().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
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
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (continuousListening) handler.postDelayed({ startListening() }, 200)
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    private fun setupSpeechRecognizer() {
        val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                uiState.value = uiState.value.copy(isListening = true, recognizedText = "Escuchando...", micRmsDb = 0f)
                Log.d("AsistenteVM", "onReadyForSpeech")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0]
                    uiState.value = uiState.value.copy(recognizedText = partial)
                    Log.d("AsistenteVM", "partial: $partial")
                    val station = extractStationFromText(partial)
                    if (station != null) procesarComando(partial)
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                uiState.value = uiState.value.copy(micRmsDb = rmsdB)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (!matches.isNullOrEmpty()) {
                    var bestIndex = 0
                    if (confidences != null && confidences.isNotEmpty()) {
                        var maxConf = -1f
                        for (i in confidences.indices) if (confidences[i] > maxConf) { maxConf = confidences[i]; bestIndex = i }
                    }
                    val spokenText = matches[bestIndex]
                    uiState.value = uiState.value.copy(recognizedText = spokenText, isListening = false, micRmsDb = 0f)
                    Log.d("AsistenteVM", "results: $spokenText")
                    procesarComando(spokenText)
                } else {
                    uiState.value = uiState.value.copy(isListening = false, recognizedText = "No se entendió", micRmsDb = 0f)
                }
                if (continuousListening) handler.postDelayed({ safeRestartListening() }, 200)
            }

            override fun onError(error: Int) {
                Log.d("AsistenteVM", "onError: $error")
                uiState.value = uiState.value.copy(isListening = false, recognizedText = "Error en el reconocimiento", micRmsDb = 0f)
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cargarComandos(): List<Comando> {
        return try {
            val jsonString = getApplication<Application>().assets.open("Comandos.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<ComandosWrapper>() {}.type
            val wrapper: ComandosWrapper = Gson().fromJson(jsonString, type)
            wrapper.comandos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun procesarComando(texto: String) {
        val normText = normalize(texto)

        // 1) Si el usuario dice “estoy en …” → fijar estación actual y preguntar destino
        val estActual = extractDesdeEstoyEn(texto)
        if (!estActual.isNullOrBlank()) {
            uiState.value = uiState.value.copy(estacionActual = estActual)
            esperandoDestino = true
            val r = "Entendido. Estás en ${estActual}. ¿Hacia dónde te diriges?"
            reproducirTexto(r)
            uiState.value = uiState.value.copy(assistantResponse = r)
            return
        }

        // 2) Si estamos esperando el destino y detectamos una estación → fijar destino y confirmar guía
        if (esperandoDestino) {
            val estDest = extractDestinoLibre(texto) ?: extractStationFromText(texto)
            if (!estDest.isNullOrBlank()) {
                uiState.value = uiState.value.copy(estacionDestino = estDest)
                esperandoDestino = false
                val r = "Iniciando guía hacia ${estDest}."
                reproducirTexto(r)
                uiState.value = uiState.value.copy(assistantResponse = r)
                // opcional: detener escucha para evitar solaparse con TTS
                stopListening()
                return
            }
        }

        // 3) Flujo original de comandos JSON (por si dijo un comando predefinido)
        val comandoEncontrado = comandos.find { matchesCommand(normText, it) }
        if (comandoEncontrado != null) {
            if (comandoEncontrado.intencion == "destino_estacion" && comandoEncontrado.estacion.isNullOrEmpty()) {
                val estacionExtraida = extractDestinoLibre(texto) ?: extractStationFromText(texto)
                val comandoFinal = comandoEncontrado.copy(estacion = estacionExtraida)
                ejecutarAccion(comandoFinal.intencion, comandoFinal)
            } else {
                ejecutarAccion(comandoEncontrado.intencion, comandoEncontrado)
            }
            return
        }

        // 4) Fallback: intenta tratar como destino directo (“me dirijo a …”)
        val estacionExtraida = extractDestinoLibre(texto) ?: extractStationFromText(texto)
        if (!estacionExtraida.isNullOrBlank()) {
            val comandoTemporal = Comando(
                entrada = texto,
                intencion = "destino_estacion",
                estacion = estacionExtraida
            )
            ejecutarAccion(comandoTemporal.intencion, comandoTemporal)
            return
        }

        // 5) No se entendió
        reproducirTexto("No entendí bien. ¿Hacia dónde te diriges?")
        uiState.value = uiState.value.copy(assistantResponse = "No entendí bien.")
        stopListening()
    }


    private fun ejecutarAccion(intencion: String, parametros: Comando) {
        val respuesta: String = when (intencion) {
            "buscar_objeto" -> "Buscando ${parametros.objeto ?: "el objeto"}. Por favor, espere."
            "ubicar" -> "Usted está en ${parametros.objeto ?: "un lugar desconocido"}."
            "activar_guiado" -> "Iniciando guía en modo ${parametros.modo ?: "predeterminado"}."
            "destino_estacion" -> {
                val est = parametros.estacion ?: "el destino"
                // publica destino (PerformanceTestScreen lo escuchará)
                uiState.value = uiState.value.copy(estacionDestino = est)
                // respuesta por TTS como antes
                if (ultimaEstacionConfirmada == est) {
                    "Reanudando guía hacia $est."
                } else {
                    ultimaEstacionConfirmada = est
                    stopListening()
                    "Iniciando guía hacia $est."
                }
            }
            "confirmacion" -> "Comando confirmado."
            "cancelar" -> "Operación cancelada."
            "modificar" -> "¿Qué deseas modificar?"
            "repetir" -> "Repitiendo última acción..."
            "estado_entorno" -> "Mostrando información de entorno..."
            else -> "Intención no reconocida."
        }
        reproducirTexto(respuesta)
        uiState.value = uiState.value.copy(assistantResponse = respuesta)
    }

    private fun reproducirTexto(texto: String) {
        try {
            utteranceCounter++
            val id = "utt-$utteranceCounter"
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onCleared() {
        super.onCleared()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        speechRecognizer.destroy()
        handler.removeCallbacksAndMessages(null)
    }

    // --------------------- Helpers ---------------------
    private fun normalize(text: String): String {
        var s = text.lowercase(Locale.getDefault()).trim()
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        s = s.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ")
        return s
    }

    private fun matchesCommand(text: String, comando: Comando): Boolean {
        val normText = normalize(text)
        val tokensTexto = normText.split(" ").toSet()
        val variantes = comando.entrada.split("|")
        return variantes.any { variante ->
            val normVariante = normalize(variante)
            val tokensComando = normVariante.split(" ")
            tokensComando.all { tokenComando -> tokensTexto.contains(tokenComando) }
        }
    }

    fun extractStationFromText(text: String): String? {
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
                return lastGroup
            }
        }
        return null
    }

    fun say(texto: String) {
        reproducirTexto(texto)
    }
    private fun extractDesdeEstoyEn(text: String): String? {
        val m = Regex("estoy en ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE).find(text.trim())
        return m?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("[\\.,]$"), "")
    }

    private fun extractDestinoLibre(text: String): String? {
        // Frases típicas para destino (sin “estoy en”)
        val pats = listOf(
            Regex("ir a (la |el )?estaci[oó]n ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("voy a (la |el )?estaci[oó]n ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("me dirijo a ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("quiero ir a ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("destino ([\\p{L}0-9\\s]+)", RegexOption.IGNORE_CASE)
        )
        val t = text.trim()
        for (p in pats) {
            val m = p.find(t)
            if (m != null) {
                return m.groupValues.last().trim().replace(Regex("[\\.,]$"), "")
            }
        }
        return null
    }


    fun setEstacionActual(nombre: String?) {
        uiState.value = uiState.value.copy(estacionActual = nombre)
    }
    fun clearRuta() {
        uiState.value = uiState.value.copy(estacionDestino = null)
    }

}
