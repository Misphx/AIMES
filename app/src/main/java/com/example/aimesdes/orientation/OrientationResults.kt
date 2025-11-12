package com.example.aimesdes.orientation

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.aimesdes.navigation.DireccionMetro
import com.example.aimesdes.vision.Detection
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

// ---------------- ENUMS + DATA ----------------

enum class Position { IZQUIERDA, CENTRO_IZQUIERDA, CENTRO, CENTRO_DERECHA, DERECHA }
enum class Distance { LEJOS, MEDIO, CERCA }

enum class NavType { GO_STRAIGHT, TURN_LEFT, TURN_RIGHT }

private const val TURN_THRESH = 0.15f    // 15% de la mitad de imagen
private const val HYSTERESIS = 0.05f     // evita “flapping”

data class NavInstruction(
    val type: NavType,
    val confidence: Float = 1f
)

data class OrientationResult(
    val label: String,
    val confidence: Float,
    val position: Position,
    val distance: Distance,
    val box: RectF,
)

// --------------- ORIENTATION + OCR + TTS ----------------

class OrientationModule(
    /** Si quieres fijar una clase objetivo (“entrada”, “escalera”, etc.) */
    var targetLabel: String? = null
) {
    private val nearTh = 0.45f
    private val midTh  = 0.25f

    // salida de voz desacoplada (inyectas asistenteViewModel.say)
    private var speaker: ((String) -> Unit)? = null
    fun setSpeaker(say: (String) -> Unit) { speaker = say }

    // anti–spam
    private var lastSpoken = ""
    private var lastTs = 0L
    private var ocrBusy = false
    private var lastNav: NavType? = null

    // labels que consideramos “señales”
    private val signalLabels = setOf("senales_amarillas","senales_azules","senales_cafes","senales_rojas","senales_rosas","senales_verdes")

    // estaciones (orden fijo) – usamos extremos
    private val estaciones = listOf(
        "cerrillos","lo valledor","pac","franklin","biobío",
        "ñuble","estadio nacional","ñuñoa","inés de suárez","los leones"
    )

    // --------- mapeos base ---------
    private fun mapPosition(centerX: Float, frameW: Int): Position {
        val w = frameW.toFloat()
        return when {
            centerX < w * 1f/5f -> Position.IZQUIERDA
            centerX < w * 2f/5f -> Position.CENTRO_IZQUIERDA
            centerX < w * 3f/5f -> Position.CENTRO
            centerX < w * 4f/5f -> Position.CENTRO_DERECHA
            else -> Position.DERECHA
        }
    }
    private fun mapDistance(boxH: Float, frameH: Int): Distance {
        val rel = boxH / frameH.toFloat()
        return when {
            rel >= nearTh -> Distance.CERCA
            rel >= midTh  -> Distance.MEDIO
            else          -> Distance.LEJOS
        }
    }

    // --------- API existente ---------
    fun analyze(detections: List<Detection>, frameW: Int, frameH: Int): List<OrientationResult> {
        if (frameW <= 0 || frameH <= 0 || detections.isEmpty()) return emptyList()
        return detections.map { det ->
            val cx = (det.box.left + det.box.right) / 2f
            OrientationResult(
                label = det.label,
                confidence = det.score,
                position = mapPosition(cx, frameW),
                distance = mapDistance(det.box.height(), frameH),
                box = det.box
            )
        }
    }

    fun selectBest(results: List<OrientationResult>): OrientationResult? {
        if (results.isEmpty()) return null
        val target = targetLabel?.lowercase()?.trim()
        val subset = if (!target.isNullOrEmpty()) {
            results.filter { it.label.lowercase().trim() == target }
        } else emptyList()
        return (if (subset.isNotEmpty()) subset else results).maxByOrNull { it.confidence }
    }

    // ------- TTS ambiental (reglas de labels/objetos) -------
    fun formatTts(result: OrientationResult): String {
        val raw = result.label.lowercase()

        // Regla 1: podotáctil -> NO decir nada salvo que esté LEJOS
        if ((raw == "podo_circulo" || raw == "podo_linea") && result.distance != Distance.LEJOS) {
            return "" // suprime TTS
        }

        // Mapeo de nombres "bonitos" para TTS
        val nombre = when (raw) {
            "escalera_norm", "escalera_meca" -> "escaleras"
            "podo_circulo", "podo_linea"     -> "piso podotáctil"
            "senales_rosas" -> "señal rosa"
            else -> if (result.label.isBlank()) "objeto" else result.label
        }

        val pos = when (result.position) {
            Position.IZQUIERDA -> "izquierda"
            Position.CENTRO_IZQUIERDA -> "centro izquierda"
            Position.CENTRO -> "al centro"
            Position.CENTRO_DERECHA -> "centro derecha"
            Position.DERECHA -> "derecha"
        }
        val dist = when (result.distance) {
            Distance.CERCA -> "cerca"
            Distance.MEDIO -> "a media distancia"
            Distance.LEJOS -> "lejos"
        }
        val confPct = (result.confidence * 100).toInt()
        return "$nombre $pos, $dist, $confPct por ciento."
    }

    // ------------------ Navegación a partir de señal ------------------

    /** Devuelve instrucción de navegación según la X del letrero vs centro del frame (640). */
    private fun inferByGeometry(box: RectF, frameW: Int = 640): NavInstruction {
        val cx = (box.left + box.right) / 2f
        val center = frameW / 2f
        val norm = (cx - center) / center // -1..1
        return when {
            norm > (TURN_THRESH + HYSTERESIS)  -> NavInstruction(NavType.TURN_RIGHT, confidence = norm)
            norm < -(TURN_THRESH + HYSTERESIS) -> NavInstruction(NavType.TURN_LEFT,  confidence = -norm)
            else                               -> NavInstruction(NavType.GO_STRAIGHT, confidence = 1f - kotlin.math.abs(norm))
        }
    }

    /** Sobre-escribe con OCR si hay flechas/palabras fuertes. */
    private fun overrideWithOcr(rawText: String?, fallback: NavInstruction): NavInstruction {
        if (rawText.isNullOrBlank()) return fallback
        val t = norm(rawText)
        return when {
            "→" in t || "derecha" in t || "right" in t   -> NavInstruction(NavType.TURN_RIGHT, 1f)
            "←" in t || "izquierda" in t || "left" in t  -> NavInstruction(NavType.TURN_LEFT,  1f)
            "↑" in t || "recto" in t || "frente" in t ||
                    "straight" in t                              -> NavInstruction(NavType.GO_STRAIGHT, 1f)
            else -> fallback
        }
    }

    /** Frase TTS final considerando Distance (CERCA/MEDIO/LEJOS). */
    private fun formatNavTts(nav: NavInstruction, dist: Distance): String {
        val whenTxt = when (dist) {
            Distance.CERCA -> "ahora"
            Distance.MEDIO -> "en unos metros"
            Distance.LEJOS -> "sigue recto hacia la señal"
        }
        return when (nav.type) {
            NavType.GO_STRAIGHT ->
                if (dist == Distance.LEJOS) "Sigue derecho hacia la señal." else "$whenTxt, sigue derecho."
            NavType.TURN_RIGHT ->
                if (dist == Distance.CERCA) "Ahora dobla a la derecha." else "$whenTxt, dobla a la derecha."
            NavType.TURN_LEFT  ->
                if (dist == Distance.CERCA) "Ahora dobla a la izquierda." else "$whenTxt, dobla a la izquierda."
        }
    }

    // ------------------ OCR + validación de “Dirección a …” + navegación ------------------

    suspend fun guideWithOcr(
        results: List<OrientationResult>,
        previewBitmap: Bitmap?,
        origen: String?,
        destino: String?
    ) {
        if (previewBitmap == null) return
        if (origen.isNullOrBlank() || destino.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (ocrBusy || now - lastTs < 5000) return

        // top-3 señales (en coords 640x640)
        val signals = results
            .filter { it.label.lowercase() in signalLabels }
            .sortedByDescending { it.box.width() * it.box.height() }
            .take(3)
        if (signals.isEmpty()) return

        val extremoOk = extremoEsperado(origen, destino)
        val extremoOkNorm = norm(extremoOk)

        // map 640→preview (FILL_CENTER como tu overlay)
        val model = 640f
        val pvW = previewBitmap.width.toFloat()
        val pvH = previewBitmap.height.toFloat()
        val scale = max(pvW / model, pvH / model)
        val offX = (pvW - model * scale) / 2f
        val offY = (pvH - model * scale) / 2f

        ocrBusy = true
        try {
            var matchOk = false
            var matchedSignal: OrientationResult? = null
            var matchedRaw: String? = null

            for (s in signals) {
                val crop = cropFromPreview(previewBitmap, s.box, scale, offX, offY) ?: continue
                val raw = Ocr.readText(crop)
                if (raw.isBlank()) continue

                val ntext = norm(raw)
                val m = Regex("direccion\\s+a\\s+([\\p{L}0-9\\s]+)").find(ntext)
                val destinoCartelNorm = m?.groupValues?.getOrNull(1)?.let { norm(it) } ?: continue

                if (destinoCartelNorm == extremoOkNorm) {
                    matchOk = true
                    matchedSignal = s
                    matchedRaw = raw
                    break
                }
            }

            if (matchOk && matchedSignal != null) {
                // 1) Infiere navegación por geometría
                var nav = inferByGeometry(matchedSignal.box, frameW = 640)
                // 2) Overwrite con OCR si hay flechas/palabras
                nav = overrideWithOcr(matchedRaw, nav)
                // 3) Frase con distancia del objeto
                val phrase = formatNavTts(nav, matchedSignal.distance)
                // 4) Anti-spam por cambio de instrucción
                if (lastNav != nav.type) {
                    speakOnce(phrase)
                    lastNav = nav.type
                }
            } else {
                speakOnce("Dirección de andén incorrecto, debes dirigirte hacia $extremoOk.")
            }
            lastTs = System.currentTimeMillis()
        } finally {
            ocrBusy = false
        }
    }

    private fun cropFromPreview(
        frame: Bitmap,
        modelBox: RectF,
        scale: Float,
        offX: Float,
        offY: Float
    ): Bitmap? {
        val l = (offX + modelBox.left   * scale).toInt().coerceIn(0, frame.width)
        val t = (offY + modelBox.top    * scale).toInt().coerceIn(0, frame.height)
        val r = (offX + modelBox.right  * scale).toInt().coerceIn(0, frame.width)
        val b = (offY + modelBox.bottom * scale).toInt().coerceIn(0, frame.height)
        if (r <= l || b <= t) return null
        return try { Bitmap.createBitmap(frame, l, t, r - l, b - t) } catch (_: Exception) { null }
    }

    private fun extremoEsperado(origen: String, destino: String): String {
        val msg = DireccionMetro.decidirDireccion(
            mapOf("estacion_actual" to origen, "estacion_destino" to destino)
        )
        // si el mensaje sugiere “ir al último” tomamos 'Los Leones'; si no, 'Cerrillos'
        return if (norm(msg).contains(norm(estaciones.last()))) "Los Leones" else "Cerrillos"
    }

    private fun speakOnce(text: String) {
        if (text != lastSpoken) {
            speaker?.invoke(text)
            lastSpoken = text
        }
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .replace("ñ","n")
        .trim()
}

// --------------- OCR embebido (privado al archivo) ---------------

private object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Devuelve "" si falla o no hay texto (no lanza excepciones hacia arriba). */
    suspend fun readText(bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            result.text ?: ""
        } catch (_: Exception) { "" }
    }
}
