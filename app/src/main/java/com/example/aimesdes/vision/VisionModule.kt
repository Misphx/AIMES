package com.example.aimesdes.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import com.example.aimesdes.vision.BitmapUtils  // ✅ usa tu versión real

data class Detection(
    val box: RectF,
    val score: Float,
    val label: String = ""
)

class VisionModule {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var cameraProvider: ProcessCameraProvider? = null
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var fps = 0f
    private var precision = 0f

    private lateinit var cameraExecutor: ExecutorService

    private var onResults: ((List<Detection>, Float, Float) -> Unit)? = null

    /** Inicializa el modelo YOLO TFLite **/
    fun initializeModel(context: Context) {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "object_detector.tflite")
            interpreter = Interpreter(modelBuffer)
            try {
                labels = FileUtil.loadLabels(context, "labels.txt")
            } catch (e: Exception) {
                labels = emptyList()
                Log.w("AIMES", "No se encontró labels.txt — se usarán índices de clase")
            }
            Log.i("AIMES", "Modelo YOLO11n cargado correctamente")
        } catch (e: Exception) {
            Log.e("AIMES", "Error cargando modelo: ${e.message}")
        }
    }

    /** Overload seguro: requiere un LifecycleOwner explícito **/
    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        onResults: (List<Detection>, Float, Float) -> Unit
    ) {
        this.onResults = onResults
        initializeModel(context)

        // Asegura el ejecutor
        if (!::cameraExecutor.isInitialized || cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { previewUseCase ->
                previewView?.let { pv -> previewUseCase.setSurfaceProvider(pv.surfaceProvider) }
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase ->
                    analysisUseCase.setAnalyzer(cameraExecutor) { image ->
                        processFrame(image)
                    }
                }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    analysis
                )
                Log.i("AIMES", "Cámara iniciada correctamente")
            } catch (e: Exception) {
                Log.e("AIMES", "Error iniciando cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Overload compatible (usa Context como LifecycleOwner si puede) **/
    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        context: Context,
        previewView: PreviewView?,
        onResults: (List<Detection>, Float, Float) -> Unit
    ) {
        val owner = (context as? LifecycleOwner)
        if (owner == null) {
            Log.e("AIMES", "El context provisto no implementa LifecycleOwner. Usa el overload con LifecycleOwner.")
            return
        }
        startCamera(context, owner, previewView, onResults)
    }

    /** Procesa un frame con YOLO **/
    private fun processFrame(image: ImageProxy) {
        val detections = try {
            val bitmap = BitmapUtils.imageToBitmap(image)
            runInference(bitmap)
        } catch (e: Exception) {
            Log.e("AIMES", "Error procesando frame: ${e.message}")
            emptyList()
        } finally {
            image.close()
        }

        // FPS
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            fps = frameCount * 1000f / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
        }

        // Precisión promedio
        precision = if (detections.isNotEmpty()) {
            detections.map { it.score }.average().toFloat() * 100f
        } else 0f

        onResults?.invoke(detections, fps, precision)
    }

    /** Ejecuta inferencia TFLite **/
    private fun runInference(bitmap: android.graphics.Bitmap): List<Detection> {
        val inputSize = 640
        val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = pixels[idx++]
                inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255f))
                inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255f))
                inputBuffer.putFloat(((pixel and 0xFF) / 255f))
            }
        }

        val outputBuffer = Array(1) { Array(8400) { FloatArray(85) } }

        val elapsed = measureTimeMillis {
            interpreter?.run(inputBuffer, outputBuffer)
        }
        Log.d("AIMES", "Inferencia YOLO11n ejecutada en ${elapsed}ms")

        val detections = mutableListOf<Detection>()
        val threshold = 0.4f

        outputBuffer[0].forEach { pred ->
            val scores = pred.sliceArray(4 until pred.size)
            val classId = scores.indices.maxByOrNull { scores[it] } ?: -1
            val conf = if (classId >= 0) scores[classId] * pred[4] else 0f
            if (conf > threshold) {
                val cx = pred[0]
                val cy = pred[1]
                val w = pred[2]
                val h = pred[3]
                val left = (cx - w / 2f) * inputSize
                val top = (cy - h / 2f) * inputSize
                val right = (cx + w / 2f) * inputSize
                val bottom = (cy + h / 2f) * inputSize
                val label = if (labels.isNotEmpty() && classId in labels.indices) labels[classId] else "obj$classId"
                detections.add(Detection(RectF(left, top, right, bottom), conf, label))
            }
        }

        return detections
    }

    /** Detiene la cámara y libera recursos **/
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()

            if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                cameraExecutor.shutdown()
                cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
            }

            interpreter?.close()
            interpreter = null

            Log.i("AIMES", "Cámara detenida y recursos liberados correctamente")
        } catch (e: Exception) {
            Log.e("AIMES", "Error al detener cámara: ${e.message}")
        }
    }
}