package com.example.aimesdes.vision

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

data class DetectedObject(
    val label: String,
    val score: Float,
    val box: RectF
)

data class VisionOutput(
    val objects: List<DetectedObject>,
    val text: String? = null
)

/**
 * VisionAnalyzer:
 * Analiza frames en tiempo real con YOLO11n.tflite.
 * Modular: puede incorporar OCR y voz más adelante.
 */
class VisionAnalyzer(
    private val context: Context,
    private val onResult: (VisionOutput) -> Unit
) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private val useLabels = false  // cambia a true si quieres usar labels.txt
    private val labels: List<String> by lazy {
        if (useLabels) {
            try { FileUtil.loadLabels(context, "labels.txt") }
            catch (e: Exception) { listOf("obj") }
        } else {
            List(80) { "cls_$it" } // genera nombres cls_0, cls_1, etc.
        }
    }

    private val inputSize = 640 // según export de Ultralytics
    private val threshold = 0.45f

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "object_detector.tflite")
            interpreter = Interpreter(model)
            Log.i("VisionAnalyzer", "✅ Modelo YOLO11n.tflite cargado correctamente.")
        } catch (e: Exception) {
            Log.e("VisionAnalyzer", "❌ Error cargando modelo: ${e.message}")
        }
    }

    override fun analyze(image: ImageProxy) {
        val bmp = BitmapUtils.imageToBitmap(image)
        val tensorImage = TensorImage.fromBitmap(bmp)

        val inputBuffer: ByteBuffer = tensorImage.buffer
        val outputBuffer =
            TensorBuffer.createFixedSize(intArrayOf(1, 8400, 85), DataType.FLOAT32)

        try {
            interpreter?.run(inputBuffer, outputBuffer.buffer.rewind())
        } catch (e: Exception) {
            Log.e("VisionAnalyzer", "❌ Error en inferencia: ${e.message}")
            image.close()
            return
        }

        val detections = parseDetections(outputBuffer.floatArray, image.width, image.height)
        onResult(VisionOutput(objects = detections))
        image.close()
    }

    private fun parseDetections(array: FloatArray, imgW: Int, imgH: Int): List<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        val numPred = array.size / 85 // 8400 predicciones
        for (i in 0 until numPred) {
            val offset = i * 85
            val x = array[offset]
            val y = array[offset + 1]
            val w = array[offset + 2]
            val h = array[offset + 3]
            val conf = array[offset + 4]
            if (conf < threshold) continue

            val scores = array.copyOfRange(offset + 5, offset + 85)
            val (maxCls, maxScore) = scores.withIndex().maxByOrNull { it.value } ?: continue
            val totalScore = conf * maxScore
            if (totalScore < threshold) continue

            val cx = x * imgW / inputSize
            val cy = y * imgH / inputSize
            val bw = w * imgW / inputSize
            val bh = h * imgH / inputSize

            val left = max(0f, cx - bw / 2)
            val top = max(0f, cy - bh / 2)
            val right = min(imgW.toFloat(), cx + bw / 2)
            val bottom = min(imgH.toFloat(), cy + bh / 2)

            results.add(
                DetectedObject(
                    label = labels.getOrElse(maxCls) { "obj" },
                    score = totalScore,
                    box = RectF(left, top, right, bottom)
                )
            )
        }
        return results.sortedByDescending { it.score }.take(10)
    }
}