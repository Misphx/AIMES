package com.example.aimesdes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aimesdes.ui.PerformanceTestScreen
import com.example.aimesdes.vision.VisionModule
import com.example.aimesdes.AsistenteViewModel.*
import java.util.Locale
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.foundation.text.KeyboardOptions

/* ------------------- Datos comandos / estado UI ------------------- */

data class Comando(
    val entrada: String,
    val intencion: String,
    val objeto: String? = null,
    val modo: String? = null,
    val estacion: String? = null
)

data class ComandosWrapper(val comandos: List<Comando>)

/* ------------------- Main Activity con navegación ------------------- */

class MainActivity : ComponentActivity() {
    private val asistenteViewModel: AsistenteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val visionModule = remember { VisionModule() }
            val nav = rememberNavController()

            // --- Permisos (mic + cámara) ---
            var hasMicPerm by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasCamPerm by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val multiPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val mic = result[Manifest.permission.RECORD_AUDIO] == true
                val cam = result[Manifest.permission.CAMERA] == true
                hasMicPerm = mic
                hasCamPerm = cam
                if (!mic) Toast.makeText(context, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
                if (!cam) Toast.makeText(context, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
            }

            // Efecto de navegación disparado por el VM (cuando entiende "abrir cámara", etc.)
            val navToCam by asistenteViewModel.navigateToCamera
            LaunchedEffect(navToCam) {
                if (navToCam) {
                    // Apaga mic principal antes de entrar al Test (evita conflictos)
                    asistenteViewModel.stopListening()
                    asistenteViewModel.setVisionMode(true)
                    asistenteViewModel.navigateToCamera.value = false

                    if (!hasCamPerm) {
                        multiPermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    } else {
                        nav.navigate("performance_test")
                    }
                }
            }

            MaterialTheme {
                NavHost(navController = nav, startDestination = "home") {

                    composable("home") {
                        HomeScreen(
                            onAlert = { nav.navigate("alert") },
                            onSettings = { nav.navigate("settings") },
                            onHelp = { nav.navigate("help") },
                            onMicRequested = {
                                if (!hasMicPerm) {
                                    multiPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                } else {
                                    asistenteViewModel.startListening()
                                }
                            },
                            onCamera = {
                                // Detén el mic principal ANTES de entrar al test
                                asistenteViewModel.stopListening()
                                asistenteViewModel.setVisionMode(true)
                                if (!hasCamPerm) {
                                    multiPermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                } else {
                                    nav.navigate("performance_test")
                                }
                            },
                            asistenteViewModel = asistenteViewModel,
                            hasMicPerm = hasMicPerm
                        )
                    }

                    // ALERTA (usa tu AlertScreen real)
                    composable("alert") {
                        AlertScreen(
                            onBack = { nav.popBackStack() },
                            phone = "56912345678",
                            message = "Necesito ayuda.",
                            option = "opt2",
                            onDialContact = { /* dial a contacto */ },
                            onDialServices = { /* dial 133 */ },
                            onSendSms = { /* sms(phone, message) */ },
                            onQuickSms = { quick -> /* sms(phone, quick) */ }
                        )
                    }

                    // CONFIGURACIÓN (usa tu SettingsScreen real)
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            initialPhone = "56912345678",
                            initialMessage = "Necesito ayuda.",
                            initialOption = "opt2",
                            initialLargeText = true,
                            initialTts = true,
                            initialVibration = true,
                            onSave = { _, _, _, _, _, _ -> },
                            onTestDial = { /* dial 133 */ },
                            onTestSms = { _, _ -> /* sms(...) */ }
                        )
                    }

                    //  AYUDA (usa tu HelpScreen real)
                    composable("help") {
                        HelpScreen(onBack = { nav.popBackStack() })
                    }

                    // CÁMARA / TEST
                    composable("performance_test") {
                        PerformanceTestScreen(
                            visionModule = visionModule,
                            asistenteViewModel = asistenteViewModel,
                            lifecycleOwner = this@MainActivity
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Si la app pierde foco, apaga el mic principal para evitar bugeos
        asistenteViewModel.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpieza final
        asistenteViewModel.stopListening()
    }
}

/* ------------------- UI Home + componentes ------------------- */

@Composable
fun HomeScreen(
    onAlert: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onMicRequested: () -> Unit,
    onCamera: () -> Unit,
    asistenteViewModel: AsistenteViewModel,
    hasMicPerm: Boolean
) {
    val bg = Color(0xFF0B0B0B)
    val micYellow = Color(0xFFFFD54F)
    val camWhite = Color.White
    val pillRed = Color(0xFFC62828)
    val context = LocalContext.current

    val ui by asistenteViewModel.uiState

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(20.dp)
    ) {
        val minSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val circleSize = minSide * 0.5f

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AIMES", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(30.dp))

            PillButton("⚠ Alerta", pillRed, Modifier
                .fillMaxWidth()
                .height(90.dp)) { onAlert() }
            Spacer(Modifier.height(40.dp))

            // Mic principal (Home)
            CircleAction(
                size = circleSize,
                color = micYellow,
                icon = Icons.Filled.Mic,
                label = "Micrófono",
                labelColor = Color.Black
            ) {
                if (hasMicPerm) onMicRequested()
                else Toast.makeText(context, "Otorga permiso de micrófono", Toast.LENGTH_SHORT).show()
            }

            Spacer(Modifier.height(40.dp))

            // Entra al Test (la pantalla administra su propio micrófono)
            CircleAction(
                size = circleSize,
                color = camWhite,
                icon = Icons.Filled.CameraAlt,
                label = "Capturar",
                labelColor = Color.Black
            ) { onCamera() }

            Spacer(Modifier.weight(1f))
            BottomBar(onSettings, onHelp, 40.dp)
        }
    }

