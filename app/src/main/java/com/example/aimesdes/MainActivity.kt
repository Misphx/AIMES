package com.example.aimesdes

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aimesdes.ui.PerformanceTestScreen
import com.example.aimesdes.vision.VisionModule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.Normalizer
import java.util.Locale

/* ------------------- ViewModel y Data ------------------- */

data class Comando(
    val entrada: String,
    val intencion: String,
    val objeto: String? = null,
    val modo: String? = null,
    val estacion: String? = null
)

data class ComandosWrapper(val comandos: List<Comando>)

data class AsistenteUIState(
    val isListening: Boolean = false,
    val recognizedText: String = "Toca el micrófono para hablar",
    val assistantResponse: String = "",
    val micRmsDb: Float = 0f
)

class AsistenteViewModel(application: Application) : AndroidViewModel(application) {
    val uiState = mutableStateOf(AsistenteUIState())
    private var tts: TextToSpeech? = null
    private val speechRecognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(application)
    private val comandos: List<Comando>
    private val handler = Handler(Looper.getMainLooper())
    private var lastIntent: Intent? = null
    private var utteranceCounter = 0

    init {
        comandos = cargarComandos()
        setupTts()
        setupSpeechRecognizer()
        greetUser()
    }

    override fun onCleared() {
        super.onCleared()
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
    }

    private fun greetUser() {
        val mensaje = "Hola. ¿En qué puedo ayudarte?"
        reproducirTexto(mensaje)
        uiState.value = uiState.value.copy(assistantResponse = mensaje)
    }

    fun startListening() {
        try { tts?.stop() } catch (_: Exception) {}
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getApplication<Application>().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        lastIntent = intent
        try {
            speechRecognizer.startListening(intent)
            uiState.value = uiState.value.copy(isListening = true, recognizedText = "Escuchando...")
        } catch (_: Exception) {
            uiState.value = uiState.value.copy(isListening = false, recognizedText = "Error al iniciar micrófono")
        }
    }

    fun stopListening() {
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        uiState.value = uiState.value.copy(isListening = false)
    }

    private fun setupTts() {
        tts = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CL")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        val recognitionListener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()
                if (spoken != null) {
                    uiState.value = uiState.value.copy(recognizedText = spoken, isListening = false)
                    procesarComando(spoken)
                }
            }
            override fun onError(error: Int) { uiState.value = uiState.value.copy(isListening = false) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) { uiState.value = uiState.value.copy(micRmsDb = rmsdB) }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(recognitionListener)
    }

    private fun cargarComandos(): List<Comando> {
        return try {
            val jsonString = getApplication<Application>().assets.open("comandos.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<ComandosWrapper>() {}.type
            Gson().fromJson<ComandosWrapper>(jsonString, type).comandos
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun procesarComando(texto: String) {
        val normText = normalize(texto)
        val comandoEncontrado = comandos.find { matchesCommand(normText, it) }
        if (comandoEncontrado != null) {
            reproducirTexto("Ejecutando ${comandoEncontrado.intencion}")
        } else {
            reproducirTexto("No entendí bien.")
        }
    }

    private fun reproducirTexto(texto: String) {
        utteranceCounter++
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "utt-$utteranceCounter")
    }

    private fun normalize(text: String): String {
        var s = text.lowercase(Locale.getDefault()).trim()
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        s = s.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ")
        return s
    }

    private fun matchesCommand(text: String, comando: Comando): Boolean {
        val normText = normalize(text)
        return comando.entrada.split("|").any { variant ->
            val normVar = normalize(variant)
            val tokens = normVar.split(" ").filter { it.isNotBlank() }
            tokens.all { token -> normText.contains(token) }
        }
    }
}

/* ------------------- Main Activity ------------------- */

class MainActivity : ComponentActivity() {
    private val asistenteViewModel: AsistenteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val visionModule = remember { VisionModule() } // ✅ crea instancia real

            MaterialTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onAlert = { nav.navigate("alert") },
                            onSettings = { nav.navigate("settings") },
                            onHelp = { nav.navigate("help") },
                            onMicRequested = { asistenteViewModel.startListening() },
                            onCamera = { nav.navigate("performance_test") },
                            asistenteViewModel = asistenteViewModel
                        )
                    }
                    composable("alert") {
                        SimpleScreen("Alerta", "Pantalla de alerta") { nav.popBackStack() }
                    }
                    composable("settings") {
                        SimpleScreen("Configuración", "Pantalla de configuración") { nav.popBackStack() }
                    }
                    composable("help") {
                        SimpleScreen("Ayuda", "Pantalla de ayuda") { nav.popBackStack() }
                    }
                    composable("performance_test") {
                        PerformanceTestScreen(visionModule = visionModule) // ✅ llamado correcto
                    }
                }
            }
        }
    }
}

/* ------------------- Home Screen ------------------- */

@Composable
fun HomeScreen(
    onAlert: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onMicRequested: () -> Unit,
    onCamera: () -> Unit,
    asistenteViewModel: AsistenteViewModel
) {
    val bg = Color(0xFF0B0B0B)
    val micYellow = Color(0xFFFFD54F)
    val camWhite = Color.White
    val pillRed = Color(0xFFC62828)
    val context = LocalContext.current

    // Permiso de micrófono
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
        if (granted) onMicRequested()
    }

    fun handleMicClick() {
        if (hasMicPerm) onMicRequested()
        else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

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

            PillButton("⚠ Alerta", pillRed, Modifier.fillMaxWidth().height(90.dp)) { onAlert() }
            Spacer(Modifier.height(40.dp))

            CircleAction(circleSize, micYellow, Icons.Filled.Mic, "Micrófono", Color.Black) {
                handleMicClick()
            }

            Spacer(Modifier.height(40.dp))

            CircleAction(circleSize, camWhite, Icons.Filled.CameraAlt, "Capturar", Color.Black) {
                onCamera()
            }

            Spacer(Modifier.weight(1f))
            BottomBar(onSettings, onHelp, 40.dp)
        }
    }
}

/* ------------------- Reusables ------------------- */

@Composable
fun PillButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CircleAction(
    size: Dp,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

/* ------------------- Simple placeholder ------------------- */

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