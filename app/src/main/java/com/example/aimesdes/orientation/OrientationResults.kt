package com.example.aimesdes.orientation

import android.graphics.RectF
import com.example.aimesdes.vision.Detection
import kotlin.math.*

enum class Position { IZQUIERDA, CENTRO_IZQUIERDA, CENTRO, CENTRO_DERECHA, DERECHA }
enum class Distance { LEJOS, MEDIO, CERCA }

data class OrientationResult(
    val label: String,
    val confidence: Float,
    val position: Position,
    val distance: Distance,
    val box: RectF,
    val distanceMeters: Float? = null
)

class OrientationModule(
    var targetLabel: String? = null
) {
    // --- CONSTANTES A CALIBRAR ---
    private val FOCAL_LENGTH_PIXELS = 800f
    private val KNOWN_OBJECT_HEIGHT_METERS = 1.7f
    private val CAMERA_HEIGHT_METERS = 1.6f

    // Constante de calibración POV (se puede medir empíricamente una vez)
    private var povConstantK = 480f // ejemplo inicial, ajustable en campo

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

    /** Método clásico pinhole. */
    private fun estimateDistanceMeters(boxHeightPixels: Float): Float? {
        if (boxHeightPixels <= 0) return null
        return (KNOWN_OBJECT_HEIGHT_METERS * FOCAL_LENGTH_PIXELS) / boxHeightPixels
    }

    /** Corrección angular horizontal. */
    private fun applyAngularCorrection(rawDistance: Float, boxCenterX: Float, frameW: Int): Float {
        val offsetX = boxCenterX - (frameW / 2f)
        val theta = atan(offsetX / FOCAL_LENGTH_PIXELS)
        return rawDistance * cos(theta)
    }

    /** --- NUEVO ---: Estimación por perspectiva vertical (POV fijo en parte inferior). */
    private fun estimateDistancePOV(boxBottomY: Float, frameH: Int): Float? {
        val dy = (frameH - boxBottomY)
        if (dy <= 0) return null
        return povConstantK / dy
    }

    /** Permite calibrar la constante POV automáticamente. */
    fun calibratePOV(frameH: Int, knownDistanceMeters: Float, objectBaseY: Float) {
        val dy = (frameH - objectBaseY)
        if (dy > 0) povConstantK = knownDistanceMeters * dy
    }

    /** Fusión ponderada. */
    private fun fuseDistances(d1: Float?, d2: Float?): Float? {
        if (d1 == null && d2 == null) return null
        if (d1 == null) return d2
        if (d2 == null) return d1
        return 0.5f * d1 + 0.5f * d2
    }

    /** Análisis principal. */
    fun analyze(
        detections: List<Detection>,
        frameW: Int,
        frameH: Int
    ): List<OrientationResult> {
        if (detections.isEmpty()) return emptyList()

        return detections.map { det ->
            val cx = (det.box.left + det.box.right) / 2f
            val pos = mapPosition(cx, frameW)
            val distCat = mapDistance(det.box.height(), frameH)

            val dPinhole = estimateDistanceMeters(det.box.height())
            val dPOV = estimateDistancePOV(det.box.bottom, frameH)
            val dFused = fuseDistances(dPinhole, dPOV)
            val dFinal = dFused?.let { applyAngularCorrection(it, cx, frameW) }

            OrientationResult(
                label = det.label,
                confidence = det.score,
                position = pos,
                distance = distCat,
                box = det.box,
                distanceMeters = dFinal
            )
        }
    }

    fun selectBest(results: List<OrientationResult>): OrientationResult? {
        if (results.isEmpty()) return null
        val target = targetLabel?.lowercase()?.trim()
        val subset = if (!target.isNullOrEmpty()) {
            results.filter { it.label.lowercase().trim() == target }
        } else results
        return subset.maxByOrNull { it.confidence }
    }

    fun formatTts(result: OrientationResult): String {
        val nombre = result.label.ifBlank { "objeto" }
        val pos = when (result.position) {
            Position.IZQUIERDA -> "a la izquierda"
            Position.CENTRO_IZQUIERDA -> "al centro izquierda"
            Position.CENTRO -> "al centro"
            Position.CENTRO_DERECHA -> "al centro derecha"
            Position.DERECHA -> "a la derecha"
        }

        val distTxt = if (result.distanceMeters != null) {
            val d = result.distanceMeters
            when {
                d < 0.8f -> "a menos de un metro"
                d < 2.0f -> "a unos ${"%.1f".format(d)} metros"
                else -> "a aproximadamente ${d.roundToInt()} metros"
            }
        } else {
            when (result.distance) {
                Distance.CERCA -> "cerca"
                Distance.MEDIO -> "a media distancia"
                Distance.LEJOS -> "lejos"
            }
        }

        return "$nombre $pos, $distTxt."
    }
}
