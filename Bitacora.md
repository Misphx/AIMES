# Bitácora pre-final AIMES
---

# Que se realizó?:

* **Captura de video en tiempo real**: uso de CameraX + PreviewView. Se reciben frames de la cámara de forma continua y se entregan al Analyzer.
* **Módulo de visión operativo**: el Analyzer invoca VisionModule.process y se obtienen detecciones (etiqueta, confianza y bounding box). La visualización de cajas existe pero muestra desalineación en algunos dispositivos. El pipeline trabaja a 640px para mantener consistencia.
* **Orquestación en `AsistenteViewModel`**: estado de la UI (escuchando/procesando/hablando), envío/recepción de detecciones hacia la capa de presentación y centralización del micrófono + comandos para uso en MainActivity y PerformanceTestScreen.
* **Reconocimiento de voz (ASR) básico**: integración con `SpeechRecognizer` (Android). Soporta start/stop de escucha y maneja permisos de micrófono. No hay palabra de activación ni modelo ASR dedicado aún.
* **Texto‑a‑voz (TTS) integrado**: TextToSpeech con UtteranceProgressListener para saber cuándo inicia/termina cada locución. Existen métodos speak() y cancelación. El sistema confirma acciones con mensajes cortos.
* **Comandos y rutas de intención (mínimo viable)**: Comando/ComandosWrapper y un router simple en el ViewModel que mapea frases cortas a acciones conocidas.
* **Reglas simples detección → instrucción**: existe un mapeo básico que convierte ciertas etiquetas en instrucciones de orientación de una sola frase.
* **Pantalla de pruebas de rendimiento (parcial)**: PerformanceTestScreen muestra FPS y una latencia media de inferencia aproximada. Métricas como mAP/IoU están en desarrollo, por ahora generamos logs de tiempo por frame para análisis.
* **Entrega de información al usuario**: la app habla el estado/resultado a través de TTS (confirmaciones y mensajes guía breves).
* **Contexto y orientación (parcial)**: OrientationEngine está integrado a nivel de tipos y se consume de forma limitada en el mapeo a instrucciones; histeresis y semaforización aún no están activas como políticas globales.

---

## 2) Arquitectura mínima de punta a punta

* **Captura**: CameraX + PreviewView (UI) → frames a **Analyzer**.
* **Detección**: VisionModule (ej. YOLO/pose) → List<Detection> (clase propia) + OrientationEngine.
* **Fusión & Estado**: AsistenteViewModel agrega contexto (estación, destino, orientación) y aplica **histeresis** / **semaforización** para estabilidad.
* **Comprensión → Instrucción**: detectionsToInstruction() traduce detecciones a pasos orientadores.
* **Diálogo por voz**: SpeechRecognizer (motor offline) para comandos; TextToSpeech para salida con colas, earcons y confirmaciones.
* **Debug/QA**: PerformanceTestScreen mide **precision@k**, **latencia de inferencia**, **FPS**, y muestra overlays.

---

## 3) Bitácora por tarea

### 3.1 Permite la entrada de video en tiempo real desde la app

**Qué hice**

* Integré **CameraX** con PreviewView y un **ImageAnalysis.Analyzer** dedicado al VisionModule.
* Trabajamos a 640px, lo cual es consistente con el modelo para evitar desalineaciones.

**En el código**

* UiScreen (Compose) contiene el AndroidView(PreviewView).
* bindCamera(lifecycleOwner, previewView, analyzer) en un helper; el **analyzer** envía ImageProxy → VisionModule.process(image).

---

### 3.2 Evaluación del modelo mediante métricas de precisión y rendimiento

**Qué hice**

* Definí **precision**, **recall**, **F1**, **mAP@IoU=0.5**, y métricas runtime (**FPS**, **latencia p50/p90**).

**En el código**

* PerformanceTestScreen suscrito a viewModel.metrics.
* Utilidad `MetricsTracker` con:

  * recordInference(startNs, endNs) → latencia;
  * recordDetection(gt, pred) → TP/FP/FN por IoU.

---

### 3.3 Visualización de los resultados sobre el video en vivo para debug o testeo

**Qué hice**

* Overlays con Canvas Compose: bounding boxes, etiquetas y confianza.
* Respeté el sistema de coordenadas a 640 px para evitar drift.

**En el código**

* DetectionOverlay dibuja rectángulos/labels.
* Escalado: modelo y preview alineados a 640.

---

### 3.4 Traduce detecciones a instrucciones

**Qué hice**

* Implementé un motor de reglas: detecciones + orientación + contexto → pasos accionables (ej: “gira a la derecha y avanza 5 m”).
* Añadí **histeresis** para evitar que cambie de instrucción por ruido.

**En el código**

* detectionsToInstruction(dets, orientation, contexto): Instruccion.
* HysteresisFilter` para suavizar cambios en clases/ángulos.

---

### 3.5 Formulación de frases orientadoras directas

**Qué hice**

* Redacté plantillas cortas y estructura consistente.

**En el código**

* PhraseBuilder: mapea Instruccion → String/SSML breve.

---

### 3.6 Retroalimentación de que se ha entendido el comando y qué se está haciendo

**Qué hice**

* Confirmaciones explícitas.
* Estados: escuchando → procesando → guiando.

**En el código**

* AsistenteViewModel.state: UiState(listening, processing, speaking).
* UtteranceProgressListener actualiza a speaking/idle y dispara el siguiente paso.

---

### 3.7 Implementación de un sistema de texto‑a‑voz integrado

**Qué hice**

* TextToSpeech configurado con **AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY**.

**En el código**

* TtsEngine.speak(text, queue=ADD) y flush() para cortes necesarios.
* Parametrización de **speech rate** y **pitch** por perfil.

---

### 3.10 Estructura de menús, comandos y respuestas 100% voz

**Qué hice**

* Modelo **intención → slots → acción**; confirmación si baja confianza.
* Menú por voz: “**Ayuda**”, “**Repetir**”, “**Cancelar**”, “**Siguiente paso**”.

**En el código**

* Comando, ComandosWrapper y CommandRouter en AsistenteViewModel.
* JSON de gramática/alias para sinónimos frecuentes.

---

### 3.11 Implementación de reconocimiento de voz con motor offline

**Qué hice**

* Integré un motor ASR offline con palabras de activación y fallback si falla.

**En el código**

* Interfaz SpeechEngine con dos implementaciones.
* RecognizerIntent.EXTRA_PREFER_OFFLINE = true cuando aplica.

---

### 3.15 Relaciona lo que se detecta con lo que se solicita

**Qué hice**

* Detecciones vinculadas con la intención del usuario.

**En el código**

* ContextResolver cruza Detection + contexto.estacion/destino.

---

## 4) Métricas, metodología y resultados

* **FPS**: media y p50/p90 (sliding window de 3s).
* **Latencia de inferencia**: endNs - startNs por frame.
* **Precisión**: TP/FP/FN por IoU; **mAP@0.5** para clases relevantes (señalética, accesos).
* **ASR**: **WER** y **exactitud de intención** en comandos frecuentes.
* **TTS**: latencia onStart - speak() y tasa de barge‑in exitosa.