    // Diálogo mientras escucha + visualizador de audio
    if (ui.isListening) {
        AlertDialog(
            onDismissRequest = { /* no cerrar */ },
            title = { Text("Asistente AIMES") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    MicVisualizer(rmsDb = ui.micRmsDb)
                    Spacer(Modifier.height(16.dp))
                    Text(ui.recognizedText, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { asistenteViewModel.stopListening() }) { Text("Detener") }
            }
        )
    }
}

@Composable
fun CircleAction(
    size: Dp,
    color: Color,
    icon: ImageVector,
    label: String,
    labelColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = labelColor, modifier = Modifier.size(80.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = labelColor, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BottomBar(onSettings: () -> Unit, onHelp: () -> Unit, iconSize: Dp) {
    Box(
        Modifier
            .height(80.dp)
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
            .padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Configuración", tint = Color.LightGray, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp) {
                Icon(Icons.Filled.Info, "Instrucciones", tint = Color.LightGray, modifier = Modifier.size(iconSize))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreen(title: String, text: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = Color(0xFF0B0B0B),
        topBar = {
            TopAppBar(
                title = { Text(title, color = Color.White) },
                navigationIcon = {
                    Text("←", Modifier.padding(start = 16.dp).clickable(onClick = onBack), color = Color.White, fontSize = 22.sp)
                }
            )
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White)
        }
    }
}

/* ------------------- Visualizador de micrófono ------------------- */

@Composable
fun MicVisualizer(rmsDb: Float, modifier: Modifier = Modifier) {
    // rms típico ~ [-2, 12]; normalizamos a [0,1]
    val normalized = ((rmsDb - (-2f)) / (12f - (-2f))).coerceIn(0f, 1f)
    val animatedRadius by animateFloatAsState(targetValue = normalized, label = "micRadius")

    Canvas(modifier = modifier.size(80.dp)) {
        val maxR = size.width / 2
        drawCircle(brush = SolidColor(Color.Gray), radius = maxR, style = Stroke(width = 1.dp.toPx()))
        drawCircle(brush = SolidColor(Color(0xFF1E88E5)), radius = maxR * animatedRadius)
    }
}

/* ------------------ ALERTA (usa opción/phone/mensaje) ------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertScreen(
    onBack: () -> Unit,
    phone: String,
    message: String,
    option: String,
    onDialContact: () -> Unit,
    onDialServices: () -> Unit,
    onSendSms: () -> Unit,
    onQuickSms: (String) -> Unit
) {
    val bg = Color(0xFF0B0B0B)
    val txt = Color(0xFFEFEFEF)

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("Llamada de emergencia", color = txt, fontSize = 22.sp) },
                navigationIcon = {
                    Text(
                        "←",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clickable(onClick = onBack),
                        color = txt,
                        fontSize = 24.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111111))
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            when (option) {
                "opt1" -> {
                    BigPill(text = "CONTACTO", onClick = onDialContact)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "“Necesito ayuda”  [ubicación]",
                        color = txt,
                        fontSize = 22.sp,
                        lineHeight = 28.sp
                    )
                    Text("Enviando…", color = txt.copy(alpha = 0.8f), fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    BigHintCard("Mensaje configurado:", message)
                }
                "opt2" -> {
                    BigPill(text = "CONTACTO", onClick = onDialContact)
                    BigPill(text = "SERVICIOS (133)", onClick = onDialServices)
                    BigHintCard("Mensaje configurado:", message)
                }
                "opt3" -> {
                    Text("Mensajes:", color = txt, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    BigMsgCard("Necesito orientación para llegar a mi destino.") { onQuickSms(it) }
                    BigMsgCard("Estoy en un lugar que no reconozco.") { onQuickSms(it) }
                    BigMsgAdd(onClick = { /* TODO: agregar mensaje personalizado */ })
                }
                "opt4" -> {
                    BigPill(text = "CONTACTO", onClick = onDialContact)
                    BigPill(text = "SERVICIOS (133)", onClick = onDialServices)
                    BigBoxButton(text = "MENSAJE", onClick = onSendSms)
                }
                else -> {
                    Text("Selecciona un diseño en Configuración.", color = txt)
                }
            }
        }
    }
}

