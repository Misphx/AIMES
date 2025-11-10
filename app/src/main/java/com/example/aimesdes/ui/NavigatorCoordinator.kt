package com.example.aimesdes.coordinator

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.aimesdes.assistant.AsistenteViewModel
import com.example.aimesdes.navigation.DireccionMetro
import com.example.aimesdes.ocr.OcrEngine
import com.example.aimesdes.orientation.OrientationModule
import com.example.aimesdes.vision.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NavigatorCoordinator(
    private val asistenteVM: AsistenteViewModel,
    private val visionModule: VisionModule,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val orientation = OrientationModule()
    private var job: Job? = null
    private var lastSpoken = ""
    private var lastTime = 0L

    fun start(context: android.content.Context, previewSink: androidx.camera.view.PreviewView? = null) {
        // Si quieres correr cámara “headless”, no necesitas preview; CameraX lo permite.
        visionModule.startCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewSink // puede ser null si tu VisionModule lo soporta
        ) { dets, fps, precision, frame640 ->
            lifecycleOwner.lifecycleScope.launch {
                process(dets, frame640)
            }
        }
    }

    fun stop() {
        job?.cancel()
        visionModule.stopCamera()
    }

    private suspend fun process(dets: List<Detection>, frame: Bitmap?) {
        // 1) Solo actuamos si el usuario pidió un destino por voz
        val destino = asistenteVM.uiState.value.estacionDestino ?: return

        // 2) Opcional: fijar label objetivo en orientación (por si lo usas)
        val results = orientation.analyze(dets, 640, 640)
        val best = orientation.selectBest(results)
        // (No hablamos orientación aquí para no chocar con TTS de comandos)

        // 3) Filtramos posibles señales
        if (frame == null) return
        val señales = dets
            .filter { it.label.lowercase() in listOf("senal","señal","sign","cartel","indicador") }
            .sortedByDescending { it.box.width() * it.box.height() }
            .take(3)

        if (señales.isEmpty()) return

        var estacionDetectada: String? = null
        for (c in señales) {
            val crop = safeCrop(frame, c.box) ?: continue
            val txt = OcrEngine.readText(crop).lowercase().trim()
            if (txt.isBlank()) continue

            // ¿encuentro una estación conocida en el texto?
            val match = DireccionMetroStationMatcher.findStationInText(txt)
            if (match != null) {
                estacionDetectada = match
                break
            }
        }

        val now = System.currentTimeMillis()
        if (estacionDetectada != null) {
            val origen = asistenteVM.uiState.value.estacionActual ?: estacionDetectada
            val dir = DireccionMetro.decidirDireccion(
                mapOf("estacion_actual" to origen, "estacion_destino" to destino)
            )
            val msg = "Señal hacia $estacionDetectada. $dir"
            if (msg != lastSpoken && now - lastTime > 2500) {
                asistenteVM.say(msg)
                lastSpoken = msg
                lastTime = now
            }
        }
    }

    private fun safeCrop(frame: Bitmap, box: RectF): Bitmap? {
        val l = box.left.coerceAtLeast(0f).toInt()
        val t = box.top.coerceAtLeast(0f).toInt()
        val r = box.right.coerceAtMost(frame.width.toFloat()).toInt()
        val b = box.bottom.coerceAtMost(frame.height.toFloat()).toInt()
        if (l >= r || t >= b) return null
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        return try { Bitmap.createBitmap(frame, l, t, w, h) } catch (_: Exception) { null }
    }
}
