package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ai.AiSettingsRepository
import com.example.ai.JarvisPersonalityMode
import com.example.ai.LiveSessionState
import com.example.ai.NewsItem
import com.example.ai.TaskStatus
import com.example.ai.UserEmotion
import com.example.data.local.AiMemoryEntity
import com.example.data.local.AiTaskEntity
import com.example.viewmodel.AssistantViewModel
import com.example.ui.components.JarvisDialPadDialog
import com.example.ui.components.WhatsAppComposerDialog
import com.example.ui.components.openWhatsAppDirect
import com.example.ui.components.JarvisOsSettingsDialog
import com.example.ui.components.JarvisHolographicChatList
import com.example.util.JarvisCameraManager
import com.example.util.JarvisDeviceAutomationHelper
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Archer Neon Emerald/Cyan Dark HUD Palette
private val SpaceDark = Color(0xFF020806)
private val SpaceSurface = Color(0xFF0A2420)
private val CardBg = Color(0xFF061B16)
private val CyanNeon = Color(0xFF00FF9D)
private val BlueHolo = Color(0xFF00E5FF)
private val AmberGlow = Color(0xFFFEE440)
private val PinkNeon = Color(0xFFFF476F)
private val TextWhite = Color(0xFFF5FFF9)
private val TextDim = Color(0xFF93B9AA)
private val EmeraldNeon = Color(0xFF00D98B)
private val BorderGlow = Color(0xFF144D3D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    viewModel: AssistantViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val liveState by viewModel.aiLiveState.collectAsState()
    val voiceHudState by viewModel.voiceHudState.collectAsState()
    val statusMessage by viewModel.aiStatusMessage.collectAsState()
    val micLevel by viewModel.aiMicLevel.collectAsState()
    val outputLevel by viewModel.aiOutputLevel.collectAsState()
    val settings by viewModel.aiSettings.collectAsState()
    val isBackgroundActive by viewModel.isBackgroundModeActive.collectAsState()

    val memories by viewModel.aiMemories.collectAsState()
    val tasks by viewModel.aiTasks.collectAsState()
    val chatHistory by viewModel.jarvisChatHistory.collectAsState()
    val unreadNotifCount by viewModel.unreadNotificationCount.collectAsState()

    // 0: Live Voice / HUD, 1: Holographic History, 2: Agents, 3: Tools, 4: Memories, 5: Tasks, 6: Settings, 7: Artifacts
    var selectedTab by remember { mutableIntStateOf(0) }
    val requestedHudTab by viewModel.requestedHudTab.collectAsState()
    LaunchedEffect(requestedHudTab) {
        requestedHudTab?.let { target ->
            selectedTab = target
            viewModel.consumeRequestedHudTab()
        }
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    // Memory Dialog States
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<AiMemoryEntity?>(null) }
    var memoryToDelete by remember { mutableStateOf<AiMemoryEntity?>(null) }
    var memorySearchQuery by remember { mutableStateOf("") }
    var selectedMemoryCategory by remember { mutableStateOf("ALL") }

    // Task Dialog States
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<AiTaskEntity?>(null) }
    var selectedTaskFilter by remember { mutableStateOf("PENDING") } // PENDING or COMPLETED

    // Telephony & WhatsApp Dialog States
    var showDialPadDialog by remember { mutableStateOf(false) }
    var showWhatsAppDialog by remember { mutableStateOf(false) }
    var showOsControlDialog by remember { mutableStateOf(false) }
    var showAccessibilityPromptDialog by remember { mutableStateOf(false) }
    var showPermissionCenter by remember { mutableStateOf(false) }
    var isAccessibilityActive by remember {
        mutableStateOf<Boolean>(JarvisDeviceAutomationHelper.isAccessibilityGranted(context))
    }

    // Auto-update accessibility status periodically
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityActive = JarvisDeviceAutomationHelper.isAccessibilityGranted(context)
            kotlinx.coroutines.delay(1500)
        }
    }

    var pendingLocationCommand by remember { mutableStateOf<String?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            Toast.makeText(context, "জিপিএস লোকেশন পারমিশন অনুমোদিত হয়েছে", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val (ok, loc) = com.example.util.JarvisLiveLocationTracker.fetchCurrentLocation(context)
                if (ok && loc != null) {
                    val spoken = com.example.util.JarvisLiveLocationTracker.formatLocationSpokenSummary(loc)
                    Toast.makeText(context, loc.fullAddress, Toast.LENGTH_LONG).show()
                    viewModel.saveJarvisChatRecord(
                        userPrompt = pendingLocationCommand ?: "আমি কোথায় আছি",
                        aiResponse = spoken,
                        commandType = "LOCATION",
                        status = "SUCCESS",
                        executionDetails = "GPS Lat: ${loc.latitude}, Lng: ${loc.longitude}"
                    )
                    viewModel.sendLiveTextMessage("[System Notification: GPS Location acquired: ${loc.fullAddress}. Speak to user in Bengali: $spoken]")
                } else {
                    val msg = "দুঃখিত স্যার, জিপিএস সিগন্যাল পাওয়া যায়নি। অনুগ্রহ করে আপনার ফোনের লোকেশন অন করুন।"
                    viewModel.sendLiveTextMessage("[System Notification: GPS signal failed. Speak to user: $msg]")
                }
                pendingLocationCommand = null
            }
        } else {
            Toast.makeText(context, "রিয়েল-টাইম অবস্থান জানাতে লোকেশন অনুমতি প্রয়োজন", Toast.LENGTH_LONG).show()
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.example.util.JarvisScreenCaptureManager.onMediaProjectionResult(context, result.resultCode, result.data)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "স্ক্রিন ক্যাপচার অনুমতি সক্রিয় হয়েছে", Toast.LENGTH_SHORT).show()
            com.example.permissions.JarvisPermissionManager.onResumeRecheckAndExecute(context)
        }
    }

    // Central Master Orchestrator & Voice Command Dispatcher
    val handleUserCommand: (String) -> Unit = { rawCommand ->
        val clean = rawCommand.trim()
        if (clean.isNotBlank()) {
            coroutineScope.launch {
                val orchestrated = viewModel.orchestrateUserCommand(clean)

                // If purely conversational with no tool plan, stream directly to Gemini Live
                if (orchestrated.finalState == "CONVERSATIONAL" &&
                    orchestrated.plan.subTasks.all { it.toolName == null }
                ) {
                    viewModel.saveJarvisChatRecord(
                        userPrompt = clean,
                        aiResponse = "প্রসেসিং...",
                        commandType = "GENERAL_QUERY",
                        status = "PROCESSING"
                    )
                    viewModel.sendLiveTextMessage(clean)
                    return@launch
                }

                // Surface real permission requirements when blocked by missing system permissions
                val allStepOutputs = orchestrated.executedSteps
                val needsA11y = allStepOutputs.any { step ->
                    step.status == "PERMISSION_REQUIRED" &&
                        (step.permissionRequired.equals("ACCESSIBILITY", ignoreCase = true) ||
                            step.summaryBengali.contains("Accessibility", ignoreCase = true))
                }
                val needsLocation = allStepOutputs.any { step ->
                    (step.status == "PERMISSION_REQUIRED" &&
                        (step.permissionRequired.equals("LOCATION", ignoreCase = true) ||
                            step.summaryBengali.contains("LOCATION", ignoreCase = true))) ||
                        (step.toolName == "getCurrentLocation" && step.status != "SUCCEEDED")
                }
                val needsScreen = allStepOutputs.any { step ->
                    (step.status == "PERMISSION_REQUIRED" &&
                        (step.permissionRequired.equals("SCREEN_CAPTURE", ignoreCase = true) ||
                            step.summaryBengali.contains("Projection", ignoreCase = true))) ||
                        (step.toolName == "startScreenSharing" && step.status != "SUCCEEDED")
                }

                if (needsA11y) {
                    showAccessibilityPromptDialog = true
                }
                if (needsLocation) {
                    pendingLocationCommand = clean
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                if (needsScreen) {
                    screenCaptureLauncher.launch(
                        com.example.util.JarvisScreenCaptureManager.getScreenCaptureIntent(context)
                    )
                }

                val uiStatus = when (orchestrated.finalState) {
                    "SUCCEEDED", "PERSONALITY_SWITCHED", "INTERRUPTION_HANDLED" -> "SUCCESS"
                    "WAITING_FOR_USER" -> "WAITING"
                    "REQUIRES_PERMISSION", "PERMISSION_REQUIRED" -> "PERMISSION_REQUIRED"
                    "REQUIRES_CONFIRMATION", "CONFIRMATION_REQUIRED" -> "CONFIRMATION_REQUIRED"
                    "PARTIALLY_COMPLETED", "PARTIAL_SUCCESS" -> "PARTIAL_SUCCESS"
                    "CANCELLED", "EMERGENCY_STOPPED" -> "CANCELLED"
                    else -> if (orchestrated.success) "SUCCESS" else "FAILED"
                }

                Toast.makeText(context, orchestrated.summaryBengali, Toast.LENGTH_SHORT).show()
                viewModel.saveJarvisChatRecord(
                    userPrompt = clean,
                    aiResponse = orchestrated.summaryBengali,
                    commandType = orchestrated.plan.primaryAgent.name,
                    status = uiStatus,
                    executionDetails = "[Agent: ${orchestrated.plan.primaryAgent.titleBengali} | State: ${orchestrated.finalState}] ${orchestrated.summaryBengali}"
                )
                viewModel.sendLiveTextMessage(
                    "[System Notification: Master Orchestrator result (${orchestrated.finalState}): ${orchestrated.summaryBengali}. Reply briefly & truthfully in Bengali.]"
                )
            }
        }
    }

    // Listen to background speech recognition wake-word events & preempt mic cleanly via HandsFreeSessionController
    LaunchedEffect(Unit) {
        com.example.service.JarvisWakeWordService.wakeWordEvents.collect { payload ->
            viewModel.handleWakeWordTrigger(
                matchedPhrase = payload.matchedPhrase,
                remainderCommand = payload.remainderCommand
            ) { inlineCommand ->
                handleUserCommand(inlineCommand)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.wakeInlineCommands.collect { inlineCommand ->
            if (inlineCommand.isNotBlank()) {
                handleUserCommand(inlineCommand)
            }
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startLiveSession()
            viewModel.startMicStreaming()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (settings.autoConnect) {
            viewModel.startLiveSession()
            viewModel.startMicStreaming()
        }
    }

    DisposableEffect(Unit) {
        com.example.permissions.PermissionOrchestrator.setSessionPermissionHandler { _, spec ->
            if (spec.id == "screen_capture") {
                screenCaptureLauncher.launch(
                    com.example.util.JarvisScreenCaptureManager.getScreenCaptureIntent(context)
                )
            }
        }
        onDispose {
            com.example.permissions.PermissionOrchestrator.setSessionPermissionHandler(null)
            // If background continuous mode is NOT active, stop the session when exiting screen
            if (!viewModel.isBackgroundModeActive.value) {
                viewModel.stopLiveSession()
            }
        }
    }

    val activeAgent by viewModel.activeAiAgent.collectAsState()

    Scaffold(
        containerColor = SpaceDark,
        topBar = {
            JarvisTopAppBar(
                liveState = liveState,
                voiceHudState = voiceHudState,
                isBackgroundActive = isBackgroundActive,
                isAccessibilityActive = isAccessibilityActive,
                unreadNotificationCount = unreadNotifCount,
                onBack = onBack,
                onToggleBackground = { viewModel.toggleBackgroundMode(!isBackgroundActive) },
                onOpenAccessibilityPrompt = { showAccessibilityPromptDialog = true },
                onOpenNotifications = { viewModel.navigateTo("notifications") },
                onOpenControlCenter = { viewModel.navigateTo("control_center") },
                onOpenPermissionCenter = { showPermissionCenter = true },
                onOpenOsMatrix = { showOsControlDialog = true },
                onOpenSettings = { showSettingsDialog = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(SpaceDark, SpaceSurface, Color(0xFF030712))
                    )
                )
        ) {
            BackHandler(enabled = selectedTab != 0) {
                selectedTab = 0
            }

            // Show telemetry strip & multi-tab bar on secondary tabs (kept clean on Live Home HUD Tab 0)
            if (selectedTab != 0) {
                TelemetryStatsStrip(
                    activeAgentName = activeAgent.titleBengali,
                    memoryCount = memories.size,
                    pendingTasksCount = tasks.count { !it.isExecuted },
                    isBackgroundActive = isBackgroundActive,
                    liveState = liveState
                )
                JarvisNavTabBar(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it }
                )
            }

            // Content Area based on Tab
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> JarvisLiveHudTab(
                        viewModel = viewModel,
                        liveState = liveState,
                        statusMessage = statusMessage,
                        micLevel = micLevel,
                        outputLevel = outputLevel,
                        textInput = textInput,
                        onTextInputChange = { textInput = it },
                        onSendMessage = {
                            if (textInput.isNotBlank()) {
                                val msg = textInput
                                textInput = ""
                                focusManager.clearFocus()
                                handleUserCommand(msg)
                            }
                        },
                        onOpenDialPad = { showDialPadDialog = true },
                        onOpenWhatsApp = { showWhatsAppDialog = true },
                        onExecuteCommand = handleUserCommand,
                        onOpenChatHistory = { selectedTab = 1 },
                        onNavigateTab = { selectedTab = it },
                        aiTasks = tasks,
                        pendingTasksCount = tasks.count { !it.isExecuted },
                        onRequestScreenCapture = {
                            screenCaptureLauncher.launch(
                                com.example.util.JarvisScreenCaptureManager.getScreenCaptureIntent(context)
                            )
                        },
                        onStopScreenCapture = {
                            com.example.util.JarvisScreenCaptureManager.stopScreenCaptureService(context)
                            Toast.makeText(context, "স্ক্রিন শেয়ারিং বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    )
                    1 -> {
                        JarvisHolographicChatList(
                            historyList = chatHistory,
                            onClearAll = { viewModel.clearAllJarvisChatHistory() },
                            onDeleteSingle = { id -> viewModel.deleteJarvisChatRecord(id) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    2 -> JarvisAgentsTab(
                        viewModel = viewModel,
                        onSelectPrompt = { prompt ->
                            textInput = prompt
                            selectedTab = 0
                            handleUserCommand(prompt)
                        }
                    )
                    3 -> JarvisToolsTab(
                        viewModel = viewModel,
                        onAskJarvis = { query ->
                            textInput = query
                            selectedTab = 0
                            handleUserCommand(query)
                        }
                    )
                    4 -> JarvisMemoryTab(
                        memories = memories,
                        searchQuery = memorySearchQuery,
                        onSearchChange = { memorySearchQuery = it },
                        selectedCategory = selectedMemoryCategory,
                        onSelectCategory = { selectedMemoryCategory = it },
                        onAddMemoryClick = { showAddMemoryDialog = true },
                        onEditMemory = { editingMemory = it },
                        onDeleteMemory = { memoryToDelete = it }
                    )
                    5 -> JarvisTasksTab(
                        tasks = tasks,
                        selectedFilter = selectedTaskFilter,
                        onSelectFilter = { selectedTaskFilter = it },
                        onAddTaskClick = { showAddTaskDialog = true },
                        onCompleteTask = { taskId ->
                            viewModel.markAiTaskCompleted(taskId) {
                                Toast.makeText(context, "টাস্ক সম্পন্ন হিসেবে চিহ্নিত হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteTask = { taskToDelete = it }
                    )
                    6 -> JarvisCoreSettingsTab(
                        viewModel = viewModel,
                        isBackgroundActive = isBackgroundActive,
                        settings = settings,
                        onOpenFullSettings = { showSettingsDialog = true }
                    )
                    7 -> JarvisArtifactWorkspaceTab(
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------
    // DIALOGS & OVERLAYS
    // -------------------------------------------------------------

    if (showSettingsDialog) {
        AiSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Add Memory Dialog
    if (showAddMemoryDialog) {
        AddOrEditMemoryDialog(
            initialKey = "",
            initialContent = "",
            initialCategory = "USER_PREFERENCE",
            initialImportance = 1,
            title = "নতুন এআই মেমোরি সংরক্ষণ",
            onDismiss = { showAddMemoryDialog = false },
            onSave = { key, content, category, importance ->
                viewModel.saveAiMemory(key, content, category, importance) {
                    showAddMemoryDialog = false
                    Toast.makeText(context, "মেমোরিতে সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Edit Memory Dialog
    editingMemory?.let { memory ->
        AddOrEditMemoryDialog(
            initialKey = memory.key,
            initialContent = memory.content,
            initialCategory = memory.category,
            initialImportance = memory.importance,
            title = "মেমোরি সম্পাদনা",
            onDismiss = { editingMemory = null },
            onSave = { key, content, category, importance ->
                val updated = memory.copy(
                    key = key,
                    content = content,
                    category = category,
                    importance = importance,
                    isPinned = importance >= 3
                )
                viewModel.updateAiMemory(updated) {
                    editingMemory = null
                    Toast.makeText(context, "মেমোরি আপডেট সম্পন্ন!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Delete Memory Confirm Dialog
    memoryToDelete?.let { mem ->
        AlertDialog(
            onDismissRequest = { memoryToDelete = null },
            title = { Text("মেমোরি মুছে ফেলতে চান?", fontWeight = FontWeight.Bold, color = TextWhite) },
            text = { Text("'${mem.key}' তথ্যটি মেমোরি থেকে সম্পূর্ণ মুছে যাবে।", color = TextDim) },
            containerColor = CardBg,
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAiMemory(mem.id) {
                            memoryToDelete = null
                            Toast.makeText(context, "মেমোরি মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PinkNeon)
                ) {
                    Text("মুছে ফেলুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryToDelete = null }) {
                    Text("বাতিল", color = TextDim)
                }
            }
        )
    }

    // Add Task Dialog
    if (showAddTaskDialog) {
        AddAiTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onSave = { title, desc, scheduledTime, timeString, priority ->
                viewModel.createAiTask(title, desc, scheduledTime, timeString, priority) {
                    showAddTaskDialog = false
                    Toast.makeText(context, "টাস্ক শিডিউল সম্পন্ন হয়েছে!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Delete Task Confirm Dialog
    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("টাস্ক বাতিল করতে চান?", fontWeight = FontWeight.Bold, color = TextWhite) },
            text = { Text("'${task.title}' টাস্কটি মুছে ফেলা হবে।", color = TextDim) },
            containerColor = CardBg,
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAiTask(task.id) {
                            taskToDelete = null
                            Toast.makeText(context, "টাস্ক মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PinkNeon)
                ) {
                    Text("মুছে ফেলুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text("বাতিল", color = TextDim)
                }
            }
        )
    }

    // Telephony SIM Dial Pad Dialog
    if (showDialPadDialog) {
        JarvisDialPadDialog(
            onDismiss = { showDialPadDialog = false },
            onActivateBackgroundAi = { viewModel.toggleBackgroundMode(true) }
        )
    }

    // WhatsApp Direct Hands-Free Dialog
    if (showWhatsAppDialog) {
        WhatsAppComposerDialog(
            phoneNumber = "",
            onDismiss = { showWhatsAppDialog = false },
            onSend = { msg ->
                showWhatsAppDialog = false
                viewModel.toggleBackgroundMode(true)
                openWhatsAppDirect(context, "", msg)
            }
        )
    }

    // Android OS Matrix & Permissions Dialog
    if (showOsControlDialog) {
        JarvisOsSettingsDialog(
            onRequestScreenCapture = {
                screenCaptureLauncher.launch(com.example.util.JarvisScreenCaptureManager.getScreenCaptureIntent(context))
            },
            onDismiss = { showOsControlDialog = false }
        )
    }

    // Android Accessibility Prompt Dialog
    if (showAccessibilityPromptDialog) {
        com.example.ui.components.JarvisAccessibilityPromptDialog(
            onDismiss = { showAccessibilityPromptDialog = false },
            onPermissionGranted = {
                showAccessibilityPromptDialog = false
                isAccessibilityActive = true
                Toast.makeText(context, "জারভিস অ্যাক্সেসিবিলিটি সক্রিয় হয়েছে", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Active Permission Rationale Bottom Sheet
    val currentPermissionRequest by com.example.permissions.PermissionOrchestrator.currentRequest.collectAsState()
    currentPermissionRequest?.let { event ->
        com.example.permissions.ui.PermissionRationaleSheet(
            requestEvent = event,
            onDismiss = { com.example.permissions.PermissionOrchestrator.dismiss() }
        )
    }

    // Full JARVIS OS Permission Center Screen Modal
    if (showPermissionCenter) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPermissionCenter = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.example.permissions.ui.PermissionCenterScreen(
                onBack = { showPermissionCenter = false }
            )
        }
    }

    // JARVIS OS Human Gate Confirmation Dialog for High-Risk Automation
    val currentGateRequest by com.example.automation.HumanGate.pendingRequest.collectAsState()
    currentGateRequest?.let { req ->
        com.example.ui.components.JarvisHumanGateDialog(
            request = req,
            onConfirm = { com.example.automation.HumanGate.respond(true) },
            onDismiss = { com.example.automation.HumanGate.respond(false) }
        )
    }
}

// =================================================================
// 1. TOP APP BAR
// =================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisTopAppBar(
    liveState: LiveSessionState,
    voiceHudState: com.example.audio.AssistantVoiceHudState,
    isBackgroundActive: Boolean,
    isAccessibilityActive: Boolean,
    unreadNotificationCount: Int,
    onBack: () -> Unit,
    onToggleBackground: () -> Unit,
    onOpenAccessibilityPrompt: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onOpenPermissionCenter: () -> Unit,
    onOpenOsMatrix: () -> Unit,
    onOpenSettings: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SpaceDark,
            titleContentColor = TextWhite
        ),
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SpaceSurface)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = CyanNeon,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(CyanNeon.copy(alpha = 0.16f))
                        .border(1.dp, CyanNeon.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "NAHID AI",
                            fontSize = 15.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            letterSpacing = 1.sp,
                            maxLines = 1
                        )
                        PulsingStatusDot(liveState = liveState)
                    }
                    val subtitleText = when (voiceHudState) {
                        com.example.audio.AssistantVoiceHudState.LISTENING -> "VOICE LIVE • LISTENING"
                        com.example.audio.AssistantVoiceHudState.SPEAKING -> "SPEAKING"
                        com.example.audio.AssistantVoiceHudState.THINKING -> "THINKING"
                        com.example.audio.AssistantVoiceHudState.TOOL_EXECUTING -> "TOOL EXECUTING"
                        com.example.audio.AssistantVoiceHudState.VERIFYING -> "VERIFYING"
                        com.example.audio.AssistantVoiceHudState.WAITING_FOR_PERMISSION -> "PERMISSION NEEDED"
                        com.example.audio.AssistantVoiceHudState.WAITING_FOR_CONFIRMATION -> "CONFIRMATION NEEDED"
                        com.example.audio.AssistantVoiceHudState.WAKE_WORD_ARMED -> "WAKE-WORD ARMED"
                        com.example.audio.AssistantVoiceHudState.PAUSED -> "PAUSED"
                        com.example.audio.AssistantVoiceHudState.OFFLINE -> "OFFLINE"
                        com.example.audio.AssistantVoiceHudState.ERROR -> "OFFLINE / ERROR"
                        else -> when {
                            liveState == LiveSessionState.CONNECTING -> "CONNECTING"
                            isBackgroundActive -> "BG SERVICE ON"
                            else -> "J.A.R.V.I.S. OS"
                        }
                    }
                    Text(
                        text = subtitleText,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            voiceHudState == com.example.audio.AssistantVoiceHudState.ERROR ||
                                liveState == LiveSessionState.ERROR -> PinkNeon
                            voiceHudState == com.example.audio.AssistantVoiceHudState.WAITING_FOR_PERMISSION ||
                                voiceHudState == com.example.audio.AssistantVoiceHudState.WAITING_FOR_CONFIRMATION -> AmberGlow
                            else -> CyanNeon.copy(alpha = 0.85f)
                        },
                        maxLines = 1
                    )
                }
            }
        },
        actions = {
            // Compact Background Continuous Mic Mode Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isBackgroundActive) CyanNeon.copy(alpha = 0.18f) else SpaceSurface,
                border = BorderStroke(1.dp, if (isBackgroundActive) CyanNeon else BorderGlow),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleBackground() }
                    .padding(end = 2.dp)
                    .testTag("hud_background_voice_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Background Voice",
                        tint = if (isBackgroundActive) CyanNeon else TextDim,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (isBackgroundActive) "BG ON" else "BG",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isBackgroundActive) CyanNeon else TextDim
                    )
                }
            }

            // Accessibility OS pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isAccessibilityActive) CyanNeon.copy(alpha = 0.14f) else AmberGlow.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, if (isAccessibilityActive) CyanNeon.copy(alpha = 0.6f) else AmberGlow.copy(alpha = 0.65f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenAccessibilityPrompt() }
                    .padding(horizontal = 2.dp)
            ) {
                Text(
                    text = if (isAccessibilityActive) "OS অন" else "OS",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAccessibilityActive) CyanNeon else AmberGlow,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }

            // Notification Intelligence Bell Button
            Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                IconButton(
                    onClick = onOpenNotifications,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SpaceSurface)
                        .testTag("open_notifications_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = if (unreadNotificationCount > 0) AmberGlow else CyanNeon,
                        modifier = Modifier.size(17.dp)
                    )
                }
                if (unreadNotificationCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(PinkNeon)
                    )
                }
            }

            // Signature Archer Emerald Square Shield Button (Permission Center & Control Diagnostics)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 2.dp, end = 4.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF00FF88))
                    .clickable { onOpenPermissionCenter() }
                    .testTag("open_permission_shield_button")
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Permissions & System Doctor",
                    tint = Color(0xFF02120B),
                    modifier = Modifier.size(19.dp)
                )
            }

            // Settings Button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SpaceSurface)
                    .testTag("open_control_center_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Settings & OS Controls",
                    tint = TextWhite,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    )
}

// =================================================================
// 2. TELEMETRY STATS STRIP
// =================================================================
@Composable
private fun TelemetryStatsStrip(
    activeAgentName: String,
    memoryCount: Int,
    pendingTasksCount: Int,
    isBackgroundActive: Boolean,
    liveState: LiveSessionState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TelemetryCard(
            label = "AGENT",
            value = activeAgentName,
            accentColor = CyanNeon,
            icon = Icons.Default.SmartToy,
            modifier = Modifier.weight(1.1f)
        )
        TelemetryCard(
            label = "MEMORIES",
            value = "$memoryCount SAVED",
            accentColor = BlueHolo,
            icon = Icons.Default.Psychology,
            modifier = Modifier.weight(0.95f)
        )
        TelemetryCard(
            label = "TASKS",
            value = "$pendingTasksCount DUE",
            accentColor = AmberGlow,
            icon = Icons.Default.Alarm,
            modifier = Modifier.weight(0.95f)
        )
        TelemetryCard(
            label = "CORE",
            value = if (isBackgroundActive) "BG LIVE" else "ONLINE",
            accentColor = BlueHolo,
            icon = Icons.Default.Memory,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    value: String,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = CardBg.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Column {
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextDim
                )
                Text(
                    text = value,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// =================================================================
// 3. SCI-FI NAV TAB BAR
// =================================================================
@Composable
private fun JarvisNavTabBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        "🎙️ লাইভ" to 0,
        "💬 হিস্ট্রি" to 1,
        "🤖 এজেন্টস" to 2,
        "🛠️ টুলস" to 3,
        "🧠 মেমোরি" to 4,
        "⏱️ টাস্ক" to 5,
        "⚙️ সেটিংস" to 6,
        "📄 আর্টিফ্যাক্ট" to 7
    )

    ScrollableTabRow(
        selectedTabIndex = selectedTab,
        containerColor = Color.Transparent,
        contentColor = CyanNeon,
        edgePadding = 12.dp,
        divider = {
            HorizontalDivider(color = Color(0xFF1E293B).copy(alpha = 0.5f), thickness = 0.5.dp)
        },
        indicator = { tabPositions ->
            if (selectedTab < tabPositions.size) {
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedTab])
                        .height(3.dp)
                        .padding(horizontal = 8.dp)
                        .background(
                            Brush.horizontalGradient(listOf(CyanNeon, BlueHolo)),
                            CircleShape
                        )
                )
            }
        }
    ) {
        tabs.forEach { (title, index) ->
            val isSelected = selectedTab == index
            Tab(
                selected = isSelected,
                onClick = { onSelectTab(index) },
                text = {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) CyanNeon.copy(alpha = 0.12f) else Color.Transparent,
                        border = if (isSelected) BorderStroke(1.dp, CyanNeon.copy(alpha = 0.35f)) else null,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected) CyanNeon else TextDim,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            )
        }
    }
}

// =================================================================
// TAB 1: JARVIS LIVE HUD (Archer Neon Emerald / Cyan Mobile HUD)
// =================================================================
private fun chamferedTechShape(cutPx: Float = 24f): GenericShape = GenericShape { size, _ ->
    val c = cutPx.coerceAtMost(size.minDimension * 0.42f)
    moveTo(c, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - c)
    lineTo(size.width - c, size.height)
    lineTo(0f, size.height)
    lineTo(0f, c)
    close()
}

private fun imageProxyToResizedJpegAndBitmap(imageProxy: ImageProxy): Pair<Bitmap, String>? {
    return try {
        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val rawOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 75, rawOut)
        val rawBytes = rawOut.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

        val rot = imageProxy.imageInfo.rotationDegrees.toFloat()
        val rotated = if (rot != 0f) {
            val matrix = Matrix().apply { postRotate(rot) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }

        val maxDim = 480
        val scale = if (rotated.width > maxDim || rotated.height > maxDim) {
            minOf(maxDim.toFloat() / rotated.width, maxDim.toFloat() / rotated.height)
        } else 1f
        val finalBmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else rotated

        val jpegOut = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 70, jpegOut)
        val base64 = Base64.encodeToString(jpegOut.toByteArray(), Base64.NO_WRAP)
        Pair(finalBmp, base64)
    } catch (_: Exception) {
        null
    }
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    return nv21
}

@Composable
private fun JarvisLiveHudTab(
    viewModel: AssistantViewModel,
    liveState: LiveSessionState,
    statusMessage: String,
    micLevel: Float,
    outputLevel: Float,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onOpenDialPad: () -> Unit,
    onOpenWhatsApp: () -> Unit,
    onExecuteCommand: (String) -> Unit = {},
    onOpenChatHistory: () -> Unit = {},
    onNavigateTab: (Int) -> Unit = {},
    aiTasks: List<AiTaskEntity> = emptyList(),
    pendingTasksCount: Int = 0,
    onRequestScreenCapture: () -> Unit = {},
    onStopScreenCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val visionState by com.example.util.JarvisScreenCaptureManager.visionState.collectAsState()

    val activePersonality by viewModel.activePersonality.collectAsState()
    val currentEmotion by viewModel.currentEmotion.collectAsState()
    val activeTask by viewModel.activeInterruptionTask.collectAsState()
    val isEmergencyStopActive by viewModel.isEmergencyStopActive.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val pendingOsAction by viewModel.pendingOsAction.collectAsState()
    val lastAutoResumeResult by viewModel.lastAutoResumeResult.collectAsState()
    val liveInputTranscript by viewModel.liveInputTranscript.collectAsState()
    val liveOutputTranscript by viewModel.liveOutputTranscript.collectAsState()
    val liveNews by viewModel.liveNews.collectAsState()
    val isNewsLoading by viewModel.isNewsLoading.collectAsState()
    val newsError by viewModel.newsError.collectAsState()

    var showEmotionPicker by remember { mutableStateOf(false) }
    var showHeadlinesDialog by remember { mutableStateOf(false) }
    var showInlineCamera by remember { mutableStateOf(false) }
    var inlineCameraFront by remember {
        mutableStateOf(JarvisCameraManager.activeLensFacing == CameraSelector.LENS_FACING_FRONT)
    }
    var hasCameraPerm by remember {
        mutableStateOf(JarvisCameraManager.hasCameraPermission(context))
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastFrameSentMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val currentLiveState by rememberUpdatedState(liveState)

    // Keep camera permission state fresh whenever returning from Android permission dialogs or OS Settings
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCameraPerm = JarvisCameraManager.hasCameraPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Collect automatic HUD events triggered by voice tools (e.g., "আজকের AI news দাও" -> open Information Panel)
    LaunchedEffect(Unit) {
        viewModel.openHeadlinesEvent.collect { items ->
            if (items.isNotEmpty()) {
                showHeadlinesDialog = true
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.closeHeadlinesEvent.collect {
            showHeadlinesDialog = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.inlineCameraToggleEvent.collect { enable ->
            hasCameraPerm = JarvisCameraManager.hasCameraPermission(context)
            val target = enable ?: !showInlineCamera
            if (target) {
                if (!hasCameraPerm) {
                    viewModel.executeHudShortcutTool("startCameraVision")
                } else {
                    showInlineCamera = true
                }
            } else {
                showInlineCamera = false
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.cameraLensChangedEvent.collect { lens ->
            inlineCameraFront = lens == CameraSelector.LENS_FACING_FRONT
        }
    }

    LaunchedEffect(Unit) {
        com.example.util.JarvisScreenCaptureManager.refreshTelemetry(context)
        if (liveNews.isEmpty()) {
            viewModel.refreshLiveNews(category = "সব")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            JarvisCameraManager.activeCameraControl = null
            JarvisCameraManager.activeImageCapture = null
        }
    }

    val pendingTasksList = remember(aiTasks) { aiTasks.filter { !it.isExecuted } }
    val completedTasksCount = remember(aiTasks) { aiTasks.count { it.isExecuted } }
    val totalTasksCount = aiTasks.size.coerceAtLeast(1)
    val taskCompletionProgress = (completedTasksCount.toFloat() / totalTasksCount.toFloat()).coerceIn(0.08f, 1f)

    val hudScrollState = rememberScrollState()
    val techButtonShape = remember { chamferedTechShape(22f) }
    val orangeAccent = Color(0xFFFF9F2E)
    val soulMintAccent = Color(0xFFE2FFF5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Scrollable Primary HUD Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(hudScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. TOP 3 CHAMFERED ACTION BUTTONS (LIVE CAMERA | FRONT | SHARE SCREEN)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LIVE CAMERA
                Surface(
                    shape = techButtonShape,
                    color = if (showInlineCamera) CyanNeon.copy(alpha = 0.2f) else CardBg,
                    border = BorderStroke(1.2.dp, if (showInlineCamera) CyanNeon else TextWhite.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .weight(1.1f)
                        .heightIn(min = 48.dp)
                        .clickable {
                            viewModel.executeHudShortcutTool(
                                if (showInlineCamera) "stopCameraVision" else "startCameraVision"
                            )
                        }
                        .testTag("hud_toggle_inline_camera")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (showInlineCamera) Icons.Default.VideocamOff else Icons.Default.PhotoCamera,
                            contentDescription = "Live Camera",
                            tint = if (showInlineCamera) CyanNeon else TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (showInlineCamera) "STOP CAM" else "LIVE CAMERA",
                            color = if (showInlineCamera) CyanNeon else TextWhite,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // FRONT / BACK LENS SWITCH
                Surface(
                    shape = techButtonShape,
                    color = CardBg,
                    border = BorderStroke(1.2.dp, TextWhite.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .weight(0.8f)
                        .heightIn(min = 48.dp)
                        .clickable {
                            viewModel.executeHudShortcutTool("switchCameraLens") { res ->
                                val msg = res.optString("summaryBengali", "ক্যামেরা লেন্স পরিবর্তন হয়েছে")
                                inlineCameraFront = JarvisCameraManager.activeLensFacing == CameraSelector.LENS_FACING_FRONT
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("hud_switch_camera_lens")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Lens",
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (inlineCameraFront) "FRONT" else "BACK",
                            color = TextWhite,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }

                // SHARE SCREEN
                val isSharingActive = visionState != com.example.util.ScreenVisionState.STOPPED
                Surface(
                    shape = techButtonShape,
                    color = if (isSharingActive) BlueHolo.copy(alpha = 0.22f) else CardBg,
                    border = BorderStroke(1.2.dp, if (isSharingActive) BlueHolo else TextWhite.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .weight(1.1f)
                        .heightIn(min = 48.dp)
                        .clickable {
                            when (visionState) {
                                com.example.util.ScreenVisionState.STOPPED -> {
                                    viewModel.executeHudShortcutTool("startScreenSharing") { res ->
                                        if (res.optBoolean("needsScreenSharingPrompt", false)) {
                                            onRequestScreenCapture()
                                        }
                                    }
                                }
                                com.example.util.ScreenVisionState.ACTIVE,
                                com.example.util.ScreenVisionState.PAUSED -> {
                                    viewModel.executeHudShortcutTool("stopScreenSharing") { res ->
                                        val msg = res.optString("summaryBengali", "")
                                        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .testTag("hud_toggle_screen_share")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSharingActive) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                            contentDescription = "Share Screen",
                            tint = if (isSharingActive) BlueHolo else TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isSharingActive) "STOP SHARE" else "SHARE SCREEN",
                            color = if (isSharingActive) BlueHolo else TextWhite,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2. INLINE OPTICAL CAMERA VIEWPORT (Streams live frames directly to Gemini Live!)
            if (showInlineCamera && hasCameraPerm) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardBg,
                    border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.8f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(136.dp)
                        .testTag("hud_inline_camera_viewport")
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx -> PreviewView(ctx) },
                            update = { previewView ->
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                                cameraProviderFuture.addListener({
                                    try {
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        val capture = ImageCapture.Builder()
                                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                            .build()
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            try {
                                                val now = System.currentTimeMillis()
                                                if (now - lastFrameSentMs.get() >= 2000L) {
                                                    val converted = imageProxyToResizedJpegAndBitmap(imageProxy)
                                                    if (converted != null) {
                                                        lastFrameSentMs.set(now)
                                                        JarvisCameraManager.lastCapturedBitmap = converted.first
                                                        val s = currentLiveState
                                                        if (s == LiveSessionState.CONNECTED ||
                                                            s == LiveSessionState.LISTENING ||
                                                            s == LiveSessionState.SPEAKING
                                                        ) {
                                                            viewModel.sendAiImageFrame(converted.second)
                                                        }
                                                    }
                                                }
                                            } finally {
                                                imageProxy.close()
                                            }
                                        }
                                        val selector = CameraSelector.Builder()
                                            .requireLensFacing(
                                                if (inlineCameraFront) CameraSelector.LENS_FACING_FRONT
                                                else CameraSelector.LENS_FACING_BACK
                                            )
                                            .build()
                                        cameraProvider.unbindAll()
                                        val cam = cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            selector,
                                            preview,
                                            capture,
                                            analysis
                                        )
                                        JarvisCameraManager.activeCameraControl = cam.cameraControl
                                        JarvisCameraManager.activeImageCapture = capture
                                    } catch (_: Exception) {
                                    }
                                }, ContextCompat.getMainExecutor(context))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SpaceDark.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = if (inlineCameraFront) "OPTICAL • FRONT LIVE" else "OPTICAL • BACK LIVE",
                                    color = CyanNeon,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PinkNeon.copy(alpha = 0.88f),
                                modifier = Modifier.clickable { viewModel.executeHudShortcutTool("stopCameraVision") }
                            ) {
                                Text(
                                    text = "STOP",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. PENDING CONFIRMATION / AUTO-RESUME / EMERGENCY STOP BANNERS (Only when active)
            pendingApproval?.let { approval ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF14213D),
                    border = BorderStroke(1.5.dp, AmberGlow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "একবারের অনুমোদন প্রয়োজন (${approval.recipientName})",
                            color = AmberGlow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = approval.promptBn, color = TextWhite, fontSize = 11.5.sp, lineHeight = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    viewModel.approvePendingActionUi { _, msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("pending_approval_confirm_button")
                            ) {
                                Text("হ্যাঁ, সম্পন্ন করো", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.rejectPendingActionUi() },
                                border = BorderStroke(1.dp, PinkNeon),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(44.dp)
                                    .testTag("pending_approval_cancel_button")
                            ) {
                                Text("না, বাতিল", color = PinkNeon, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }

            pendingOsAction?.let { pending ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberGlow.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_pending_os_action_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "অপেক্ষমাণ কাজ (${pending.descriptionBn}) — অনুমতি দিলে স্বয়ংক্রিয়ভাবে চলবে",
                            color = TextWhite,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.cancelPendingOsAction() }) {
                            Text("বাতিল", color = PinkNeon, fontSize = 10.5.sp)
                        }
                    }
                }
            }

            lastAutoResumeResult?.let { (_, msg) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = EmeraldNeon.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, EmeraldNeon),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("পারমিশন পাওয়ার পর সম্পন্ন: $msg", color = TextWhite, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearLastAutoResumeResult() }) {
                            Text("ঠিক আছে", color = CyanNeon, fontSize = 10.5.sp)
                        }
                    }
                }
            }

            if (isEmergencyStopActive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PinkNeon.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, PinkNeon),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🛑 জরুরি বিরতি সক্রিয় — সব কাজ বন্ধ", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.resetEmergencyStop() },
                            modifier = Modifier.testTag("hud_reset_emergency_stop")
                        ) {
                            Text("রিসেট", color = CyanNeon, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 4. GREEN-BORDERED DUAL-COLUMN TRANSCRIPT CARD (JARVIS left | YOU right)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF03140E),
                border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.85f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hud_transcript_card")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRANSCRIPT",
                            color = BlueHolo,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "YOU",
                            color = BlueHolo,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 66.dp, max = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val jarvisScroll = rememberScrollState()
                        val userScroll = rememberScrollState()
                        LaunchedEffect(liveOutputTranscript) {
                            jarvisScroll.animateScrollTo(jarvisScroll.maxValue)
                        }
                        LaunchedEffect(liveInputTranscript) {
                            userScroll.animateScrollTo(userScroll.maxValue)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1.08f)
                                .verticalScroll(jarvisScroll)
                        ) {
                            Text(
                                text = liveOutputTranscript.ifBlank {
                                    statusMessage.ifBlank { "আমি প্রস্তুত, স্যার — সরাসরি কথা বলুন।" }
                                },
                                color = if (liveOutputTranscript.isNotBlank()) TextWhite else TextDim,
                                fontSize = 11.5.sp,
                                lineHeight = 15.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(58.dp)
                                .background(BorderGlow.copy(alpha = 0.65f))
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.92f)
                                .verticalScroll(userScroll),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Text(
                                text = liveInputTranscript.ifBlank { "-" },
                                color = if (liveInputTranscript.isNotBlank()) TextWhite else TextDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            // 5. SPLIT HERO CORE: [MEMORY/CHAT/SOUL/SETTINGS] === NEON BEZIER CIRCUIT === [3D SPHERE + STOP/MUTE]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT COLUMN: 4 Glowing Nav Pill Nodes
                val leftNodes = listOf(
                    Triple("MEMORY", Icons.Default.Memory, BlueHolo) to { onNavigateTab(4) },
                    Triple("CHAT", Icons.Default.ChatBubbleOutline, orangeAccent) to { onNavigateTab(1) },
                    Triple("SOUL", Icons.Default.AutoAwesome, soulMintAccent) to { viewModel.navigateTo("control_center") },
                    Triple("SETTINGS", Icons.Default.Settings, CyanNeon) to { onNavigateTab(6) }
                )

                Column(
                    modifier = Modifier
                        .weight(0.37f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    leftNodes.forEach { (meta, action) ->
                        val (label, iconVec, accent) = meta
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = CardBg,
                            border = BorderStroke(1.2.dp, accent.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 38.dp)
                                .clickable { action() }
                                .testTag("hud_circuit_btn_${label.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(accent.copy(alpha = 0.2f))
                                        .border(1.dp, accent, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = iconVec,
                                        contentDescription = label,
                                        tint = accent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = label,
                                    color = TextWhite,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // MIDDLE BEZIER CIRCUIT CONNECTOR CANVAS
                Canvas(
                    modifier = Modifier
                        .weight(0.14f)
                        .fillMaxHeight()
                ) {
                    val w = size.width
                    val h = size.height
                    val traceColors = listOf(BlueHolo, orangeAccent, soulMintAccent, CyanNeon)
                    val startYs = listOf(h * 0.14f, h * 0.36f, h * 0.58f, h * 0.80f)
                    val targetCenterY = h * 0.44f
                    val targetYs = listOf(
                        targetCenterY - 9f,
                        targetCenterY - 3f,
                        targetCenterY + 3f,
                        targetCenterY + 9f
                    )

                    traceColors.forEachIndexed { idx, traceColor ->
                        val sy = startYs[idx]
                        val ey = targetYs[idx]
                        val kneeX1 = w * (0.28f + (idx % 2) * 0.08f)
                        val kneeX2 = w * 0.68f
                        val path = Path().apply {
                            moveTo(0f, sy)
                            lineTo(kneeX1, sy)
                            lineTo(kneeX2, ey)
                            lineTo(w, ey)
                        }
                        // Subtle glow halo
                        drawPath(
                            path = path,
                            color = traceColor.copy(alpha = 0.28f),
                            style = Stroke(width = 7f, cap = StrokeCap.Round)
                        )
                        // Sharp neon core line
                        drawPath(
                            path = path,
                            color = traceColor,
                            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                        )
                        // Terminal dots
                        drawCircle(color = traceColor, radius = 4.2f, center = Offset(3f, sy))
                        drawCircle(color = traceColor, radius = 3.2f, center = Offset(w - 2f, ey))
                    }
                }

                // RIGHT COLUMN: 3D Particle Sphere + STOP & Mute Below
                Column(
                    modifier = Modifier.weight(0.49f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        JarvisArcReactorCanvas(
                            liveState = liveState,
                            micLevel = micLevel,
                            outputLevel = outputLevel,
                            modifier = Modifier
                                .size(156.dp)
                                .clickable { viewModel.toggleLiveSession() }
                                .testTag("jarvis_arc_reactor_toggle")
                        )

                        // Centered Status Chip inside bottom arc (e.g. • SPEAKING • / • LISTENING •)
                        val orbStateText = when (liveState) {
                            LiveSessionState.SPEAKING -> "• SPEAKING •"
                            LiveSessionState.LISTENING -> "• LISTENING •"
                            LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> "• THINKING •"
                            LiveSessionState.CONNECTING -> "• SYNCING •"
                            LiveSessionState.ERROR -> "• RETRY •"
                            else -> "• STANDBY •"
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SpaceDark.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp)
                        ) {
                            Text(
                                text = orbStateText,
                                color = when (liveState) {
                                    LiveSessionState.SPEAKING -> BlueHolo
                                    LiveSessionState.LISTENING -> CyanNeon
                                    LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow
                                    LiveSessionState.ERROR -> PinkNeon
                                    else -> TextWhite
                                },
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Centered STOP Pill + Speaker/Mute Button right below Sphere
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = CardBg,
                            border = BorderStroke(1.3.dp, PinkNeon),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable {
                                    val report = viewModel.triggerEmergencyStop()
                                    Toast.makeText(context, report.summaryBengali, Toast.LENGTH_SHORT).show()
                                }
                                .testTag("hud_emergency_stop_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(PinkNeon)
                                )
                                Text(
                                    text = "STOP",
                                    color = TextWhite,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.triggerInstantBargeIn("hud_speaker_mute") },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(BlueHolo.copy(alpha = 0.14f))
                                .border(1.2.dp, BlueHolo.copy(alpha = 0.75f), CircleShape)
                                .testTag("hud_stop_speech_button")
                        ) {
                            Icon(
                                imageVector = if (liveState == LiveSessionState.SPEAKING) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = "Stop or Mute Speech",
                                tint = BlueHolo,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // 6. COMPACT PERSONALITY, EMOTION & ACTIVE TASK STRIP (keeps Stonic Companion & Interruption intact!)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .clickable { showEmotionPicker = true }
                            .testTag("emotion_badge_button")
                    ) {
                        Text(
                            text = "♥ ${currentEmotion.emotion.labelBengali}",
                            color = BlueHolo,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                items(JarvisPersonalityMode.entries) { mode ->
                    val selected = activePersonality == mode
                    val shortLabel = when (mode) {
                        JarvisPersonalityMode.ROMANTIC -> "রোমান্টিক"
                        JarvisPersonalityMode.BEST_FRIEND -> "বেস্ট ফ্রেন্ড"
                        JarvisPersonalityMode.EMOTIONAL_COMFORT -> "সান্ত্বনা"
                        JarvisPersonalityMode.PROFESSIONAL -> "প্রফেশনাল"
                        JarvisPersonalityMode.PLAYFUL -> "প্লেফুল"
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) CyanNeon.copy(alpha = 0.22f) else SpaceSurface,
                        border = BorderStroke(1.dp, if (selected) CyanNeon else BorderGlow),
                        modifier = Modifier
                            .clickable { viewModel.setPersonalityMode(mode) }
                            .testTag("personality_chip_${mode.id}")
                    ) {
                        Text(
                            text = shortLabel,
                            color = if (selected) CyanNeon else TextDim,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (activeTask != null || liveState == LiveSessionState.SPEAKING) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BlueHolo.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, BlueHolo),
                            modifier = Modifier
                                .clickable { viewModel.triggerInstantBargeIn("hud_barge_in") }
                                .testTag("hud_barge_in_button")
                        ) {
                            Text(
                                text = "কথা থামাও",
                                color = BlueHolo,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                activeTask?.let { t ->
                    item {
                        val isPaused = t.status == TaskStatus.PAUSED
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AmberGlow.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, AmberGlow),
                            modifier = Modifier
                                .clickable {
                                    if (isPaused) viewModel.resumeCurrentTask() else viewModel.pauseCurrentTask()
                                }
                                .testTag("hud_pause_resume_task_button")
                        ) {
                            Text(
                                text = if (isPaused) "চালু করো" else "বিরতি",
                                color = AmberGlow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PinkNeon.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, PinkNeon),
                            modifier = Modifier
                                .clickable { viewModel.cancelCurrentTask() }
                                .testTag("hud_cancel_task_button")
                        ) {
                            Text(
                                text = "টাস্ক বাতিল",
                                color = PinkNeon,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 7. SIDE-BY-SIDE BOTTOM HERO CARDS: [TODAY HEADLINES >]  &  [TODAY TASKS • X left]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // LEFT: TODAY HEADLINES > (with vertical cyan indicator bars + "+X MORE")
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, BorderGlow.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .clickable { showHeadlinesDialog = true }
                        .testTag("hud_today_headlines_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TODAY HEADLINES",
                                color = TextWhite,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = ">",
                                color = BlueHolo,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        if (isNewsLoading) {
                            Text("সংবাদ লোড হচ্ছে...", color = TextDim, fontSize = 10.sp)
                        } else if (liveNews.isEmpty()) {
                            Text(
                                text = newsError ?: "লাইভ নিউজ পেতে স্পর্শ করুন",
                                color = TextDim,
                                fontSize = 10.sp,
                                maxLines = 2
                            )
                        } else {
                            val shownHeadlines = liveNews.take(3)
                            shownHeadlines.forEach { item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .height(14.dp)
                                            .background(BlueHolo, RoundedCornerShape(2.dp))
                                    )
                                    Text(
                                        text = item.title,
                                        color = TextWhite,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            val remainingCount = (liveNews.size - shownHeadlines.size).coerceAtLeast(0)
                            if (remainingCount > 0) {
                                Text(
                                    text = "+$remainingCount MORE",
                                    color = BlueHolo,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

                // RIGHT: TODAY TASKS • X left + Cyan Progress Bar + Real Task Bullets
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF07151A),
                    border = BorderStroke(1.2.dp, BorderGlow.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .clickable { onNavigateTab(5) }
                        .testTag("hud_today_tasks_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TODAY TASKS • $pendingTasksCount left",
                                color = TextWhite,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = ">",
                                color = BlueHolo,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Cyan Glowing Progress Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF132D35))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (aiTasks.isEmpty()) 0.25f else taskCompletionProgress)
                                    .fillMaxHeight()
                                    .background(BlueHolo, RoundedCornerShape(4.dp))
                            )
                        }

                        if (pendingTasksList.isEmpty()) {
                            Text(
                                text = "✓ কোনো পেন্ডিং টাস্ক নেই (ট্যাপ করে নতুন টাস্ক যোগ করুন)",
                                color = CyanNeon.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 2,
                                lineHeight = 13.sp
                            )
                        } else {
                            pendingTasksList.take(3).forEach { task ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(BlueHolo)
                                    )
                                    Text(
                                        text = task.title,
                                        color = TextWhite,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 8. COMPACT BOTTOM MODULE ACCESS & COMMAND INPUT DOCK
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 6.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val homeDevState by viewModel.homeDevState.collectAsState()
            val devDotColor = Color(homeDevState.indicatorState.colorHex)

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, devDotColor.copy(alpha = 0.75f)),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("home_developer") }
                            .testTag("hud_home_developer_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(devDotColor)
                            )
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "HOME DEVELOPER",
                                tint = devDotColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text("HOME DEV", color = TextWhite, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier.clickable { onNavigateTab(2) }
                    ) {
                        Text("🤖 এজেন্টস", color = CyanNeon, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier.clickable { onNavigateTab(3) }
                    ) {
                        Text("🛠️ টুলস", color = BlueHolo, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier.clickable { onNavigateTab(7) }
                    ) {
                        Text("📄 আর্টিফ্যাক্ট", color = AmberGlow, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier.clickable { onOpenDialPad() }
                    ) {
                        Text("📞 কল", color = TextWhite, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier.clickable { onOpenWhatsApp() }
                    ) {
                        Text("💬 হোয়াটসঅ্যাপ", color = EmeraldNeon, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("profile") }
                            .testTag("hud_quick_profile")
                    ) {
                        Text("👤 প্রোফাইল", color = TextWhite, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("notepad") }
                            .testTag("hud_quick_notepad")
                    ) {
                        Text("📝 নোটস", color = TextWhite, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, BorderGlow),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("firebase_backup") }
                            .testTag("hud_quick_vault")
                    ) {
                        Text("🔒 ভল্ট", color = EmeraldNeon, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { viewModel.navigateTo("camera") },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SpaceSurface)
                        .border(1.dp, CyanNeon.copy(alpha = 0.45f), CircleShape)
                        .testTag("hud_open_camera_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Full Camera Vision",
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextInputChange,
                    placeholder = { Text("নাহিদ এআই-কে বলুন বা লিখুন...", fontSize = 12.sp, color = TextDim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendMessage() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = BorderGlow,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = SpaceSurface,
                        unfocusedContainerColor = SpaceSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("hud_text_input")
                )

                IconButton(
                    onClick = onSendMessage,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (textInput.isNotBlank()) CyanNeon else SpaceSurface)
                        .border(1.dp, if (textInput.isNotBlank()) CyanNeon else BorderGlow, CircleShape)
                        .testTag("hud_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (textInput.isNotBlank()) SpaceDark else TextDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // INFORMATION PANEL DIALOG (Opens on tap OR automatically on voice news queries!)
    if (showHeadlinesDialog) {
        AlertDialog(
            onDismissRequest = { showHeadlinesDialog = false },
            containerColor = Color(0xFF0A1412),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Information panel",
                            color = TextWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "LIVE HEADLINES • ${liveNews.size} ARTICLES",
                            color = CyanNeon,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { viewModel.refreshLiveNews(category = "সব", forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh News", tint = BlueHolo)
                    }
                }
            },
            text = {
                if (isNewsLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CyanNeon, strokeWidth = 2.dp)
                        Text("BBC বাংলা ও লাইভ ফিড থেকে সংবাদ আনা হচ্ছে...", color = TextDim, fontSize = 12.sp)
                    }
                } else if (liveNews.isEmpty()) {
                    Text(
                        text = newsError ?: "বর্তমানে কোনো লাইভ সংবাদ পাওয়া যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।",
                        color = AmberGlow,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(liveNews) { news ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SpaceSurface,
                                border = BorderStroke(1.dp, BorderGlow),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (news.url.isNotBlank()) {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(news.url)))
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = news.title,
                                        color = TextWhite,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${news.source} • ${news.date}",
                                            color = BlueHolo,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        if (news.url.isNotBlank()) {
                                            Text(
                                                text = "বিস্তারিত >",
                                                color = CyanNeon,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (news.summary.isNotBlank() && news.summary != news.title) {
                                        Text(
                                            text = news.summary,
                                            color = TextDim,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHeadlinesDialog = false }) {
                    Text("বন্ধ করুন", color = CyanNeon, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showEmotionPicker) {
        AlertDialog(
            onDismissRequest = { showEmotionPicker = false },
            containerColor = CardBg,
            title = { Text("আপনার বর্তমান অনুভূতি নির্বাচন করুন", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        UserEmotion.HAPPY,
                        UserEmotion.CALM,
                        UserEmotion.SAD,
                        UserEmotion.STRESSED,
                        UserEmotion.TIRED,
                        UserEmotion.LONELY,
                        UserEmotion.ANGRY
                    ).forEach { emotion ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SpaceSurface,
                            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.correctUserEmotion(emotion)
                                    showEmotionPicker = false
                                }
                        ) {
                            Text(
                                text = emotion.labelBengali,
                                color = TextWhite,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEmotionPicker = false }) {
                    Text("বন্ধ", color = TextDim)
                }
            }
        )
    }
}

// =================================================================
// TAB 2: AI LONG-TERM MEMORY TAB
// =================================================================
@Composable
private fun JarvisMemoryTab(
    memories: List<AiMemoryEntity>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onAddMemoryClick: () -> Unit,
    onEditMemory: (AiMemoryEntity) -> Unit,
    onDeleteMemory: (AiMemoryEntity) -> Unit
) {
    val categories = listOf("ALL", "USER_PREFERENCE", "SYSTEM_RULE", "AUTOMATION_DIRECTIVE", "AI_BEHAVIOR")

    val filtered = remember(memories, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        memories.filter { mem ->
            val matchCategory = selectedCategory == "ALL" || mem.category.equals(selectedCategory, ignoreCase = true)
            val matchQuery = q.isBlank() || mem.key.lowercase().contains(q) || mem.content.lowercase().contains(q)
            matchCategory && matchQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Banner card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardBg,
            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = CyanNeon,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "লং-টার্ম মেমোরি সিস্টেম",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "এখানে সংরক্ষিত তথ্য জারভিস স্থায়ীভাবে স্মরণে রাখবে এবং পরবর্তীতে সেই নিয়ম ও তথ্য মেনে কথা বলবে।",
                        fontSize = 11.5.sp,
                        color = TextDim,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Search Bar & Add Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("মেমোরিতে খুঁজুন...", fontSize = 12.sp, color = TextDim) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextDim) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanNeon,
                    unfocusedBorderColor = BorderGlow,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            )

            Button(
                onClick = onAddMemoryClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = SpaceDark)
                Spacer(modifier = Modifier.width(4.dp))
                Text("নতুন", color = SpaceDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Category Filter Chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                val label = when (cat) {
                    "ALL" -> "সব মেমোরি"
                    "USER_PREFERENCE" -> "পছন্দ"
                    "SYSTEM_RULE" -> "সিস্টেম নিয়ম"
                    "AUTOMATION_DIRECTIVE" -> "অটোমেশন নির্দেশিকা"
                    "AI_BEHAVIOR" -> "আচরণ"
                    else -> cat
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) CyanNeon else SpaceSurface,
                    border = BorderStroke(1.dp, if (isSelected) CyanNeon else Color(0xFF334155)),
                    modifier = Modifier.clickable { onSelectCategory(cat) }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) SpaceDark else TextDim
                    )
                }
            }
        }

        // Memory Items List
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PsychologyAlt,
                        contentDescription = null,
                        tint = TextDim.copy(alpha = 0.4f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "কোনো মেমোরি পাওয়া যায়নি",
                        color = TextDim,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "জারভিসকে মুখে বলুন 'মনে রাখো...' অথবা + নতুন বাটনে চাপুন।",
                        color = TextDim.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { memory ->
                    MemoryCardItem(
                        memory = memory,
                        onEdit = { onEditMemory(memory) },
                        onDelete = { onDeleteMemory(memory) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCardItem(
    memory: AiMemoryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBg,
        border = BorderStroke(1.dp, if (memory.isPinned) CyanNeon.copy(alpha = 0.5f) else Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (memory.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = AmberGlow,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = memory.key,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White.copy(alpha = 0.06f)
                ) {
                    Text(
                        text = memory.category,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanNeon
                    )
                }
            }

            Text(
                text = memory.content,
                fontSize = 12.5.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 17.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(memory.updatedAt)),
                    fontSize = 10.sp,
                    color = TextDim
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = CyanNeon, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = PinkNeon, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// =================================================================
// TAB 3: AUTONOMOUS AI TASKS TAB
// =================================================================
@Composable
private fun JarvisTasksTab(
    tasks: List<AiTaskEntity>,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    onAddTaskClick: () -> Unit,
    onCompleteTask: (Long) -> Unit,
    onDeleteTask: (AiTaskEntity) -> Unit
) {
    val filtered = remember(tasks, selectedFilter) {
        when (selectedFilter) {
            "PENDING" -> tasks.filter { !it.isExecuted }
            "COMPLETED" -> tasks.filter { it.isExecuted }
            else -> tasks
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Banner explaining automated task scheduling
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardBg,
            border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = AmberGlow,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "স্বয়ংক্রিয় টাস্ক ও শিডিউলার",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "এখানে যেকোনো কাজের সময় নির্ধারণ করে রাখলে জারভিস নির্দিষ্ট সময়ে স্বয়ংক্রিয়ভাবে নোটিফিকেশন প্রদান বা কাজ সম্পন্ন করবে।",
                        fontSize = 11.5.sp,
                        color = TextDim,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Filter Tabs & Add Task Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedFilter == "PENDING") AmberGlow else SpaceSurface,
                    modifier = Modifier.clickable { onSelectFilter("PENDING") }
                ) {
                    Text(
                        text = "চলমান (${tasks.count { !it.isExecuted }})",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedFilter == "PENDING") SpaceDark else TextDim
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedFilter == "COMPLETED") Color(0xFF10B981) else SpaceSurface,
                    modifier = Modifier.clickable { onSelectFilter("COMPLETED") }
                ) {
                    Text(
                        text = "সম্পন্ন (${tasks.count { it.isExecuted }})",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedFilter == "COMPLETED") SpaceDark else TextDim
                    )
                }
            }

            Button(
                onClick = onAddTaskClick,
                colors = ButtonDefaults.buttonColors(containerColor = AmberGlow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = SpaceDark)
                Spacer(modifier = Modifier.width(4.dp))
                Text("নতুন টাস্ক", color = SpaceDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
        }

        // Tasks List
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.EventAvailable,
                        contentDescription = null,
                        tint = TextDim.copy(alpha = 0.4f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (selectedFilter == "PENDING") "কোনো চলমান টাস্ক নেই" else "কোনো সম্পন্ন টাস্ক নেই",
                        color = TextDim,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { task ->
                    TaskCardItem(
                        task = task,
                        onComplete = { onCompleteTask(task.id) },
                        onDelete = { onDeleteTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCardItem(
    task: AiTaskEntity,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBg,
        border = BorderStroke(
            1.dp,
            if (task.isExecuted) Color(0xFF10B981).copy(alpha = 0.3f) else AmberGlow.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (task.isExecuted) Icons.Default.CheckCircle else Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (task.isExecuted) Color(0xFF10B981) else AmberGlow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = task.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (task.priority == "URGENT") PinkNeon.copy(alpha = 0.15f) else AmberGlow.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = task.priority,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (task.priority == "URGENT") PinkNeon else AmberGlow
                    )
                }
            }

            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = TextDim, modifier = Modifier.size(12.dp))
                    Text(
                        text = task.scheduledTimeString.ifBlank { "শিডিউলকৃত" },
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDim
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!task.isExecuted) {
                        Button(
                            onClick = onComplete,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Done, contentDescription = null, tint = SpaceDark, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("সম্পন্ন", fontSize = 11.sp, color = SpaceDark, fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = PinkNeon, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// =================================================================
// TAB 4: JARVIS CORE & BACKGROUND SETTINGS TAB
// =================================================================
@Composable
private fun JarvisCoreSettingsTab(
    viewModel: AssistantViewModel,
    isBackgroundActive: Boolean,
    settings: com.example.ai.AiSettingsState,
    onOpenFullSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Continuous Background Mode Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isBackgroundActive) Color(0xFF064E3B) else CardBg,
            border = BorderStroke(1.5.dp, if (isBackgroundActive) CyanNeon else Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = if (isBackgroundActive) CyanNeon else TextWhite,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "সার্বক্ষণিক ব্যাকগ্রাউন্ড মোড",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = if (isBackgroundActive) "সক্রিয় — মাইক্রোফোন সার্বক্ষণিক শুনছে" else "নিষ্ক্রিয়",
                                fontSize = 11.sp,
                                color = if (isBackgroundActive) CyanNeon else TextDim
                            )
                        }
                    }

                    Switch(
                        checked = isBackgroundActive,
                        onCheckedChange = { viewModel.toggleBackgroundMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SpaceDark,
                            checkedTrackColor = CyanNeon
                        )
                    )
                }

                Text(
                    text = "🔒 এই অপশন চালু করলে মাইক্রোফোন ব্যাকগ্রাউন্ডে সক্রিয় থাকবে। আপনি যখন যা বলবেন নাহিদ এআই তা শুনে স্বয়ংক্রিয়ভাবে কাজ করতে পারবে — নোটিফিকেশন থেকেও পজ/স্টপ করা যাবে।",
                    fontSize = 11.5.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )
            }
        }

        // Hands-Free Wake Word & Default System Assistant Card
        val ctx = LocalContext.current
        val wakeOptIn by viewModel.wakeWordOptIn.collectAsState()
        val wakeCaps = remember { com.example.audio.WakeWordModelManager.inspectCapabilities(ctx) }
        val roleStatus = remember { com.example.service.JarvisAssistantRoleManager.getStatus(ctx) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardBg,
            border = BorderStroke(1.dp, if (wakeOptIn) CyanNeon.copy(alpha = 0.6f) else Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ওয়েক-ওয়ার্ড ও ডিফল্ট অ্যাসিস্ট্যান্ট",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "${wakeCaps.engineModeLabel} • ${settings.modelName}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanNeon
                        )
                    }
                    Switch(
                        checked = wakeOptIn,
                        onCheckedChange = { enabled ->
                            viewModel.setWakeWordOptIn(enabled)
                            if (enabled) {
                                com.example.service.JarvisWakeWordService.start(ctx)
                            } else {
                                com.example.service.JarvisWakeWordService.stop(ctx)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SpaceDark,
                            checkedTrackColor = CyanNeon
                        )
                    )
                }
                Text(
                    text = roleStatus.statusBengali,
                    fontSize = 11.sp,
                    color = if (roleStatus.isDefaultAssistant) EmeraldNeon else AmberGlow
                )
                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(com.example.service.JarvisAssistantRoleManager.createSelectAssistantIntent(ctx))
                        } catch (_: Exception) {
                        }
                    },
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Assistant, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("সিস্টেম ডিফল্ট অ্যাসিস্ট্যান্ট সেট করুন", fontSize = 12.sp, color = BlueHolo, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Persona Identity Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardBg,
            border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BlueHolo)
                    Text(
                        text = "Jarvis AI Persona & Capability",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                Text(
                    text = "• Iron Man Jarvis OS: অত্যন্ত বুদ্ধিমান, অনুগত ও সম্মানজনক বাংলা কণ্ঠ।\n• Automation Engine: WhatsApp, YouTube, Facebook ও সিস্টেম অ্যাপের পূর্ণ নিয়ন্ত্রণ।\n• Optical Perception: স্ক্রিন রিডিং, ক্লিক ও লাইভ ভিজ্যুয়াল বিশ্লেষণ।\n• External Intelligence: সাধারণ জ্ঞান, আবহাওয়া, উইকিপিডিয়া ও লাইভ লোকেশন।",
                    fontSize = 11.5.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 17.sp
                )
            }
        }

        // Voice Engine & API Config Button
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardBg,
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ভয়েস ও API কনফিগারেশন",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = settings.maskedApiKey,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanNeon
                    )
                }

                Button(
                    onClick = onOpenFullSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = CyanNeon)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "API Key, Voice Model ও সেটিংস পরিবর্তন",
                        fontSize = 12.5.sp,
                        color = CyanNeon,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = { viewModel.navigateTo("control_center") },
                    colors = ButtonDefaults.buttonColors(containerColor = PinkNeon.copy(alpha = 0.16f)),
                    border = BorderStroke(1.dp, PinkNeon.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_open_control_center_button")
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = PinkNeon)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NAHID AI কন্ট্রোল সেন্টার (চরিত্র ও ইমোশন স্টুডিও)",
                        fontSize = 12.5.sp,
                        color = PinkNeon,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.navigateTo("profile") },
                        border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ওনার প্রোফাইল", fontSize = 12.sp, color = CyanNeon, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo("firebase_backup") },
                        border = BorderStroke(1.dp, EmeraldNeon.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ব্যাকআপ ভল্ট", fontSize = 12.sp, color = EmeraldNeon, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// =================================================================
// 4. CANVAS VISUALIZERS: ARC REACTOR & WAVEFORM
// =================================================================
@Composable
private fun JarvisArcReactorCanvas(
    liveState: LiveSessionState,
    micLevel: Float,
    outputLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ArcReactorRotation")

    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRing"
    )

    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRing"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reactorPulse"
    )

    val energyLevel = (micLevel * 1.5f).coerceAtLeast(outputLevel * 1.8f).coerceIn(0f, 1f)

    val primaryColor = when (liveState) {
        LiveSessionState.SPEAKING -> BlueHolo
        LiveSessionState.LISTENING -> CyanNeon
        LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow
        LiveSessionState.ERROR -> PinkNeon
        else -> CyanNeon
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2 * 0.94f

        // 1. Outermost Holographic Ambient Radial Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BlueHolo.copy(alpha = 0.22f + energyLevel * 0.24f),
                    CyanNeon.copy(alpha = 0.14f + energyLevel * 0.14f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 1.1f
            ),
            radius = maxRadius * 1.1f,
            center = center
        )

        // 2. Crisp Outer Sphere Halo Ring
        drawCircle(
            color = primaryColor.copy(alpha = 0.28f),
            radius = maxRadius * 0.90f,
            center = center,
            style = Stroke(width = 1.5f)
        )

        // 3. Tilted Elliptical Orbital Rings (Archer 3D gyroscopic orbits)
        rotate(degrees = 26f + (outerAngle * 0.15f), pivot = center) {
            drawOval(
                color = CyanNeon.copy(alpha = 0.72f),
                topLeft = Offset(center.x - maxRadius * 0.90f, center.y - maxRadius * 0.34f),
                size = Size(maxRadius * 1.80f, maxRadius * 0.68f),
                style = Stroke(width = 1.8f)
            )
        }
        rotate(degrees = -34f - (innerAngle * 0.12f), pivot = center) {
            drawOval(
                color = BlueHolo.copy(alpha = 0.72f),
                topLeft = Offset(center.x - maxRadius * 0.88f, center.y - maxRadius * 0.30f),
                size = Size(maxRadius * 1.76f, maxRadius * 0.60f),
                style = Stroke(width = 1.6f)
            )
        }

        // 4. 3D Rotating Fibonacci Particle Energy Sphere (220 golden-angle sphere nodes)
        //    Left edge shades warm yellow-gold -> emerald -> right electric cyan!
        val sphereRadius = maxRadius * 0.72f * pulseScale * (1f + energyLevel * 0.2f)
        val rotY = Math.toRadians(outerAngle.toDouble())
        val rotX = Math.toRadians((innerAngle * 0.4f).toDouble())
        val cosY = cos(rotY)
        val sinY = sin(rotY)
        val cosX = cos(rotX)
        val sinX = sin(rotX)
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        val particleCount = 220

        data class SpherePoint(val px: Float, val py: Float, val pz: Float, val rxNorm: Float)
        val projected = ArrayList<SpherePoint>(particleCount)

        for (i in 0 until particleCount) {
            val y = 1.0 - (i / (particleCount - 1).toDouble()) * 2.0
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = goldenAngle * i
            val x = cos(theta) * r
            val z = sin(theta) * r

            val rx = x * cosY - z * sinY
            val rz1 = x * sinY + z * cosY
            val ry = y * cosX - rz1 * sinX
            val rz = y * sinX + rz1 * cosX

            projected.add(
                SpherePoint(
                    px = (center.x + rx * sphereRadius).toFloat(),
                    py = (center.y + ry * sphereRadius).toFloat(),
                    pz = rz.toFloat(),
                    rxNorm = ((rx.toFloat() + 1f) * 0.5f).coerceIn(0f, 1f)
                )
            )
        }
        projected.sortBy { it.pz }

        for (pt in projected) {
            val depthNorm = ((pt.pz + 1f) * 0.5f).coerceIn(0f, 1f)
            val dotRadius = (1.5f + depthNorm * (2.7f + energyLevel * 2.0f))
            val dotAlpha = (0.28f + depthNorm * 0.72f).coerceIn(0.2f, 1f)
            val dotColor = when {
                liveState == LiveSessionState.ERROR -> PinkNeon
                pt.rxNorm < 0.28f -> AmberGlow
                pt.rxNorm < 0.56f -> CyanNeon
                else -> BlueHolo
            }
            drawCircle(
                color = dotColor.copy(alpha = dotAlpha),
                radius = dotRadius,
                center = Offset(pt.px, pt.py)
            )
        }

        // 5. Soft Cyan-Emerald Energy Core Glow
        val coreRadius = maxRadius * 0.34f * pulseScale * (1f + energyLevel * 0.25f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    BlueHolo.copy(alpha = 0.52f),
                    CyanNeon.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = center,
                radius = coreRadius
            ),
            radius = coreRadius,
            center = center
        )
    }
}

@Composable
private fun AudioWaveformDisplay(
    liveState: LiveSessionState,
    micLevel: Float,
    outputLevel: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 20
    val activeLevel = (micLevel * 1.8f).coerceAtLeast(outputLevel * 2.0f).coerceIn(0.05f, 1f)

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2)
        val midY = size.height / 2

        for (i in 0 until barCount) {
            val factor = sin((i.toDouble() / barCount) * Math.PI).toFloat()
            val height = (size.height * 0.8f * factor * activeLevel).coerceAtLeast(4f)
            val x = i * barWidth * 2 + barWidth / 2

            val color = when (liveState) {
                LiveSessionState.SPEAKING -> BlueHolo
                LiveSessionState.LISTENING -> CyanNeon
                LiveSessionState.THINKING -> AmberGlow
                else -> Color(0xFF334155)
            }

            drawLine(
                color = color,
                start = Offset(x, midY - height / 2),
                end = Offset(x, midY + height / 2),
                strokeWidth = barWidth * 0.85f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun PulsingStatusDot(liveState: LiveSessionState) {
    val color = when (liveState) {
        LiveSessionState.CONNECTED -> Color(0xFF10B981)
        LiveSessionState.LISTENING -> CyanNeon
        LiveSessionState.SPEAKING -> BlueHolo
        LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow
        LiveSessionState.ERROR -> PinkNeon
        else -> Color(0xFF64748B)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// =================================================================
// 5. DIALOGS: ADD/EDIT MEMORY & ADD TASK
// =================================================================
@Composable
private fun AddOrEditMemoryDialog(
    initialKey: String,
    initialContent: String,
    initialCategory: String,
    initialImportance: Int,
    title: String,
    onDismiss: () -> Unit,
    onSave: (key: String, content: String, category: String, importance: Int) -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }
    var content by remember { mutableStateOf(initialContent) }
    var category by remember { mutableStateOf(initialCategory) }
    var importance by remember { mutableIntStateOf(initialImportance) }

    val categories = listOf("USER_PREFERENCE", "SYSTEM_RULE", "AUTOMATION_DIRECTIVE", "AI_BEHAVIOR", "GENERAL")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("কীওয়ার্ড / শিরোনাম (যেমন: ডিসকাউন্ট নিয়ম)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = BorderGlow,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("বিস্তারিত মেমোরি / নির্দেশনা") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = BorderGlow,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("ক্যাটেগরি:", fontSize = 11.5.sp, color = TextDim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        val isSel = category == cat
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) CyanNeon else SpaceSurface,
                            modifier = Modifier.clickable { category = cat }
                        ) {
                            Text(
                                text = cat,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) SpaceDark else TextDim
                            )
                        }
                    }
                }
            }
        },
        containerColor = CardBg,
        confirmButton = {
            Button(
                onClick = {
                    if (key.isNotBlank() && content.isNotBlank()) {
                        onSave(key.trim(), content.trim(), category, importance)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                enabled = key.isNotBlank() && content.isNotBlank()
            ) {
                Text("সংরক্ষণ করুন", color = SpaceDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল", color = TextDim)
            }
        }
    )
}

@Composable
private fun AddAiTaskDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, desc: String, scheduledTime: Long, timeString: String, priority: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedPresetMinutes by remember { mutableLongStateOf(30L) }
    var priority by remember { mutableStateOf("NORMAL") }

    val presets = listOf(
        "১৫ মিনিট পর" to 15L,
        "৩০ মিনিট পর" to 30L,
        "১ ঘণ্টা পর" to 60L,
        "৩ ঘণ্টা পর" to 180L,
        "২৪ ঘণ্টা পর" to 1440L
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("নতুন এআই টাস্ক শিডিউল", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("টাস্কের শিরোনাম (যেমন: স্টক চেক)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberGlow,
                        unfocusedBorderColor = BorderGlow,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("বিস্তারিত কাজের বিবরণ") },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberGlow,
                        unfocusedBorderColor = BorderGlow,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("কখন কার্যকর হবে (Presets):", fontSize = 11.5.sp, color = TextDim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presets) { (label, mins) ->
                        val isSel = selectedPresetMinutes == mins
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) AmberGlow else SpaceSurface,
                            modifier = Modifier.clickable { selectedPresetMinutes = mins }
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) SpaceDark else TextDim
                            )
                        }
                    }
                }
            }
        },
        containerColor = CardBg,
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val scheduledTime = System.currentTimeMillis() + (selectedPresetMinutes * 60 * 1000L)
                        val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(scheduledTime))
                        onSave(title.trim(), description.trim(), scheduledTime, timeStr, priority)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberGlow),
                enabled = title.isNotBlank()
            ) {
                Text("শিডিউল করুন", color = SpaceDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল", color = TextDim)
            }
        }
    )
}

@Composable
private fun TelemetryChip(
    label: String,
    active: Boolean,
    warning: Boolean = false
) {
    val chipColor = when {
        warning -> AmberGlow
        active -> EmeraldNeon
        else -> TextDim
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SpaceSurface.copy(alpha = 0.8f),
        border = BorderStroke(0.5.dp, chipColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(chipColor, CircleShape)
            )
            Text(
                text = label,
                fontSize = 9.sp,
                color = chipColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

