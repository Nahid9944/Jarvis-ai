package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.permissions.ui.PermissionCenterScreen
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.FirebaseBackupScreen
import com.example.ui.screens.NotepadScreen
import com.example.ui.screens.NotificationCenterScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.NahidAiTheme
import com.example.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.util.configureAppCheck(this, intent)
        com.example.service.NotificationAnnouncementCoordinator.init(this)
        enableEdgeToEdge()
        setContent {
            NahidAiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF040814) // Deep Space Dark
                ) {
                    JarvisAiApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.permissions.UniversalPermissionCoordinator.onResumeRecheck(this)
        com.example.permissions.JarvisPermissionManager.onResumeRecheckAndExecute(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun JarvisAiApp(viewModel: AssistantViewModel = viewModel()) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf("hud") }

    val orbState by viewModel.orbVisualState.collectAsState()
    val isOrbVisible by viewModel.isOrbVisible.collectAsState()
    val aiLiveState by viewModel.aiLiveState.collectAsState()
    val aiMicLevel by viewModel.aiMicLevel.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val hasLockPin by viewModel.hasLockPin.collectAsState()
    val loginError by viewModel.loginError.collectAsState()
    val currentOwnerName by viewModel.currentOwnerName.collectAsState()
    val wakeWordOptIn by viewModel.wakeWordOptIn.collectAsState()

    // Start offline keyword spotting ONLY when explicitly opted in by the user
    LaunchedEffect(wakeWordOptIn, isLoggedIn) {
        if (wakeWordOptIn && isLoggedIn) {
            viewModel.startOfflineKeywordSpotting()
        } else {
            viewModel.stopOfflineKeywordSpotting()
        }
    }

    // Trigger Gemini AI listening session when wake word or global orb is triggered
    LaunchedEffect(Unit) {
        val act = context as? ComponentActivity
        if (act?.intent?.getBooleanExtra("EXTRA_WAKE_TRIGGERED", false) == true) {
            viewModel.startLiveSession()
            viewModel.startMicStreaming()
        }
    }

    // Collect background wake word events to auto-trigger listening session
    LaunchedEffect(Unit) {
        com.example.service.JarvisWakeWordService.wakeWordEvents.collect {
            viewModel.startLiveSession()
            viewModel.startMicStreaming()
        }
    }

    // Collect AI voice assistant navigation commands
    LaunchedEffect(Unit) {
        viewModel.navigationCommand.collect { targetRoute ->
            if (targetRoute.isNotBlank()) {
                val route = targetRoute.trim().lowercase()
                when {
                    route in listOf("camera", "camera_vision", "scanner", "optical") -> currentScreen = "camera"
                    route in listOf("notepad", "notes", "memo") -> currentScreen = "notepad"
                    route in listOf("permissions", "permission_center", "capabilities") -> currentScreen = "permissions"
                    route in listOf("notifications", "notification_center", "alerts") -> currentScreen = "notifications"
                    route in listOf("control_center", "companion_hub", "character_studio") -> currentScreen = "control_center"
                    route in listOf("profile", "user_profile", "owner") -> currentScreen = "profile"
                    route in listOf("firebase_backup", "backup", "cloud_sync") -> currentScreen = "firebase_backup"
                    route in listOf("hud", "home", "back", "ai_assistant") -> currentScreen = "hud"
                    else -> currentScreen = "hud"
                }
            }
        }
    }

    // System Back Handler
    BackHandler(enabled = currentScreen != "hud") {
        currentScreen = "hud"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            "hud" -> {
                AiAssistantScreen(
                    viewModel = viewModel,
                    onBack = {
                        (context as? android.app.Activity)?.finish()
                    }
                )
            }
            "camera" -> {
                CameraScreen(
                    mode = com.example.ui.screens.CameraMode.AI_VOICE_VISION,
                    viewModel = viewModel,
                    onClose = { currentScreen = "hud" }
                )
            }
            "notepad" -> {
                NotepadScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = "hud" }
                )
            }
            "permissions" -> {
                PermissionCenterScreen(
                    onBack = { currentScreen = "hud" }
                )
            }
            "notifications" -> {
                NotificationCenterScreen(
                    viewModel = viewModel,
                    onNavigate = { route, _ ->
                        if (route.isNotBlank() && route != "notifications") {
                            viewModel.navigateTo(route)
                        } else {
                            currentScreen = "hud"
                        }
                    },
                    onBack = { currentScreen = "hud" }
                )
            }
            "control_center" -> {
                com.example.ui.screens.ControlCenterScreen(
                    viewModel = viewModel,
                    onOpenPermissions = { currentScreen = "permissions" },
                    onOpenCamera = { currentScreen = "camera" },
                    onBack = { currentScreen = "hud" }
                )
            }
            "firebase_backup" -> {
                FirebaseBackupScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = "hud" }
                )
            }
            "profile" -> {
                ProfileScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = "hud" }
                )
            }
            else -> {
                AiAssistantScreen(
                    viewModel = viewModel,
                    onBack = {
                        (context as? android.app.Activity)?.finish()
                    }
                )
            }
        }

        // Persistent Floating Jarvis Orb Overlay (Visually active across all screens when unlocked)
        if (isOrbVisible && isLoggedIn) {
            val isSessionActive = aiLiveState in listOf(
                com.example.ai.LiveSessionState.CONNECTING,
                com.example.ai.LiveSessionState.CONNECTED,
                com.example.ai.LiveSessionState.LISTENING,
                com.example.ai.LiveSessionState.THINKING,
                com.example.ai.LiveSessionState.SPEAKING,
                com.example.ai.LiveSessionState.TOOL_EXECUTING
            )
            com.example.ui.components.FloatingJarvisOrb(
                orbState = orbState,
                isVoiceSessionActive = isSessionActive,
                audioEnergy = aiMicLevel,
                onToggleLiveVoice = { viewModel.toggleLiveSession() },
                onTriggerScreenAnalysis = {
                    currentScreen = "camera"
                },
                onToggleFlashlight = {
                    com.example.util.JarvisDeviceAutomationHelper.toggleFlashlight(context, true)
                },
                onOpenDialer = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onOpenWhatsApp = {
                    com.example.ui.components.openWhatsAppDirect(context, "", "")
                },
                onOpenFullTerminal = {
                    currentScreen = "hud"
                }
            )
        }

        // Local App Lock Gate when user has configured a custom PIN
        if (hasLockPin && !isLoggedIn) {
            com.example.ui.components.JarvisLockScreenOverlay(
                ownerName = currentOwnerName,
                loginError = loginError,
                onSubmitPin = { enteredPin ->
                    viewModel.verifyLockPin(enteredPin)
                }
            )
        }
    }
}
