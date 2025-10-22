package com.example.aimesdes.orientation

import android.graphics.RectF
import com.example.aimesdes.vision.Detection
import kotlin.math.max
import kotlin.math.min

enum class Position { IZQUIERDA, CENTRO_IZQUIERDA, CENTRO, CENTRO_DERECHA, DERECHA }
enum class Distance { LEJOS, MEDIO, CERCA }

data class OrientationResult(
    val label: String,
    val confidence: Float,
    val position: Position,
    val distance: Distance,
    val box: RectF
)

class OrientationModule(
    var targetLabel: String? = null
) {
    // Ajusta estos umbrales tras probar en el dispositivo
    private val nearTh = 0.45f
    private val midTh  = 0.25f

    private fun mapPosition(centerX: Float, frameW: Int): Position {
        val w = frameW.toFloat()
        return when {
            centerX < w * 1f / 5f -> Position.IZQUIERDA
            centerX < w * 2f / 5f -> Position.CENTRO_IZQUIERDA
            centerX < w * 3f / 5f -> Position.CENTRO
            centerX < w * 4f / 5f -> Position.CENTRO_DERECHA
            else                  -> Position.DERECHA
        }
    }

    private fun mapDistance(boxH: Float, frameH: Int): Distance {
        val rel = if (frameH > 0) boxH / frameH.toFloat() else 0f
        return when {
            rel >= nearTh -> Distance.CERCA
            rel >= midTh  -> Distance.MEDIO
            else          -> Distance.LEJOS
        }
    }

    /** Convierte detecciones de YOLO en resultados de orientación. */
    fun analyze(
        detections: List<Detection>,
        frameW: Int,
        frameH: Int
    ): List<OrientationResult> {
        if (frameW <= 0 || frameH <= 0) return emptyList()
        if (detections.isEmpty()) return emptyList()

        return detections.map { det ->
            val cx = (det.box.left + det.box.right) / 2f
            val pos = mapPosition(cx, frameW)
            val dist = mapDistance(det.box.height(), frameH)
            OrientationResult(
                label = det.label,
                confidence = det.score,
                position = pos,
                distance = dist,
                box = det.box
            )
        }
    }

    /** Toma la mejor detección (de la clase objetivo si fue definida). */
    fun selectBest(results: List<OrientationResult>): OrientationResult? {
        if (results.isEmpty()) return null
        val target = targetLabel?.lowercase()?.trim()
        val subset = if (!target.isNullOrEmpty()) {
            results.filter { it.label.lowercase().trim() == target }
        } else emptyList()
        return (if (subset.isNotEmpty()) subset else results).maxByOrNull { it.confidence }
    }

    /** Mensaje tipo TTS en español. */
    fun formatTts(result: OrientationResult): String {
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
        val nombre = result.label.ifBlank { "objeto" }
        return "$nombre $pos, $dist, $confPct por ciento."
    }
}