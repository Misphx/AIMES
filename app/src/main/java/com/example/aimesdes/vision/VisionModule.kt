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
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class Detection(
    val box: RectF,
    val score: Float,
    val label: String = ""
)

class VisionModule {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var cameraProvider: ProcessCameraProvider? = null

    // Métricas
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var fps = 0f
    private var precision = 0f

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mainExecutor: Executor

    private var onResults: ((List<Detection>, Float, Float) -> Unit)? = null

    /** Inicializa el modelo YOLO TFLite **/
    private fun initializeModel(context: Context) {
        if (interpreter != null) return
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "object_detector.tflite")
            interpreter = Interpreter(modelBuffer)
            labels = try { FileUtil.loadLabels(context, "labels.txt") }
            catch (_: Exception) { emptyList() }
            Log.i("AIMES", "Modelo YOLO11n cargado correctamente")
        } catch (e: Exception) {
            Log.e("AIMES", "Error cargando modelo: ${e.message}")
        }
    }

    /** Overload seguro **/
    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        onResults: (List<Detection>, Float, Float) -> Unit
    ) {
        this.onResults = onResults
        initializeModel(context)

        // Ejecutores
        if (!::cameraExecutor.isInitialized || cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        mainExecutor = ContextCompat.getMainExecutor(context)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { p ->
                previewView?.let { pv -> p.setSurfaceProvider(pv.surfaceProvider) }
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(cameraExecutor) { image ->
                        processFrame(image)
                    }
                }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                Log.i("AIMES", "Cámara iniciada correctamente")
            } catch (e: Exception) {
                Log.e("AIMES", "Error iniciando cámara: ${e.message}")
            }
        }, mainExecutor)
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

        if (::mainExecutor.isInitialized) {
            mainExecutor.execute { onResults?.invoke(detections, fps, precision) }
        } else {
            onResults?.invoke(detections, fps, precision)
        }
    }

    /** Ejecuta inferencia TFLite: devuelve cajas en espacio 640x640 **/
    private fun runInference(bitmap: android.graphics.Bitmap): List<Detection> {
        val tfl = interpreter ?: run {
            Log.e("AIMES", "Interpreter es null. ¿Se cargó el modelo?")
            return emptyList()
        }

        val inputSize = 640
        val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inType = tfl.getInputTensor(0).dataType().toString() // FLOAT32 o UINT8
        val inputBuffer: ByteBuffer = when (inType) {
            "FLOAT32" -> {
                val buf = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
                val px = IntArray(inputSize * inputSize)
                resized.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
                var i = 0
                for (y in 0 until inputSize) for (x in 0 until inputSize) {
                    val p = px[i++]
                    buf.putFloat(((p ushr 16) and 0xFF) / 255f)
                    buf.putFloat(((p ushr 8) and 0xFF) / 255f)
                    buf.putFloat((p and 0xFF) / 255f)
                }
                buf.rewind(); buf
            }
            "UINT8" -> {
                val buf = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
                val px = IntArray(inputSize * inputSize)
                resized.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
                var i = 0
                for (y in 0 until inputSize) for (x in 0 until inputSize) {
                    val p = px[i++]
                    buf.put(((p ushr 16) and 0xFF).toByte())
                    buf.put(((p ushr 8) and 0xFF).toByte())
                    buf.put((p and 0xFF).toByte())
                }
                buf.rewind(); buf
            }
            else -> {
                Log.e("AIMES", "Tipo de entrada no soportado: $inType")
                return emptyList()
            }
        }

        val outTensor = tfl.getOutputTensor(0)
        val outShape = outTensor.shape()
        val outType = outTensor.dataType().toString()
        val outBytes = outTensor.numBytes()

        val outBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        val inputs = arrayOf<Any>(inputBuffer)
        val outputs = hashMapOf<Int, Any>(0 to outBuffer)

        val elapsed = kotlin.system.measureTimeMillis {
            tfl.runForMultipleInputsOutputs(inputs, outputs)
        }
        Log.d("AIMES", "Inferencia shape=${outShape.contentToString()} en ${elapsed}ms; outType=$outType")

        outBuffer.rewind()
        val floats = FloatArray(outBytes / 4)
        outBuffer.asFloatBuffer().get(floats)

        val b = outShape.getOrNull(0) ?: 1
        if (b != 1 || outShape.size != 3) {
            Log.e("AIMES", "Layout de salida no soportado: ${outShape.contentToString()}")
            return emptyList()
        }
        val a = outShape[1] // puede ser canales o boxes
        val n = outShape[2] // puede ser boxes o canales

        val channelsFirst = (a in listOf(17, 84, 85)) && (n in listOf(8400, 25200))
        val boxesFirst    = (a in listOf(8400, 25200)) && (n in listOf(17, 84, 85))

        val results = mutableListOf<Detection>()
        val threshold = 0.30f

        fun parseVector(vec: FloatArray, classStart: Int, hasObj: Boolean) {
            if (vec.size <= classStart) return
            val classScores = vec.copyOfRange(classStart, vec.size)
            val classId = classScores.indices.maxByOrNull { classScores[it] } ?: -1
            val obj = if (hasObj) vec[4] else 1f
            val conf = if (classId >= 0) classScores[classId] * obj else 0f
            if (conf > threshold) {
                val cx = vec[0]; val cy = vec[1]; val w = vec[2]; val h = vec[3]
                val left = (cx - w / 2f) * inputSize
                val top = (cy - h / 2f) * inputSize
                val right = (cx + w / 2f) * inputSize
                val bottom = (cy + h / 2f) * inputSize
                val label = if (labels.isNotEmpty() && classId in labels.indices) labels[classId] else "obj$classId"
                results.add(Detection(RectF(left, top, right, bottom), conf, label))
            }
        }

        when {
            channelsFirst -> {
                val C = a; val K = n
                // Elegimos el que da mayor confianza.
                for (k in 0 until K) {
                    // Construir vector de canales para la caja k
                    val vec = FloatArray(C)
                    var base = k
                    var c = 0
                    while (c < C) {
                        vec[c] = floats[base]
                        base += K
                        c++
                    }

                    // Requiere al menos 4 coords
                    if (C < 5) continue

                    // Caso A: sin 'obj'
                    val classStartNoObj = 4
                    val classesNoObj = (C - classStartNoObj).coerceAtLeast(0)
                    var bestIdNoObj = -1
                    var bestScoreNoObj = 0f
                    if (classesNoObj > 0) {
                        for (i in 0 until classesNoObj) {
                            val s = vec[classStartNoObj + i]
                            if (s > bestScoreNoObj) { bestScoreNoObj = s; bestIdNoObj = i }
                        }
                    }

                    // Caso B: con 'obj' (índice 4)
                    val hasObjPossible = C >= 6 // al menos 4 coords + obj + 1 clase
                    var bestIdWithObj = -1
                    var bestScoreWithObj = 0f
                    var objVal = 1f
                    if (hasObjPossible) {
                        objVal = vec[4]
                        val classStartWithObj = 5
                        val classesWithObj = (C - classStartWithObj).coerceAtLeast(0)
                        var bestCls = -1
                        var bestClsScore = 0f
                        for (i in 0 until classesWithObj) {
                            val s = vec[classStartWithObj + i]
                            if (s > bestClsScore) { bestClsScore = s; bestCls = i }
                        }
                        bestIdWithObj = bestCls
                        bestScoreWithObj = bestClsScore * objVal
                    }

                    // Elegir el esquema con mayor confianza
                    val useWithObj = hasObjPossible && bestScoreWithObj >= bestScoreNoObj
                    val conf = if (useWithObj) bestScoreWithObj else bestScoreNoObj
                    val classId = if (useWithObj) bestIdWithObj else bestIdNoObj

                    if (classId >= 0 && conf > threshold) {
                        val cx = vec[0]; val cy = vec[1]; val w = vec[2]; val h = vec[3]
                        val left = (cx - w / 2f) * inputSize
                        val top = (cy - h / 2f) * inputSize
                        val right = (cx + w / 2f) * inputSize
                        val bottom = (cy + h / 2f) * inputSize
                        val label = if (labels.isNotEmpty() && classId in labels.indices) labels[classId] else "obj$classId"
                        results.add(Detection(RectF(left, top, right, bottom), conf, label))
                    }
                }
            }
            boxesFirst -> {
                val K = a; val C = n
                val hasObj = when {
                    labels.isNotEmpty() && C - 5 == labels.size -> true
                    labels.isNotEmpty() && C - 4 == labels.size -> false
                    else -> (C - 5) >= 1
                }
                val classStart = if (hasObj) 5 else 4

                var k = 0
                while (k < K) {
                    val base = k * C
                    val vec = floats.copyOfRange(base, base + C)
                    parseVector(vec, classStart, hasObj)
                    k++
                }
            }
            else -> {
                Log.e("AIMES", "Layout no reconocido (a=$a, n=$n) en ${outShape.contentToString()}")
            }
        }

        val finalDetections = results.sortedByDescending { it.score }.take(50)
        Log.i("AIMES", "Detecciones=${finalDetections.size} (umbral=$threshold)")
        return finalDetections
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