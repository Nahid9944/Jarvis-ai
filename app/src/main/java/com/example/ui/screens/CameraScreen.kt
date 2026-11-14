package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ai.AiSettingsRepository
import com.example.ai.JarvisCameraVisionCoordinator
import com.example.ai.ScreenVisualAnalyzer
import com.example.util.JarvisCameraManager
import com.example.viewmodel.AssistantViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class CameraMode {
    AI_VOICE_VISION,
    TEXT_OCR_SCAN
}

private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val PinkNeon = Color(0xFFF15BB5)
private val SpaceDark = Color(0xFF040814)

@Composable
fun CameraScreen(
    mode: CameraMode = CameraMode.AI_VOICE_VISION,
    viewModel: AssistantViewModel? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "ক্যামেরা ব্যবহারের অনুমতি প্রয়োজন", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(JarvisCameraManager.activeLensFacing) }
    var isVisionPaused by remember { mutableStateOf(false) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    var detectedText by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var visualAnalysisResult by remember { mutableStateOf("") }
    val isLiveStreaming by JarvisCameraVisionCoordinator.isCameraStreaming.collectAsState()
    val coordinatorSummary by JarvisCameraVisionCoordinator.lastVisualSummaryBn.collectAsState()
    val observationState by JarvisCameraVisionCoordinator.observationState.collectAsState()

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(hasCameraPermission, lensFacing, isVisionPaused) {
        if (!hasCameraPermission) return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                if (isVisionPaused) {
                    cameraControl = null
                    JarvisCameraManager.activeCameraControl = null
                    JarvisCameraManager.activeImageCapture = null
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !isVisionPaused) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                if (visionText.text.isNotBlank()) {
                                    detectedText = visionText.text.take(220)
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture,
                    imageAnalysis
                )
                cameraControl = cam.cameraControl
                cam.cameraControl.enableTorch(isFlashOn && lensFacing == CameraSelector.LENS_FACING_BACK)
                JarvisCameraManager.activeLensFacing = lensFacing
                JarvisCameraManager.activeCameraControl = cam.cameraControl
                JarvisCameraManager.activeImageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(context, "ক্যামেরা লোড ত্রুটি: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            JarvisCameraVisionCoordinator.stopVisionStream()
            try {
                cameraControl?.enableTorch(false)
            } catch (_: Exception) {
            }
            cameraExecutor.shutdown()
            JarvisCameraManager.activeCameraControl = null
            JarvisCameraManager.activeImageCapture = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            if (!isVisionPaused) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    val reticleRadius = minOf(w, h) * 0.28f

                    drawCircle(
                        color = CyanNeon.copy(alpha = 0.35f),
                        radius = reticleRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2f)
                    )

                    val crossLen = 30f
                    drawLine(
                        color = CyanNeon,
                        start = Offset(cx - reticleRadius - crossLen, cy),
                        end = Offset(cx - reticleRadius + crossLen, cy),
                        strokeWidth = 3f
                    )
                    drawLine(
                        color = CyanNeon,
                        start = Offset(cx + reticleRadius - crossLen, cy),
                        end = Offset(cx + reticleRadius + crossLen, cy),
                        strokeWidth = 3f
                    )
                    drawLine(
                        color = CyanNeon,
                        start = Offset(cx, cy - reticleRadius - crossLen),
                        end = Offset(cx, cy - reticleRadius + crossLen),
                        strokeWidth = 3f
                    )
                    drawLine(
                        color = CyanNeon,
                        start = Offset(cx, cy + reticleRadius - crossLen),
                        end = Offset(cx, cy + reticleRadius + crossLen),
                        strokeWidth = 3f
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PauseCircle,
                        contentDescription = null,
                        tint = AmberGlow,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ক্যামেরা ভিশন সাময়িক বিরতিতে আছে",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { isVisionPaused = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("camera_resume_center_button")
                    ) {
                        Text("পুনরায় চালু করুন", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = CyanNeon,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ক্যামেরা পারমিশন সক্রিয় করুন",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("camera_permission_button")
                ) {
                    Text("অনুমতি দিন", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Top HUD Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    JarvisCameraVisionCoordinator.stopVisionStream()
                    onClose()
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .testTag("camera_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "বন্ধ করুন",
                    tint = CyanNeon
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (mode == CameraMode.TEXT_OCR_SCAN) "OCR SCANNER" else "MAYA OPTICAL VISION",
                    color = CyanNeon,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isVisionPaused -> "PAUSED"
                        isLiveStreaming -> "LIVE STREAMING TO GEMINI"
                        lensFacing == CameraSelector.LENS_FACING_FRONT -> "FRONT CAMERA READY"
                        else -> "REAR CAMERA READY"
                    },
                    color = if (isLiveStreaming) AmberGlow else Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        cameraControl?.enableTorch(isFlashOn)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .testTag("camera_flash_button")
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "ফ্ল্যাশ",
                        tint = if (isFlashOn) Color.Yellow else Color.White
                    )
                }

                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            isFlashOn = false
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .testTag("camera_switch_lens_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "ক্যামেরা পরিবর্তন",
                        tint = CyanNeon
                    )
                }
            }
        }

        // Bottom Controls & Vision Output
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (visualAnalysisResult.isNotBlank()) {
                Surface(
                    color = SpaceDark.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "AI দৃষ্টি বিশ্লেষণ ফলাফল",
                                color = CyanNeon,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            IconButton(
                                onClick = { visualAnalysisResult = "" },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "বন্ধ",
                                    tint = Color.Gray
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = visualAnalysisResult,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            if (detectedText.isNotBlank() && visualAnalysisResult.isBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Text(
                        text = "চিহ্নিত টেক্সট:\n$detectedText",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (isLiveStreaming || observationState.privacyIndicatorActive) {
                Surface(
                    color = Color(0xFF2B0A18).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PinkNeon.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = "● গোপনীয়তা সূচক: লাইভ ক্যামেরা সক্রিয় (গোপনে কোনো ফ্রেম সংরক্ষিত হয় না)",
                            color = PinkNeon,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "অভিব্যক্তি: ${observationState.detectedExpressionBn} | ইশারা: ${observationState.detectedGestureBn}",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                        Text(
                            text = coordinatorSummary,
                            color = AmberGlow,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Sampling Interval Selector Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ইন্টারভাল:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                listOf(1500L to "1.5s", 3000L to "3s", 5000L to "5s").forEach { (ms, label) ->
                    val selected = observationState.samplingIntervalMs == ms
                    FilterChip(
                        selected = selected,
                        onClick = { JarvisCameraVisionCoordinator.setSamplingInterval(ms) },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.height(34.dp)
                    )
                }
            }

            // Pause / Live Stream / Stop Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        isVisionPaused = !isVisionPaused
                        JarvisCameraVisionCoordinator.setPaused(isVisionPaused)
                        if (isVisionPaused && isLiveStreaming) {
                            JarvisCameraVisionCoordinator.stopVisionStream()
                        }
                    },
                    border = BorderStroke(1.dp, AmberGlow),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SpaceDark.copy(alpha = 0.75f),
                        contentColor = AmberGlow
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("camera_pause_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isVisionPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isVisionPaused) "চালু করুন" else "বিরতি (Pause)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val next = !isLiveStreaming
                        if (next) isVisionPaused = false
                        viewModel?.toggleCameraVisionStream(next)
                            ?: if (next) JarvisCameraVisionCoordinator.startContinuousVisionStream(context)
                            else JarvisCameraVisionCoordinator.stopVisionStream()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLiveStreaming) PinkNeon else BlueHolo
                    ),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                        .testTag("camera_live_stream_button")
                ) {
                    Icon(
                        imageVector = if (isLiveStreaming) Icons.Default.VideocamOff else Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isLiveStreaming) "স্ট্রিম বন্ধ" else "লাইভ ভিশন",
                        color = Color.Black,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        JarvisCameraVisionCoordinator.stopVisionStream()
                        isVisionPaused = false
                        onClose()
                    },
                    border = BorderStroke(1.dp, PinkNeon),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SpaceDark.copy(alpha = 0.75f),
                        contentColor = PinkNeon
                    ),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(48.dp)
                        .testTag("camera_stop_close_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("বন্ধ", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Central Snapshot Capture & Analyze Button
            Button(
                onClick = {
                    if (isAnalyzing || isVisionPaused) return@Button
                    isAnalyzing = true
                    val capture = imageCapture
                    if (capture != null) {
                        val tempFile = File(context.cacheDir, "jarvis_cam_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            val apiKey = AiSettingsRepository(context).settingsState.value.apiKey
                                            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                                            tempFile.delete()
                                            if (bitmap != null) {
                                                JarvisCameraManager.lastCapturedBitmap = bitmap
                                                val analysis = ScreenVisualAnalyzer.analyzeBitmap(
                                                    bitmap = bitmap,
                                                    apiKey = apiKey,
                                                    userQuery = "ক্যামেরার সামনে কি আছে তা বিস্তারিত বাংলায় বর্ণনা করো।"
                                                )
                                                withContext(Dispatchers.Main) {
                                                    isAnalyzing = false
                                                    visualAnalysisResult = analysis.summaryBengali
                                                    viewModel?.addJarvisChatMessage(
                                                        userPrompt = "[ক্যামেরা ভিজুয়াল বিশ্লেষণ]",
                                                        aiResponse = analysis.summaryBengali,
                                                        commandType = "SCREEN_VISION",
                                                        status = "SUCCESS"
                                                    )
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    isAnalyzing = false
                                                    visualAnalysisResult = "ক্যামেরা ছবি ডিকোড করা যায়নি।"
                                                }
                                            }
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    tempFile.delete()
                                    isAnalyzing = false
                                    Toast.makeText(context, "ছবি ধারণে ত্রুটি: ${exception.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        isAnalyzing = false
                        visualAnalysisResult = if (detectedText.isNotBlank()) "চিহ্নিত লেখা:\n$detectedText" else "ক্যামেরা প্রিভিউ প্রস্তুত নয়।"
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                modifier = Modifier
                    .size(72.dp)
                    .testTag("camera_capture_button"),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "ভিজ্যুয়াল স্ক্যান",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}
