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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aimesdes.vision.Detection
import com.example.aimesdes.vision.VisionModule
import androidx.compose.ui.layout.onSizeChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceTestScreen(
    visionModule: VisionModule,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isRunning by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(0f) }
    var precision by remember { mutableStateOf(0f) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }

    // Mantener referencia al PreviewView para controlarlo fuera del factory
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Al salir de la pantalla: detener cámara y liberar
    DisposableEffect(Unit) {
        onDispose {
            visionModule.stopCamera()
        }
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
            // Vista de cámara (solo crea la vista; no inicia la cámara aquí)
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

            // Overlay de detecciones
            DetectionsOverlay(detections = detections)

            // Panel de control
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                                }
                                isRunning = true
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
    detections: List<Detection>,
    modelInputSize: Int = 640 // debe coincidir con VisionModule.runInference
) {
    val density = LocalDensity.current
    var overlayW by remember { mutableStateOf(0) } // px
    var overlayH by remember { mutableStateOf(0) } // px

    // Escalas dinámicas (px del modelo -> px del overlay)
    val sx = remember(overlayW, modelInputSize) {
        if (modelInputSize != 0) overlayW.toFloat() / modelInputSize else 1f
    }
    val sy = remember(overlayH, modelInputSize) {
        if (modelInputSize != 0) overlayH.toFloat() / modelInputSize else 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { intSize ->           // <- IntSize
                overlayW = intSize.width          // <- propiedades, sin "()"
                overlayH = intSize.height
            }
    ) {
        // 1) Rectángulos escalados correctamente
        Canvas(modifier = Modifier.fillMaxSize()) {
            detections.forEach { det ->
                val left = det.box.left * sx
                val top = det.box.top * sy
                val w = det.box.width() * sx
                val h = det.box.height() * sy

                drawRect(
                    color = Color(0xFF8A2BE2),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 4f)
                )
            }
        }

        // 2) Etiquetas perfectamente posicionadas (px -> dp)
        detections.forEach { det ->
            val lxDp = with(density) { (det.box.left * sx + 8f).toDp() }
            val lyDp = with(density) { (det.box.top * sy + 8f).toDp() }

            Text(
                text = "${det.label} ${(det.score * 100).toInt()}%",
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