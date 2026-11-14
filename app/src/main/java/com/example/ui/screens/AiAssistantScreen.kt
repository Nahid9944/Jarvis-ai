package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ai.AiSettingsRepository
import com.example.ai.JarvisPersonalityMode
import com.example.ai.LiveSessionState
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
import com.example.util.JarvisDeviceAutomationHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

// Sci-Fi Hologram JARVIS Palette
private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val PinkNeon = Color(0xFFF15BB5)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)
private val EmeraldNeon = Color(0xFF10B981)
private val BorderGlow = Color(0xFF1E3A8A)

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
    val statusMessage by viewModel.aiStatusMessage.collectAsState()
    val micLevel by viewModel.aiMicLevel.collectAsState()
    val outputLevel by viewModel.aiOutputLevel.collectAsState()
    val settings by viewModel.aiSettings.collectAsState()
    val isBackgroundActive by viewModel.isBackgroundModeActive.collectAsState()

    val memories by viewModel.aiMemories.collectAsState()
    val tasks by viewModel.aiTasks.collectAsState()
    val chatHistory by viewModel.jarvisChatHistory.collectAsState()
    val unreadNotifCount by viewModel.unreadNotificationCount.collectAsState()

    // 0: Live Voice / HUD, 1: Holographic History, 2: Agents, 3: Tools, 4: Memories, 5: Tasks, 6: Settings
    var selectedTab by remember { mutableIntStateOf(0) }

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
                        step.summaryBengali.contains("Accessibility", ignoreCase = true)
                }
                val needsLocation = allStepOutputs.any { step ->
                    (step.status == "PERMISSION_REQUIRED" && step.summaryBengali.contains("LOCATION", ignoreCase = true)) ||
                        (step.toolName == "getCurrentLocation" && step.status != "SUCCEEDED")
                }
                val needsScreen = allStepOutputs.any { step ->
                    (step.status == "PERMISSION_REQUIRED" && step.summaryBengali.contains("Projection", ignoreCase = true)) ||
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
                    "PARTIAL_SUCCESS" -> "PARTIAL_SUCCESS"
                    "EMERGENCY_STOPPED" -> "CANCELLED"
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

    // Listen to background speech recognition wake-word events
    LaunchedEffect(Unit) {
        com.example.service.JarvisWakeWordService.wakeWordEvents.collect { spokenText ->
            if (spokenText.isNotBlank()) {
                handleUserCommand(spokenText)
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
        onDispose {
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
            // Telemetry Status Strip - shown on secondary tabs for detailed stats, kept off Live Home tab for clean, breathable HUD
            if (selectedTab != 0) {
                TelemetryStatsStrip(
                    activeAgentName = activeAgent.titleBengali,
                    memoryCount = memories.size,
                    pendingTasksCount = tasks.count { !it.isExecuted },
                    isBackgroundActive = isBackgroundActive,
                    liveState = liveState
                )
            }

            // Sci-Fi Hologram Navigation Tabs
            JarvisNavTabBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it }
            )

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
        title = {
            Column(modifier = Modifier.padding(start = 2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "NAHID AI",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Black,
                        color = CyanNeon,
                        letterSpacing = 0.6.sp,
                        maxLines = 1
                    )
                    Surface(
                        shape = CircleShape,
                        color = when (liveState) {
                            LiveSessionState.LISTENING -> CyanNeon.copy(alpha = 0.2f)
                            LiveSessionState.SPEAKING -> BlueHolo.copy(alpha = 0.2f)
                            LiveSessionState.ERROR -> PinkNeon.copy(alpha = 0.2f)
                            else -> Color(0xFF1E293B)
                        },
                        border = BorderStroke(1.dp, when (liveState) {
                            LiveSessionState.LISTENING -> CyanNeon
                            LiveSessionState.SPEAKING -> BlueHolo
                            LiveSessionState.ERROR -> PinkNeon
                            else -> Color(0xFF475569)
                        })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            PulsingStatusDot(liveState = liveState)
                            Text(
                                text = when (liveState) {
                                    LiveSessionState.CONNECTED -> "ONLINE"
                                    LiveSessionState.LISTENING -> "LIVE"
                                    LiveSessionState.SPEAKING -> "VOICE"
                                    LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> "PROCESSING"
                                    LiveSessionState.CONNECTING -> "CONNECTING"
                                    LiveSessionState.ERROR -> "ERROR"
                                    LiveSessionState.DISCONNECTED -> "STANDBY"
                                },
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (liveState) {
                                    LiveSessionState.LISTENING -> CyanNeon
                                    LiveSessionState.SPEAKING -> BlueHolo
                                    else -> TextDim
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
                Text(
                    text = "মায়া (Maya) • Personal AI Operating System",
                    fontSize = 10.5.sp,
                    color = TextDim,
                    maxLines = 1
                )
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp)
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
        actions = {
            // Combined OS / BG Status Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isAccessibilityActive) CyanNeon.copy(alpha = 0.15f) else AmberGlow.copy(alpha = 0.15f),
                border = BorderStroke(
                    1.dp,
                    if (isAccessibilityActive) CyanNeon.copy(alpha = 0.6f) else AmberGlow.copy(alpha = 0.7f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenAccessibilityPrompt() }
                    .padding(end = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.AccessibilityNew,
                        contentDescription = "OS Accessibility Status",
                        tint = if (isAccessibilityActive) CyanNeon else AmberGlow,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (isAccessibilityActive) "OS অন" else "OS পারমিশন",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAccessibilityActive) CyanNeon else AmberGlow
                    )
                }
            }

            // Notification Intelligence Bell Button
            Box(modifier = Modifier.padding(end = 2.dp)) {
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
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (unreadNotificationCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(PinkNeon)
                    )
                }
            }

            // Maya Companion Control Center Button
            IconButton(
                onClick = onOpenControlCenter,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SpaceSurface)
                    .testTag("open_control_center_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Maya Control Center",
                    tint = PinkNeon,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Permission Center Shield Button
            IconButton(
                onClick = onOpenPermissionCenter,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SpaceSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Permissions Center",
                    tint = CyanNeon,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Settings & Controls Button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SpaceSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextWhite,
                    modifier = Modifier.size(18.dp)
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
        "⚙️ সেটিংস" to 6
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
// TAB 1: JARVIS LIVE HUD
// =================================================================
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
    onRequestScreenCapture: () -> Unit = {},
    onStopScreenCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    val screenTelemetry by com.example.util.JarvisScreenCaptureManager.telemetry.collectAsState()
    val visionState by com.example.util.JarvisScreenCaptureManager.visionState.collectAsState()

    val activePersonality by viewModel.activePersonality.collectAsState()
    val currentEmotion by viewModel.currentEmotion.collectAsState()
    val activeTask by viewModel.activeInterruptionTask.collectAsState()
    val isEmergencyStopActive by viewModel.isEmergencyStopActive.collectAsState()
    val selectedSpecialist by viewModel.selectedSpecialistAgent.collectAsState()
    val pendingAnnouncements by viewModel.pendingAnnouncements.collectAsState()
    val isCameraVisionStreaming by viewModel.isCameraVisionStreaming.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val lastAutoResumeResult by viewModel.lastAutoResumeResult.collectAsState()

    var showEmotionPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        com.example.util.JarvisScreenCaptureManager.refreshTelemetry(context)
    }

    @Composable
    fun ReactorAndCompanionCore(modifier: Modifier = Modifier, compactSize: Boolean = false) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Pending One-Time Action Confirmation Card
            pendingApproval?.let { approval ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF14213D),
                    border = BorderStroke(1.5.dp, AmberGlow),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "একবারের অনুমোদন প্রয়োজন (${approval.recipientName})",
                            color = AmberGlow,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = approval.promptBn,
                            color = TextWhite,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    viewModel.approvePendingActionUi { _, msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pending_approval_confirm_button")
                            ) {
                                Text("হ্যাঁ, সম্পন্ন করো", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.rejectPendingActionUi() },
                                border = BorderStroke(1.dp, PinkNeon),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(48.dp)
                                    .testTag("pending_approval_cancel_button")
                            ) {
                                Text("না, বাতিল", color = PinkNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Auto-resume result banner after granting Android OS permission
            lastAutoResumeResult?.let { (_, msg) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EmeraldNeon.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, EmeraldNeon),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "পারমিশন পাওয়ার পর সম্পন্ন: $msg",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearLastAutoResumeResult() }) {
                            Text("ঠিক আছে", color = CyanNeon, fontSize = 11.sp)
                        }
                    }
                }
            }

            if (isEmergencyStopActive) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PinkNeon.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, PinkNeon),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🛑 জরুরি বিরতি সক্রিয় — সব কাজ বন্ধ",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.resetEmergencyStop() },
                            modifier = Modifier.testTag("hud_reset_emergency_stop")
                        ) {
                            Text("রিসেট", color = CyanNeon, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        }
                    }
                }
            }

            // Personality Selector Chips (5 STONIC Companion Modes)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) CyanNeon.copy(alpha = 0.22f) else SpaceSurface,
                        border = BorderStroke(1.dp, if (selected) CyanNeon else Color(0xFF334155)),
                        modifier = Modifier
                            .clickable { viewModel.setPersonalityMode(mode) }
                            .testTag("personality_chip_${mode.id}")
                    ) {
                        Text(
                            text = shortLabel,
                            color = if (selected) CyanNeon else TextDim,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Emotion + Specialist Agent Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clickable { showEmotionPicker = true }
                        .testTag("emotion_badge_button")
                ) {
                    Text(
                        text = "অনুভূতি: ${currentEmotion.emotion.labelBengali}",
                        color = BlueHolo,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "Agent: ${selectedSpecialist.titleBengali}",
                        color = AmberGlow,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(contentAlignment = Alignment.Center) {
                val reactorSize = if (compactSize) 170.dp else 200.dp
                Box(
                    modifier = Modifier
                        .size(reactorSize + 50.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    when (liveState) {
                                        LiveSessionState.SPEAKING -> BlueHolo.copy(alpha = 0.16f)
                                        LiveSessionState.LISTENING -> CyanNeon.copy(alpha = 0.18f)
                                        LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow.copy(alpha = 0.15f)
                                        else -> CyanNeon.copy(alpha = 0.08f)
                                    },
                                    Color.Transparent
                                )
                            )
                        )
                )

                JarvisArcReactorCanvas(
                    liveState = liveState,
                    micLevel = micLevel,
                    outputLevel = outputLevel,
                    modifier = Modifier
                        .size(reactorSize)
                        .clickable { viewModel.toggleLiveSession() }
                        .testTag("jarvis_arc_reactor_toggle")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SpaceSurface.copy(alpha = 0.88f),
                border = BorderStroke(
                    1.dp,
                    when (liveState) {
                        LiveSessionState.SPEAKING -> BlueHolo.copy(alpha = 0.6f)
                        LiveSessionState.LISTENING -> CyanNeon.copy(alpha = 0.7f)
                        LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow.copy(alpha = 0.6f)
                        LiveSessionState.ERROR -> PinkNeon.copy(alpha = 0.6f)
                        else -> Color(0xFF334155).copy(alpha = 0.5f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PulsingStatusDot(liveState = liveState)
                    Text(
                        text = when (liveState) {
                            LiveSessionState.CONNECTED -> "অনলাইন • মায়া প্রস্তুত"
                            LiveSessionState.CONNECTING -> "সংযোগ হচ্ছে..."
                            LiveSessionState.LISTENING -> "আমি শুনছি... সরাসরি বাংলায় বলুন"
                            LiveSessionState.THINKING -> "প্রক্রিয়া করছি..."
                            LiveSessionState.TOOL_EXECUTING -> "টুলস ও প্রোটোকল প্রসেসিং..."
                            LiveSessionState.SPEAKING -> "কথা বলছি... (থামাতে স্পর্শ করুন)"
                            LiveSessionState.ERROR -> "সংযোগ বিচ্ছিন্ন • পুনরায় চেষ্টা করুন"
                            LiveSessionState.DISCONNECTED -> "স্পর্শ করে কথা বলুন"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (liveState) {
                            LiveSessionState.LISTENING -> CyanNeon
                            LiveSessionState.SPEAKING -> BlueHolo
                            LiveSessionState.THINKING, LiveSessionState.TOOL_EXECUTING -> AmberGlow
                            else -> TextWhite
                        }
                    )
                }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusMessage,
                    fontSize = 11.sp,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            AudioWaveformDisplay(
                liveState = liveState,
                micLevel = micLevel,
                outputLevel = outputLevel,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
            )

            // Active Task / Full-Duplex Interruption Control Strip
            if (activeTask != null || liveState == LiveSessionState.SPEAKING) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        activeTask?.let { t ->
                            Text(
                                text = "সক্রিয় কাজ (${t.status.labelBengali}): ${t.goalBengali}",
                                color = TextWhite,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.triggerInstantBargeIn("hud_barge_in") },
                                border = BorderStroke(1.dp, BlueHolo),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .testTag("hud_barge_in_button")
                            ) {
                                Text("কথা থামাও", color = BlueHolo, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            activeTask?.let { t ->
                                val isPaused = t.status == TaskStatus.PAUSED
                                OutlinedButton(
                                    onClick = {
                                        if (isPaused) viewModel.resumeCurrentTask() else viewModel.pauseCurrentTask()
                                    },
                                    border = BorderStroke(1.dp, AmberGlow),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .testTag("hud_pause_resume_task_button")
                                ) {
                                    Text(
                                        text = if (isPaused) "চালু করো" else "বিরতি",
                                        color = AmberGlow,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.cancelCurrentTask() },
                                    border = BorderStroke(1.dp, PinkNeon),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .testTag("hud_cancel_task_button")
                                ) {
                                    Text("বাতিল", color = PinkNeon, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ControlsAndInputPane(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 5 Granular Real-time Telemetry Indicators + Emergency Stop Button
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    TelemetryChip(
                        label = "A11y UI",
                        active = screenTelemetry.accessibilityUiAccess
                    )
                }
                item {
                    TelemetryChip(
                        label = "Projection",
                        active = screenTelemetry.mediaProjectionConsent
                    )
                }
                item {
                    TelemetryChip(
                        label = "Vision: ${visionState.name}",
                        active = visionState == com.example.util.ScreenVisionState.ACTIVE,
                        warning = visionState == com.example.util.ScreenVisionState.PAUSED
                    )
                }
                item {
                    TelemetryChip(
                        label = "CamStream: ${if (isCameraVisionStreaming) "ON" else "OFF"}",
                        active = isCameraVisionStreaming
                    )
                }
                item {
                    TelemetryChip(
                        label = "Queue: ${pendingAnnouncements.size}",
                        active = pendingAnnouncements.isEmpty(),
                        warning = pendingAnnouncements.isNotEmpty()
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PinkNeon.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, PinkNeon),
                        modifier = Modifier
                            .clickable {
                                val report = viewModel.triggerEmergencyStop()
                                Toast.makeText(context, report.summaryBengali, Toast.LENGTH_LONG).show()
                            }
                            .testTag("hud_emergency_stop_button")
                    ) {
                        Text(
                            text = "🛑 সব বন্ধ",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 4-State Holographic Screen Sharing Control
            when (visionState) {
                com.example.util.ScreenVisionState.STOPPED -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyanNeon.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRequestScreenCapture() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenShare,
                                contentDescription = null,
                                tint = CyanNeon,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "START SCREEN SHARING (স্ক্রিন শেয়ার শুরু করুন)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanNeon,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
                com.example.util.ScreenVisionState.ACTIVE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AmberGlow.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    com.example.util.JarvisScreenCaptureManager.pauseScreenVision(context)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = AmberGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "PAUSE VISION (বিরতি)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberGlow
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PinkNeon.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, PinkNeon.copy(alpha = 0.8f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onStopScreenCapture() }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StopScreenShare,
                                    contentDescription = null,
                                    tint = PinkNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "STOP SHARING (বন্ধ)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PinkNeon
                                )
                            }
                        }
                    }
                }
                com.example.util.ScreenVisionState.PAUSED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EmeraldNeon.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, EmeraldNeon.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    com.example.util.JarvisScreenCaptureManager.resumeScreenVision(context)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = EmeraldNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "RESUME VISION (চালু)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldNeon
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PinkNeon.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, PinkNeon.copy(alpha = 0.8f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onStopScreenCapture() }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StopScreenShare,
                                    contentDescription = null,
                                    tint = PinkNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "STOP SHARING (বন্ধ)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PinkNeon
                                )
                            }
                        }
                    }
                }
            }

            // 1. Primary Action Dock (4 Glass Pills)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SIM Dial
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenDialPad() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("সিম কল", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
                    }
                }

                // WhatsApp
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenWhatsApp() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("হোয়াটসঅ্যাপ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    }
                }

                // Holographic Chat Log
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenChatHistory() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("হিস্ট্রি", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BlueHolo)
                    }
                }

                // System Diagnostics / Scan
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onExecuteCommand("জারভিস, সম্পূর্ণ সিস্টেম ডায়াগনস্টিক রিপোর্ট দাও এবং ব্যাটারি, মেমোরি ও ওএস স্ট্যাটাস জানাও")
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = AmberGlow, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("স্ক্যান", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = AmberGlow)
                    }
                }
            }

            // 1B. Quick Hub Module Pills (Notifications, Owner Profile, Notes, Vault)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("profile") }
                            .testTag("hud_quick_profile")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(14.dp))
                            Text("প্রোফাইল", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextWhite)
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("notifications") }
                            .testTag("hud_quick_notifications")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = AmberGlow, modifier = Modifier.size(14.dp))
                            Text("নোটিফিকেশন", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextWhite)
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("notepad") }
                            .testTag("hud_quick_notepad")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(14.dp))
                            Text("নোটস", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextWhite)
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, EmeraldNeon.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { viewModel.navigateTo("firebase_backup") }
                            .testTag("hud_quick_vault")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(14.dp))
                            Text("ব্যাকআপ ভল্ট", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextWhite)
                        }
                    }
                }
            }

            // 2. Smart Suggested Prompts (Single sleek horizontal row with Automation & AI queries)
            val smartVoicePrompts = remember {
                listOf(
                    "📍 আমি কোথায় আছি" to "আমি কোথায় আছি",
                    "🖥️ স্ক্রিন শেয়ার" to "START SCREEN SHARING",
                    "🛑 শেয়ারিং বন্ধ" to "STOP SCREEN SHARING",
                    "▶️ ইউটিউব চালাও" to "ইউটিউবে বাংলা গান চালাও",
                    "📞 কল রহিম" to "রহিমকে কল দাও",
                    "📍 জিপিএস লোকেশন" to "আমার বর্তমান লাইভ জিপিএস লোকেশন কত?",
                    "🔙 হোমে যাও" to "হোমে যাও",
                    "👁️ স্ক্রিন পরিদর্শন" to "স্ক্রিন দেখো",
                    "📜 নিচে নামাও" to "নিচে নামাও",
                    "⚡ সিস্টেম ডায়াগনস্টিক" to "সিস্টেম ডায়াগনস্টিক রিপোর্ট দাও এবং স্ট্যাটাস জানাও",
                    "🌤️ লাইভ আবহাওয়া" to "আজকের লাইভ আবহাওয়া, তাপমাত্রা ও পূর্বাভাস কেমন?",
                    "⏱️ পেন্ডিং প্রোটোকল" to "আজকের পেন্ডিং টাস্ক ও শিডিউলগুলো দেখাও",
                    "🧠 মেমোরি ফ্যাক্টস" to "আমার সেভ করা মেমোরি ও তথ্যের তালিকা দেখাও"
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(smartVoicePrompts) { (label, command) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SpaceSurface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.6f)),
                        modifier = Modifier.clickable {
                            onExecuteCommand(command)
                        }
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }

            // 3. Floating Input Dock (wrapped safely for gesture nav bar & keyboard)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { viewModel.triggerInstantBargeIn("dock_mute_speech") },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SpaceSurface)
                        .border(1.dp, Color(0xFF334155), CircleShape)
                        .testTag("hud_stop_speech_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeOff,
                        contentDescription = "Stop Speech",
                        tint = if (liveState == LiveSessionState.SPEAKING) BlueHolo else TextDim,
                        modifier = Modifier.size(20.dp)
                    )
                }

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
                        contentDescription = "Camera Vision",
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextInputChange,
                    placeholder = { Text("মায়া / জারভিসকে লিখুন...", fontSize = 12.5.sp, color = TextDim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendMessage() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = Color(0xFF334155),
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
                        .border(1.dp, if (textInput.isNotBlank()) CyanNeon else Color(0xFF334155), CircleShape)
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

    // Responsive Adaptive Layout: Two-Pane for Tablet / Unfolded (>= 600.dp), Single Column for Compact Phones
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReactorAndCompanionCore(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight(),
                    compactSize = false
                )
                ControlsAndInputPane(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                ReactorAndCompanionCore(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    compactSize = true
                )
                ControlsAndInputPane(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
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
                    text = "🔒 এই অপশন চালু করলে মাউথ স্পিকার সবসময় ব্যাকগ্রাউন্ডে ওপেন থাকবে। আপনি যখন যা বলবেন জারভিস তা সরাসরি শুনবে এবং নিজের কাজ করতে পারবে — এমনকি অ্যাপ থেকে বের হলেও।",
                    fontSize = 11.5.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )
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
                        text = "মায়া কন্ট্রোল সেন্টার (চরিত্র ও ইমোশন স্টুডিও)",
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
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRing"
    )

    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRing"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
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
        else -> CyanNeon.copy(alpha = 0.4f)
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2 * 0.92f

        // 1. Outermost Ambient Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor.copy(alpha = 0.22f + energyLevel * 0.25f), Color.Transparent),
                center = center,
                radius = maxRadius * 1.15f
            ),
            radius = maxRadius * 1.15f,
            center = center
        )

        // 2. Outer Segmented Ring
        val numTicks = 24
        for (i in 0 until numTicks) {
            val angleDeg = (i * (360f / numTicks) + outerAngle) % 360f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val startDist = maxRadius * 0.92f
            val endDist = maxRadius * (if (i % 3 == 0) 1.0f else 0.96f)
            val startP = Offset(
                (center.x + cos(angleRad) * startDist).toFloat(),
                (center.y + sin(angleRad) * startDist).toFloat()
            )
            val endP = Offset(
                (center.x + cos(angleRad) * endDist).toFloat(),
                (center.y + sin(angleRad) * endDist).toFloat()
            )
            drawLine(
                color = primaryColor.copy(alpha = if (i % 3 == 0) 0.85f else 0.45f),
                start = startP,
                end = endP,
                strokeWidth = if (i % 3 == 0) 2.5f else 1.5f,
                cap = StrokeCap.Round
            )
        }

        // 3. Middle Dashed Arc Ring
        drawArc(
            color = primaryColor.copy(alpha = 0.7f),
            startAngle = innerAngle,
            sweepAngle = 120f,
            useCenter = false,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
            size = Size(maxRadius * 1.5f, maxRadius * 1.5f),
            topLeft = Offset(center.x - maxRadius * 0.75f, center.y - maxRadius * 0.75f)
        )
        drawArc(
            color = BlueHolo.copy(alpha = 0.7f),
            startAngle = innerAngle + 180f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
            size = Size(maxRadius * 1.5f, maxRadius * 1.5f),
            topLeft = Offset(center.x - maxRadius * 0.75f, center.y - maxRadius * 0.75f)
        )

        // 4. Inner Glowing Quantum Core
        val coreRadius = maxRadius * 0.45f * pulseScale * (1f + energyLevel * 0.35f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    primaryColor,
                    primaryColor.copy(alpha = 0.1f)
                ),
                center = center,
                radius = coreRadius
            ),
            radius = coreRadius,
            center = center
        )

        // Core Ring Border
        drawCircle(
            color = Color.White,
            radius = coreRadius * 0.75f,
            center = center,
            style = Stroke(width = 2.5f)
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

