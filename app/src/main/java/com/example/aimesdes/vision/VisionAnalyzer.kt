package com.example.aimesdes.vision

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

data class DetectedObject(
    val label: String,
    val score: Float,
    val box: RectF // coordenadas en espacio del modelo (0..640)
)

data class VisionOutput(
    val objects: List<DetectedObject>,
    val text: String? = null
)

class VisionAnalyzer(
    private val context: Context,
    private val onResult: (VisionOutput) -> Unit
) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private val labels: List<String> by lazy {
        try { FileUtil.loadLabels(context, "labels.txt") } catch (_: Exception) { emptyList() }
    }
    private val inputSize = 640
    private val threshold = 0.45f
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "object_detector.tflite")
            interpreter = Interpreter(model)
            Log.i("VisionAnalyzer", "Modelo YOLO11n.tflite cargado correctamente.")
        } catch (e: Exception) {
            Log.e("VisionAnalyzer", "Error cargando modelo: ${e.message}")
        }
    }

    override fun analyze(image: ImageProxy) {
        val bmp = try { BitmapUtils.imageToBitmap(image) } catch (e: Exception) {
            Log.e("VisionAnalyzer", "Bitmap conversion error: ${e.message}")
            image.close(); return
        }

        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, inputSize, inputSize, true)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val p = pixels[idx++]
                inputBuffer.putFloat(((p ushr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((p ushr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((p and 0xFF) / 255f)
            }
        }

        val output = Array(1) { Array(8400) { FloatArray(85) } }

        try {
            val t = measureTimeMillis { interpreter?.run(inputBuffer, output) }
            Log.d("VisionAnalyzer", "Inferencia en ${t}ms")
        } catch (e: Exception) {
            Log.e("VisionAnalyzer", "Error en inferencia: ${e.message}")
            image.close(); return
        } finally {
            image.close()
        }

        val dets = mutableListOf<DetectedObject>()
        for (i in 0 until 8400) {
            val pred = output[0][i]
            val scores = pred.sliceArray(5 until pred.size)
            val classId = scores.indices.maxByOrNull { scores[it] } ?: -1
            val score = if (classId >= 0) scores[classId] * pred[4] else 0f
            if (score < threshold) continue

            val cx = pred[0]; val cy = pred[1]; val w = pred[2]; val h = pred[3]
            val left = (cx - w / 2f) * inputSize
            val top = (cy - h / 2f) * inputSize
            val right = (cx + w / 2f) * inputSize
            val bottom = (cy + h / 2f) * inputSize
            val label = if (labels.isNotEmpty() && classId in labels.indices) labels[classId] else "obj$classId"

            dets.add(DetectedObject(label, score, RectF(left, top, right, bottom)))
        }

        // Publicar en hilo principal
        mainHandler.post { onResult(VisionOutput(objects = dets.sortedByDescending { it.score }.take(20))) }
    }
}