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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aimesdes.ui.PerformanceTestScreen
import com.example.aimesdes.vision.VisionModule
import com.example.aimesdes.assistant.AsistenteViewModel


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
                        PerformanceTestScreen(
                            visionModule = visionModule,
                            asistenteViewModel = asistenteViewModel
                        )
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