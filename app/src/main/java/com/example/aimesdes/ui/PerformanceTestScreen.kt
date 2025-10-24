package com.example.aimesdes.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.aimesdes.orientation.Distance
import com.example.aimesdes.orientation.OrientationEngine
import com.example.aimesdes.orientation.OrientationResult
import com.example.aimesdes.orientation.Position
import com.example.aimesdes.vision.Detection
import com.example.aimesdes.vision.VisionModule
import com.example.aimesdes.AsistenteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceTestScreen(
    asistenteViewModel: AsistenteViewModel,
    visionModule: VisionModule,
    lifecycleOwner: LifecycleOwner
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var previewRef by remember { mutableStateOf<PreviewView?>(null) }

    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var orientResults by remember { mutableStateOf<List<OrientationResult>>(emptyList()) }
    var bestOrient by remember { mutableStateOf<OrientationResult?>(null) }
    var fps by remember { mutableStateOf(0f) }
    var precision by remember { mutableStateOf(0f) }

    val orientationEngine = remember { OrientationEngine() }

    LaunchedEffect(Unit) {
        try {
            val labels = context.assets.open("labels.txt")
                .bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            orientationEngine.setLabels(labels)
        } catch (_: Exception) {}
    }

    // --- Micrófono propio del Test (independiente del de Main) ---
    val testSrRef = remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun startTestMic() {
        val sr = testSrRef.value ?: SpeechRecognizer.createSpeechRecognizer(context).also { testSrRef.value = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { asistenteViewModel.procesarComando(it) }
                try { sr.startListening(intent) } catch (_: Exception) {}
            }
            override fun onPartialResults(results: Bundle) {}
            override fun onError(error: Int) { try { sr.startListening(intent) } catch (_: Exception) {} }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { sr.startListening(intent) } catch (_: Exception) {}
    }

    fun stopTestMic() {
        try { testSrRef.value?.stopListening() } catch (_: Exception) {}
    }

    fun destroyTestMic() {
        try { testSrRef.value?.stopListening() } catch (_: Exception) {}
        try { testSrRef.value?.destroy() } catch (_: Exception) {}
        testSrRef.value = null
    }

    DisposableEffect(Unit) {
        onDispose {
            try { stopTestMic() } catch (_: Exception) {}
            try { destroyTestMic() } catch (_: Exception) {}
            try { visionModule.stopCamera() } catch (_: Exception) {}
        }
    }

    // --- Controles UI ---
    var voiceOn by remember { mutableStateOf(true) }
    var targetText by remember { mutableStateOf("") }
    var povK by remember { mutableStateOf(520f) }
    var nearM by remember { mutableStateOf(1.2f) }
    var midM by remember { mutableStateOf(2.5f) }

    LaunchedEffect(voiceOn) { asistenteViewModel.setVoiceGuidance(voiceOn) }
    LaunchedEffect(targetText) {
        val t = targetText.lowercase().trim().ifBlank { null }
        asistenteViewModel.setTargetLabel(t)
        orientationEngine.targetLabel = t
    }
    LaunchedEffect(povK, nearM, midM) {
        orientationEngine.povK = povK
        orientationEngine.nearMeters = nearM
        orientationEngine.midMeters = midM
    }

    var running by remember { mutableStateOf(false) }

    fun startCamera() {
        val pv = previewRef ?: return
        startTestMic()

        visionModule.startCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = pv
        ) { dets, newFps, newPrecision ->
            detections = dets
            fps = newFps
            precision = newPrecision

            val w = pv.width
            val h = pv.height

            val orientList = orientationEngine.toResults(dets, w, h)
            val best = orientationEngine.selectBest(orientList)

            orientResults = orientList
            bestOrient = best

            if (voiceOn) {
                best?.let { asistenteViewModel.speakOrientation(it) }
            }
        }
        running = true
    }

    fun stopCamera() {
        stopTestMic()
        try { visionModule.stopCamera() } catch (_: Exception) {}
        running = false
    }

    // ===== UI =====
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Prueba de Rendimiento / Orientación",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            AssistStats(fps = fps, precision = precision)
        }

        Spacer(Modifier.height(8.dp))

        // Preview + overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),
                factory = {
                    PreviewView(it).apply {
                        this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewRef = this
                    }
                }
            )

            // Overlay de cajas y etiquetas orientadas
            DetectionOverlay(
                detections = detections,
                orientation = orientResults,
                best = bestOrient
            )
        }

        Spacer(Modifier.height(10.dp))

        // Panel de controles
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
        ) {
            Column(Modifier.padding(12.dp)) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = voiceOn, onCheckedChange = { voiceOn = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Guía por voz", color = Color.White)
                    }

                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Objetivo (ej: door, elevator)", color = Color.LightGray) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0x33000000),
                            unfocusedContainerColor = Color(0x22000000),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    if (!running) {
                        Button(onClick = { startCamera() }) { Text("Iniciar") }
                    } else {
                        Button(onClick = { stopCamera() }) { Text("Detener") }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Column(Modifier.fillMaxWidth()) {
                    Text("Calibración distancia (POV K = ${povK.toInt()})", color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = povK,
                        onValueChange = { povK = it },
                        valueRange = 120f..2000f
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Cerca < ${"%.1f".format(nearM)} m", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = nearM,
                                onValueChange = { v -> nearM = v.coerceIn(0.5f, 3.0f) },
                                valueRange = 0.5f..3.0f
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Medio < ${"%.1f".format(midM)} m", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = midM,
                                onValueChange = { v -> midM = v.coerceIn(nearM + 0.2f, 5.0f) },
                                valueRange = 1.0f..5.0f
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistStats(fps: Float, precision: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatChip(label = "FPS", value = if (fps.isFinite()) "%.1f".format(fps) else "-")
        StatChip(label = "Precisión", value = if (precision.isFinite()) "%.1f%%".format(precision * 100) else "-")
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = Color(0x2222AAFF),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("$label:", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    orientation: List<OrientationResult>,
    best: OrientationResult?
) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            detections.forEach { det ->
                val l = det.box.left * w
                val t = det.box.top * h
                val r = det.box.right * w
                val b = det.box.bottom * h
                val rectColor = if (best != null && det.box == best.box) Color(0xFF00E676) else Color(0x66FFFFFF)
                drawRect(
                    color = rectColor,
                    topLeft = Offset(l, t),
                    size = Size(width = (r - l), height = (b - t)),
                    style = Stroke(width = 3f)
                )
            }
        }

        // Etiquetas orientadas (texto)
        orientation.forEach { res ->
            val posTxt = when (res.position) {
                Position.IZQUIERDA -> "Izq"
                Position.CENTRO_IZQUIERDA -> "C-Izq"
                Position.CENTRO -> "Centro"
                Position.CENTRO_DERECHA -> "C-Der"
                Position.DERECHA -> "Der"
            }
            val distTxt = res.distanceMeters?.let { m ->
                when {
                    m < 1.0f -> "muy cerca (${String.format("%.1f", m)}m)"
                    m < 2.0f -> "cerca (${String.format("%.1f", m)}m)"
                    m < 4.0f -> "medio (${String.format("%.1f", m)}m)"
                    else -> "lejos (${String.format("%.0f", m)}m)"
                }
            } ?: when (res.distance) {
                Distance.CERCA -> "cerca"
                Distance.MEDIO -> "medio"
                Distance.LEJOS -> "lejos"
            }

            val text = "${res.label} ${(res.confidence * 100).toInt()}% • $posTxt • $distTxt"

            Box(
                modifier = Modifier
                    .offset(
                        x = (res.box.left * 16f).dp,
                        y = (res.box.top * 16f).dp
                    )
                    .background(Color(0x99000000), shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(text = text, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}