/* ------------------ SETTINGS (real) ------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    initialPhone: String,
    initialMessage: String,
    initialOption: String,
    initialLargeText: Boolean,
    initialTts: Boolean,
    initialVibration: Boolean,
    onSave: (phone: String, message: String, option: String, large: Boolean, tts: Boolean, vib: Boolean) -> Unit,
    onTestDial: () -> Unit,
    onTestSms: (phone: String, message: String) -> Unit
) {
    val bg = Color(0xFF0B0B0B)
    val txt = Color(0xFFEFEFEF)
    val card = Color(0xFF121212)
    val haptics = LocalHapticFeedback.current

    var phone by rememberSaveable { mutableStateOf(initialPhone) }
    var message by rememberSaveable { mutableStateOf(initialMessage) }
    var option by rememberSaveable { mutableStateOf(initialOption) }
    var large by rememberSaveable { mutableStateOf(initialLargeText) }
    var tts by rememberSaveable { mutableStateOf(initialTts) }
    var vib by rememberSaveable { mutableStateOf(initialVibration) }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("Configuración", color = txt) },
                navigationIcon = {
                    Text(
                        "←",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clickable(onClick = onBack),
                        color = txt,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SectionCard(title = "Accesibilidad", card = card, txt = txt) {
                SettingSwitch(
                    label = "Texto grande",
                    checked = large,
                    onCheckedChange = { large = it; if (vib && it) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    txt = txt
                )
                SettingSwitch(
                    label = "Lectura en voz (placeholder)",
                    checked = tts,
                    onCheckedChange = { tts = it; if (vib && it) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    txt = txt
                )
                SettingSwitch(
                    label = "Vibración (placeholder)",
                    checked = vib,
                    onCheckedChange = { vib = it; if (it) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    txt = txt
                )
            }

            SectionCard(title = "Contacto de emergencia", card = card, txt = txt) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Teléfono", color = txt.copy(alpha = 0.8f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = darkFieldColors(),
                    textStyle = TextStyle(color = txt),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Mensaje", color = txt.copy(alpha = 0.8f)) },
                    singleLine = false,
                    minLines = 2,
                    colors = darkFieldColors(),
                    textStyle = TextStyle(color = txt),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard(title = "Diseño de emergencia (elige uno)", card = card, txt = txt) {
                RadioRow("Opción 1", option == "opt1", txt) { option = "opt1" }
                RadioRow("Opción 2", option == "opt2", txt) { option = "opt2" }
                RadioRow("Opción 3", option == "opt3", txt) { option = "opt3" }
                RadioRow("Opción 4", option == "opt4", txt) { option = "opt4" }
            }

            SectionCard(title = "Probar acciones", card = card, txt = txt) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = onTestDial,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF263238))
                    ) { Text("Llamar 133", color = txt) }
                    FilledTonalButton(
                        onClick = { onTestSms(phone, message) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF263238))
                    ) { Text("Enviar SMS", color = txt) }
                }
            }

            Button(
                onClick = { onSave(phone, message, option, large, tts, vib) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
            ) { Text("Guardar", color = Color.White, fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/* ------------------ HELP (simple, fondo negro) ------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val bg = Color(0xFF0B0B0B)
    val txt = Color(0xFFEFEFEF)
    val context = LocalContext.current

    val instructions = """
        Instrucciones de uso:

        • Botón Alerta: parte superior de la pantalla, color rojo.
          Presiónalo para iniciar acciones de emergencia.

        • Micrófono: centro de la pantalla, botón circular amarillo.
          Actívalo para describir o guiar con la voz.

        • Capturar: debajo del micrófono, botón circular blanco.
          Úsalo para tomar una foto y describir la escena.

        • Configuración: parte inferior izquierda (icono ⚙, gris).
          Edita número de emergencia, mensajes y opciones.

        • Instrucciones: parte inferior derecha (icono ℹ, gris).
          Muestra esta misma pantalla.

        Consejos:
        • Mantén tu teléfono cargado y con datos o llamada habilitada.
        • Verifica que el número de emergencia esté configurado.
    """.trimIndent()

    var isSpeaking by remember { mutableStateOf(false) }
    var ttsRef by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("es", "CL")
            }
        }
        ttsRef = engine
        onDispose {
            isSpeaking = false
            engine?.stop(); engine?.shutdown()
            ttsRef = null
        }
    }

    fun speakAll() {
        ttsRef?.speak(instructions, TextToSpeech.QUEUE_FLUSH, null, "help-tts")
        isSpeaking = true
    }
    fun stopSpeak() {
        ttsRef?.stop()
        isSpeaking = false
    }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("Instrucciones", color = txt, fontSize = 26.sp) },
                navigationIcon = {
                    Text("←",
                        modifier = Modifier.padding(start = 16.dp).clickable(onClick = onBack),
                        color = txt, fontSize = 24.sp)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111111))
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(bg)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    instructions,
                    color = txt,
                    fontSize = 20.sp,
                    lineHeight = 26.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            if (!isSpeaking) {
                Button(
                    onClick = { speakAll() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(40.dp)
                ) {
                    Text("🔊 Leer en voz alta", color = Color.White, fontSize = 22.sp)
                }
            } else {
                OutlinedButton(
                    onClick = { stopSpeak() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(40.dp)
                ) {
                    Text("⏹ Detener lectura", color = txt, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun BigPill(
    text: String,
    onClick: () -> Unit,
    bg: Color = Color(0xFF1F1F1F),
    fg: Color = Color.White
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigBoxButton(
    text: String,
    onClick: () -> Unit,
    bg: Color = Color(0xFF2B2B2B),
    fg: Color = Color.White
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigHintCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(title, color = Color(0xFFBDBDBD), fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Color.White, fontSize = 18.sp, lineHeight = 24.sp)
    }
}

@Composable
private fun BigMsgCard(text: String, onSend: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141414))
            .clickable(onClick = { onSend(text) })
            .padding(18.dp)
            .semantics { contentDescription = "Mensaje: $text" }
    ) {
        Text(text, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text("Tocar para enviar", color = Color(0xFFB0BEC5), fontSize = 14.sp)
    }
}

@Composable
private fun BigMsgAdd(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E272E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("Agregar  +", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionCard(
    title: String,
    card: Color,
    txt: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, txt: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = txt, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, txt: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text, color = txt)
    }
}

@Composable
private fun darkFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF1B1B1B),
    unfocusedContainerColor = Color(0xFF161616),
    disabledContainerColor = Color(0xFF161616),
    cursorColor = Color.White,
    focusedBorderColor = Color(0xFF2E7D32),
    unfocusedBorderColor = Color(0xFF2C2C2C),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

@Composable
private fun PillButton(
    text: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Botón de alerta" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/* ====== Nota: versión alternativa del helper de círculo ======
   Renombrada para evitar colisión con la función principal.
   (La dejo por si la necesitas más adelante.) */
@Composable
private fun CircleActionLarge(
    size: Dp,
    containerColor: Color,
    icon: @Composable () -> Unit,
    label: String,
    labelColor: Color,
    labelSizeSp: Int = 18,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                Spacer(Modifier.height(10.dp))
                Text(
                    label,
                    color = labelColor,
                    fontSize = labelSizeSp.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    modifier: Modifier = Modifier,
    height: Dp,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    iconSize: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .background(Color(0xFF0F0F0F))
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Configuración",
                    tint = Color(0xFFE0E0E0),
                    modifier = Modifier.size(iconSize)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Instrucciones",
                    tint = Color(0xFFE0E0E0),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}