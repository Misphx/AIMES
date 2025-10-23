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
import com.example.aimesdes.orientation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat

// importa tu ViewModel (está declarado en el mismo package com.example.aimesdes)
import com.example.aimesdes.AsistenteViewModel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceTestScreen(
    visionModule: VisionModule,
    asistenteViewModel: AsistenteViewModel,   // <<--- ViewModel para TTS
    modifier: Modifier = Modifier,
    onStopped: () -> Unit
) {
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
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val labels = context.assets.open("labels.txt")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            orientation.setLabels(labels)
        } catch (e: Exception) {
            // si falla, seguirá usando los labels tal como vienen (objN o nombre)
        }
    }
    var orientResults by remember { mutableStateOf<List<OrientationResult>>(emptyList()) }
    var bestOrient by remember { mutableStateOf<OrientationResult?>(null) }

    // Permiso de cámara
    val hasCameraPermInit = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.CAMERA
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var hasCameraPerm by remember { mutableStateOf(hasCameraPermInit) }

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

                    // TTS inmediato con anti-flood en el ViewModel
                    bestOrient?.let { asistenteViewModel.speakOrientation(it) }
                }
                isRunning = true
            }
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

                Button(
                    onClick = {
                        if (isRunning) {
                            visionModule.stopCamera()
                            isRunning = false
                            onStopped()
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

                                        val w = previewRef?.width ?: 0
                                        val h = previewRef?.height ?: 0
                                        orientResults = orientation.analyze(dets, w, h)
                                        bestOrient = orientation.selectBest(orientResults)

                                        // TTS en botón start
                                        bestOrient?.let { asistenteViewModel.speakOrientation(it) }
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
            }
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
            // --- Cálculo de escalas y offsets ---
            val overlayW = size.width
            val overlayH = size.height

            // Punto de referencia: centro inferior de la cámara
            val refPoint = Offset(overlayW / 2f, overlayH)

            // --- Detecciones ---
            orientation.forEach { res ->
                val left = offsetX + res.box.left * scale
                val top = offsetY + res.box.top * scale
                val w = res.box.width() * scale
                val h = res.box.height() * scale

                // Centro del bounding box detectado
                val objCenter = Offset(left + w / 2f, top + h / 2f)

                // Dibujar rectángulo del objeto
                drawRect(
                    color = Color(0xFF8A2BE2),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 4f)
                )

                // --- Línea desde punto referencial hasta el objeto ---
                drawLine(
                    color = Color.Cyan,
                    start = refPoint,
                    end = objCenter,
                    strokeWidth = 3f
                )

                // --- Texto con distancia estimada ---
                val distanceText = if (res.distanceMeters != null)
                    "${"%.1f".format(res.distanceMeters)} m"
                else
                    res.distance.name.lowercase()

                drawContext.canvas.nativeCanvas.drawText(
                    distanceText,
                    objCenter.x,
                    objCenter.y - h / 2f - 10f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        textSize = 36f
                        isFakeBoldText = true
                    }
                )
            }

            // --- Punto de referencia (marcador visual) ---
            drawCircle(
                color = Color.Red,
                radius = 10f,
                center = refPoint
            )
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