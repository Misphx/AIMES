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

class OrientationEngine(
    private var focalLengthPx: Float = 1150f,
    private var knownObjectHeightMeters: Float? = null
) {
    var povK: Float = 520f
    var nearMeters: Float = 1.2f
    var midMeters:  Float = 2.5f
    var smoothingAlpha: Float = 0.35f
    var targetLabel: String? = null

    private var labelNames: List<String> = emptyList()
    fun setLabels(names: List<String>) { labelNames = names }

    private data class EmaState(var v: Float, var ready: Boolean)
    private val emaByKey = mutableMapOf<String, EmaState>()

    private val objIdxRegex = Regex("""^obj(\d+)$""")
    private fun resolveLabel(raw: String): String {
        val m = objIdxRegex.matchEntire(raw)
        if (m != null) {
            val idx = m.groupValues[1].toIntOrNull()
            if (idx != null && idx in labelNames.indices) return labelNames[idx]
        }
        return raw
    }
    private fun humanize(raw: String) = raw.replace('_', ' ')

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

    private fun metersToBucket(dMeters: Float?): Distance {
        if (dMeters == null || dMeters.isNaN() || dMeters.isInfinite()) return Distance.LEJOS
        return when {
            dMeters < nearMeters -> Distance.CERCA
            dMeters < midMeters  -> Distance.MEDIO
            else                 -> Distance.LEJOS
        }
    }

    private fun relBoxToBucket(hPx: Float, frameH: Int): Distance {
        val rel = if (frameH > 0) hPx / frameH.toFloat() else 0f
        return when {
            rel >= 0.45f -> Distance.CERCA
            rel >= 0.25f -> Distance.MEDIO
            else         -> Distance.LEJOS
        }
    }

    private fun estimateDistanceMetersByPinhole(boxHeightPixels: Float): Float? {
        val h = knownObjectHeightMeters ?: return null
        if (boxHeightPixels <= 0f) return null
        return (h * focalLengthPx) / boxHeightPixels
    }

    private fun applyAngularCorrection(rawD: Float?, boxCenterX: Float, frameW: Int): Float? {
        if (rawD == null) return null
        val offsetX = boxCenterX - (frameW / 2f)
        val theta = atan(offsetX / focalLengthPx)
        return rawD * cos(theta)
    }

    private fun estimateDistanceMetersByPOV(boxBottomY: Float, frameH: Int): Float? {
        val dy = (frameH - boxBottomY)
        if (dy <= 0f) return null
        return povK / dy
    }

    private fun ema(key: String, x: Float): Float {
        val s = emaByKey.getOrPut(key) { EmaState(x, false) }
        if (!s.ready) { s.v = x; s.ready = true; return s.v }
        s.v = smoothingAlpha * x + (1f - smoothingAlpha) * s.v
        return s.v
    }

    fun toResults(detections: List<Detection>, frameW: Int, frameH: Int): List<OrientationResult> {
        return detections.mapNotNull { det ->
            val labelResolved = resolveLabel(det.label)
            val labelHuman = humanize(labelResolved)

            val cx = det.box.centerX()
            val h  = det.box.height()
            val pos = mapPosition(cx, frameW)

            val dPinhole = estimateDistanceMetersByPinhole(h)
            val dPinholeCorr = applyAngularCorrection(dPinhole, cx, frameW)
            val dPOV = estimateDistanceMetersByPOV(det.box.bottom, frameH)
            val dMetersRaw = dPinholeCorr ?: dPOV

            val key = "${labelResolved}|${pos.name}"
            val dMeters = dMetersRaw?.let { ema(key, it) }

            val distCat = if (dMeters != null) metersToBucket(dMeters) else relBoxToBucket(h, frameH)

            OrientationResult(
                label = labelHuman,
                confidence = det.score,
                position = pos,
                distance = distCat,
                box = det.box,
                distanceMeters = dMeters
            )
        }
    }

    fun selectBest(results: List<OrientationResult>): OrientationResult? {
        if (results.isEmpty()) return null
        val target = targetLabel?.lowercase()?.trim()
        val subset = if (!target.isNullOrEmpty()) {
            results.filter { it.label.lowercase() == target }
                .ifEmpty { return null } // si hay objetivo definido, no devolvemos otros
        } else results

        return subset.minBy { distanceScore(it) }
    }

    private fun distanceScore(r: OrientationResult): Float {
        val bucket = when (r.distance) {
            Distance.CERCA -> 0f
            Distance.MEDIO -> 1f
            Distance.LEJOS -> 2f
        }
        val metersPart = r.distanceMeters ?: Float.POSITIVE_INFINITY
        return if (r.distanceMeters != null)
            metersPart - r.confidence * 0.01f
        else
            bucket - r.confidence * 0.01f
    }

    fun formatForSpeech(result: OrientationResult): String {
        val pos = when (result.position) {
            Position.IZQUIERDA -> "a la izquierda"
            Position.CENTRO_IZQUIERDA -> "a la izquierda"
            Position.CENTRO -> "al frente"
            Position.CENTRO_DERECHA -> "a la derecha"
            Position.DERECHA -> "a la derecha"
        }
        val distTxt = result.distanceMeters?.let { d ->
            when {
                d < 1.0f -> "muy cerca (${String.format("%.1f", d)} m)"
                d < 2.0f -> "cerca (${String.format("%.1f", d)} m)"
                d < 4.0f -> "a media distancia (${String.format("%.1f", d)} m)"
                else     -> "lejos (${String.format("%.0f", d)} m)"
            }
        } ?: when (result.distance) {
            Distance.CERCA -> "cerca"
            Distance.MEDIO -> "a media distancia"
            Distance.LEJOS -> "lejos"
        }
        return "${result.label} $pos, $distTxt."
    }
}