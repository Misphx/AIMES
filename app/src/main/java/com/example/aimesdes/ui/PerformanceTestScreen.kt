package com.example.aimesdes.ui

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aimesdes.vision.Detection
import com.example.aimesdes.vision.VisionModule
import com.example.aimesdes.orientation.OrientationModule
import com.example.aimesdes.orientation.OrientationResult
import com.example.aimesdes.orientation.Position
import com.example.aimesdes.orientation.Distance
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.aimesdes.assistant.AsistenteViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceTestScreen(
    visionModule: VisionModule,
    asistenteViewModel: AsistenteViewModel,   // <<--- ViewModel para TTS
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        try {
            asistenteViewModel.setContinuousListening(false)
            asistenteViewModel.stopListening()
        } catch (_: Exception) {}
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Estado visión
    var isRunning by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(0f) }
    var precision by remember { mutableStateOf(0f) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }

    // PreviewView para CameraX
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Orientación
    val orientation = remember { OrientationModule(targetLabel = null) } // p.ej. "entrada"
    var orientResults by remember { mutableStateOf<List<OrientationResult>>(emptyList()) }
    var bestOrient by remember { mutableStateOf<OrientationResult?>(null) }

    var lastSpoken by remember { mutableStateOf("") }
    var lastSpeakTimeMs by remember { mutableStateOf(0L) }

    // Permiso de cámara
    val hasCameraPermInit = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.CAMERA
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var hasCameraPerm by remember { mutableStateOf(hasCameraPermInit) }

    val voice = asistenteViewModel.uiState.value
    val ctx = LocalContext.current

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) asistenteViewModel.startListening()
        else Toast.makeText(ctx, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
        if (granted) {
            val pv = previewRef
            if (pv != null) {
                visionModule.startCamera(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = pv
                ) { dets, newFps, newPrecision ->
                    detections = dets
                    fps = newFps
                    precision = newPrecision

                    val w = previewRef?.width ?: 0
                    val h = previewRef?.height ?: 0
                    orientResults = orientation.analyze(dets, w, h)
                    bestOrient = orientation.selectBest(orientResults)

                }
                isRunning = true
            }
        }
    }

    fun handleMicClick() {
        val hasMic = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            if (voice.isListening) asistenteViewModel.stopListening()
            else asistenteViewModel.startListening()
        }
    }

    // Al salir: apagar cámara
    DisposableEffect(Unit) {
        onDispose { visionModule.stopCamera() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prueba de rendimiento de visión") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Vista de cámara
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewRef = this
                    }
                }
            )

            // Overlay con cajas y labels orientados
            DetectionsOverlay(
                orientation = orientResults,
                modelInputSize = 640
            )

            // Panel inferior
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val guide = bestOrient?.let { orientation.formatTts(it) } ?: "Buscando objetivo…"
                Text(
                    text = guide,
                    color = Color.White,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "FPS: ${"%.2f".format(fps)}  |  Precisión: ${"%.1f".format(precision)}%",
                    color = Color.White,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón existente de cámara (lo dejas como está)
                    Button(
                        onClick = {
                            if (isRunning) {
                                visionModule.stopCamera()
                                isRunning = false
                            } else {
                                if (!hasCameraPerm) {
                                    cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
                                } else {
                                    val pv = previewRef
                                    if (pv != null) {
                                        visionModule.startCamera(
                                            context = context,
                                            lifecycleOwner = lifecycleOwner,
                                            previewView = pv
                                        ) { dets, newFps, newPrecision ->
                                            detections = dets
                                            fps = newFps
                                            precision = newPrecision
                                            // usa 640x640 para orientar en el marco del modelo
                                            orientResults = orientation.analyze(dets, 640, 640)
                                            bestOrient = orientation.selectBest(orientResults)
                                        }
                                        isRunning = true
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color.Red else Color(0xFF4CAF50)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isRunning) "Detener" else "Iniciar prueba",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // ⏺️ Botón de micrófono (nuevo)
                    FilledIconButton(onClick = { handleMicClick() }) {
                        Icon(
                            imageVector = if (voice.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (voice.isListening) "Detener micrófono" else "Activar micrófono"
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = if (voice.isListening) "Escuchando…" else "Mic apagado",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

    }
    // TTS: anunciar la mejor orientación sin spamear
    LaunchedEffect(bestOrient) {
        val best = bestOrient ?: return@LaunchedEffect
        if (best.confidence < 0.90f) return@LaunchedEffect  // umbral de confianza

        val msg = orientation.formatTts(best)
        val now = System.currentTimeMillis()
        val changed = msg != lastSpoken
        val enoughTime = now - lastSpeakTimeMs >= 4000      // 3s mínimo entre mensajes

        if (changed && enoughTime) {
            asistenteViewModel.say(msg)                     // <- usa el wrapper público
            lastSpoken = msg
            lastSpeakTimeMs = now
        }
    }


}

@Composable

private fun DetectionsOverlay(
    orientation: List<OrientationResult>,
    modelInputSize: Int = 640
) {
    val density = LocalDensity.current
    var overlayW by remember { mutableStateOf(0) }
    var overlayH by remember { mutableStateOf(0) }

    // FILL_CENTER → escalar por el mayor factor y centrar
    val scale = remember(overlayW, overlayH, modelInputSize) {
        if (modelInputSize == 0) 1f else maxOf(
            overlayW.toFloat() / modelInputSize,
            overlayH.toFloat() / modelInputSize
        )
    }
    val contentW = modelInputSize * scale
    val contentH = modelInputSize * scale
    val offsetX = (overlayW - contentW) / 2f
    val offsetY = (overlayH - contentH) / 2f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { s ->
                overlayW = s.width
                overlayH = s.height
            }
    ) {
        // Rectángulos
        Canvas(modifier = Modifier.fillMaxSize()) {
            orientation.forEach { res ->
                val left = offsetX + res.box.left * scale
                val top = offsetY + res.box.top * scale
                val w = res.box.width() * scale
                val h = res.box.height() * scale

                drawRect(
                    color = Color(0xFF8A2BE2),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 4f)
                )
            }
        }

        // Etiquetas orientadas
        orientation.forEach { res ->
            val lxDp = with(density) { (offsetX + res.box.left * scale + 8f).toDp() }
            val lyDp = with(density) { (offsetY + res.box.top * scale + 8f).toDp() }

            val posTxt = when (res.position) {
                Position.IZQUIERDA -> "Izq"
                Position.CENTRO_IZQUIERDA -> "C-Izq"
                Position.CENTRO -> "Centro"
                Position.CENTRO_DERECHA -> "C-Der"
                Position.DERECHA -> "Der"
            }
            val distTxt = when (res.distance) {
                Distance.CERCA -> "cerca"
                Distance.MEDIO -> "medio"
                Distance.LEJOS -> "lejos"
            }
            val text = "${res.label} ${(res.confidence * 100).toInt()}% • $posTxt • $distTxt"

            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .offset(x = lxDp, y = lyDp)
                    .background(Color(0x99000000), shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}