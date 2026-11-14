package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.*
import com.example.automation.JarvisSkillRecorder
import com.example.data.local.*
import com.example.data.repository.*
import com.example.permissions.*
import com.example.ui.components.OrbVisualState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AssistantDatabase.getDatabase(application)
    private val dao = db.assistantDao()
    private val repository = AssistantRepository(dao)
    private val backupRepo = FirebaseBackupRepository(dao, application.applicationContext)
    private val userManagementRepo = UserManagementRepository(dao, application.applicationContext)
    private val aiSettingsRepo = AiSettingsRepository(application.applicationContext)
    val jarvisMemoryRepo = JarvisMemoryRepository(dao, db.jarvisMemoryDao())
    val jarvisSkillRepo = JarvisSkillRepository(db.jarvisSkillDao())
    val skillRecorder = JarvisSkillRecorder(viewModelScope)
    val allLearnedSkills: StateFlow<List<JarvisSkillEntity>> = jarvisSkillRepo.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Database Migration Report
    private val _migrationReport = MutableStateFlow<DatabaseMigrationReport?>(null)
    val migrationReport: StateFlow<DatabaseMigrationReport?> = _migrationReport.asStateFlow()

    // Owner Credential & Local App Lock State (PBKDF2 salted hash via SecureKeystoreVault)
    private val authPrefs = application.getSharedPreferences("nahid_auth_prefs", android.content.Context.MODE_PRIVATE).also { prefs ->
        val legacyPlain = prefs.getString("saved_password", "")?.trim().orEmpty()
        if (legacyPlain.isNotEmpty()) {
            val hashed = com.example.security.SecureKeystoreVault.hashPinWithSalt(legacyPlain)
            prefs.edit()
                .putString("saved_pin_hash", hashed)
                .putBoolean("has_lock_pin", true)
                .remove("saved_password")
                .apply()
        }
    }

    fun hasConfiguredLockPin(): Boolean {
        val hash = authPrefs.getString("saved_pin_hash", "") ?: ""
        return hash.isNotBlank() && authPrefs.getBoolean("has_lock_pin", true)
    }

    private val _hasLockPin = MutableStateFlow(hasConfiguredLockPin())
    val hasLockPin: StateFlow<Boolean> = _hasLockPin.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(!hasConfiguredLockPin())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _loginError = MutableStateFlow("")
    val loginError: StateFlow<String> = _loginError.asStateFlow()

    private val _currentOwnerName = MutableStateFlow(
        authPrefs.getString("saved_username", "Masum Billah Nahid") ?: "Masum Billah Nahid"
    )
    val currentOwnerName: StateFlow<String> = _currentOwnerName.asStateFlow()

    fun getSavedCredentials(): Pair<String, String> {
        val savedName = authPrefs.getString("saved_username", "Masum Billah Nahid") ?: "Masum Billah Nahid"
        return Pair(savedName, "")
    }

    fun saveCredentials(username: String, pass: String) {
        val cleanName = username.trim().ifBlank { "Masum Billah Nahid" }
        val cleanPin = pass.trim()
        val editor = authPrefs.edit()
            .putString("saved_username", cleanName)
            .remove("saved_password")
        if (cleanPin.isNotBlank()) {
            editor.putString("saved_pin_hash", com.example.security.SecureKeystoreVault.hashPinWithSalt(cleanPin))
                .putBoolean("has_lock_pin", true)
        } else {
            editor.remove("saved_pin_hash")
                .putBoolean("has_lock_pin", false)
        }
        editor.apply()
        _currentOwnerName.value = cleanName
        _hasLockPin.value = hasConfiguredLockPin()
        _loginError.value = ""
    }

    fun verifyLockPin(enteredPin: String): Boolean {
        val storedHash = authPrefs.getString("saved_pin_hash", "") ?: ""
        if (storedHash.isBlank()) {
            _isLoggedIn.value = true
            _loginError.value = ""
            return true
        }
        val ok = com.example.security.SecureKeystoreVault.verifyPin(enteredPin, storedHash)
        if (ok) {
            _isLoggedIn.value = true
            _loginError.value = ""
        } else {
            _loginError.value = "ভুল লোকাল পিন! আবার চেষ্টা করুন।"
        }
        return ok
    }

    fun unlockWithBiometricSuccess() {
        _isLoggedIn.value = true
        _loginError.value = ""
    }

    fun lockAppNow() {
        if (hasConfiguredLockPin()) {
            _isLoggedIn.value = false
            _loginError.value = ""
        }
    }

    fun clearSavedCredentials() {
        authPrefs.edit()
            .remove("saved_password")
            .remove("saved_pin_hash")
            .putBoolean("has_lock_pin", false)
            .apply()
        _hasLockPin.value = false
        _isLoggedIn.value = true
        _loginError.value = ""
    }

    // Device & Telemetry Streams
    val allAppUsers: StateFlow<List<AppUserEntity>> = userManagementRepo.getAllUsersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isCurrentUserBlocked: StateFlow<Boolean> = userManagementRepo.isCurrentUserBlocked
    val locationSharingEnabled: StateFlow<Boolean> = userManagementRepo.locationSharingEnabled
    val currentUserId: String get() = userManagementRepo.currentUserId
    val isMasterApp: Boolean get() = userManagementRepo.isMasterApp
    val isUserRegistered: Boolean get() = userManagementRepo.isUserRegistered()
    val registeredUserName: String get() = userManagementRepo.getRegisteredUserName()
    val currentUserNumber: String get() = userManagementRepo.userNumber
    val currentDeviceModel: String get() = userManagementRepo.currentDeviceModel
    val persistentDeviceId: String get() = userManagementRepo.persistentDeviceId
    val installationId: String get() = userManagementRepo.installationId

    // Active Multi-Agent State
    private val _activeAiAgent = MutableStateFlow<AiAgent>(AiAgentRegistry.getAgentById(aiSettingsRepo.getSelectedAgentId()))
    val activeAiAgent: StateFlow<AiAgent> = _activeAiAgent.asStateFlow()

    fun selectAiAgent(agent: AiAgent) {
        _activeAiAgent.value = agent
        aiSettingsRepo.setSelectedAgentId(agent.id)
    }

    fun toggleLocationSharing(enabled: Boolean) {
        viewModelScope.launch {
            userManagementRepo.setLocationSharing(enabled)
        }
    }

    fun setLocationSharing(enabled: Boolean) {
        toggleLocationSharing(enabled)
    }

    // Core Personal Data Streams
    val allNotes: StateFlow<List<NoteEntity>> = repository.allNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profile: StateFlow<JarvisProfileEntity?> = repository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Personal Notification Center Streams
    val allNotifications: StateFlow<List<AppNotificationEntity>> = repository.allActiveNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadNotificationCount: StateFlow<Int> = repository.unreadNotificationCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun markNotificationAsRead(id: Long) {
        viewModelScope.launch { repository.markNotificationAsRead(id) }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch { repository.markAllNotificationsAsRead() }
    }

    fun deleteNotification(id: Long) {
        viewModelScope.launch { repository.deleteNotification(id) }
    }

    fun clearAllNotifications() {
        viewModelScope.launch { repository.clearAllNotifications() }
    }

    // AI Long-Term Memory
    val aiMemories: StateFlow<List<AiMemoryEntity>> = dao.getAllActiveMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMemories: StateFlow<List<AiMemoryEntity>> get() = aiMemories

    fun saveAiMemory(
        key: String,
        content: String,
        category: String = "USER_PREFERENCE",
        importance: Int = 1,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.insertMemory(
                AiMemoryEntity(
                    key = key.trim(),
                    content = content.trim(),
                    category = category,
                    importance = importance,
                    createdAt = now,
                    updatedAt = now,
                    isPinned = importance >= 3,
                    isActive = true
                )
            )
            onDone()
        }
    }

    fun updateAiMemory(memory: AiMemoryEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            dao.updateMemory(memory.copy(updatedAt = System.currentTimeMillis()))
            onDone()
        }
    }

    fun deleteAiMemory(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            dao.deleteMemoryById(id)
            onDone()
        }
    }

    // AI Scheduled Tasks & Reminders
    val aiTasks: StateFlow<List<AiTaskEntity>> = dao.getAllAiTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAiTasks: StateFlow<List<AiTaskEntity>> get() = aiTasks

    fun createAiTask(
        title: String,
        description: String,
        scheduledTime: Long,
        scheduledTimeString: String,
        priority: String = "NORMAL",
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val insertedId = dao.insertAiTask(
                AiTaskEntity(
                    title = title.trim(),
                    description = description.trim(),
                    scheduledTime = scheduledTime,
                    scheduledTimeString = scheduledTimeString,
                    status = "PENDING",
                    actionType = "REMINDER",
                    targetCommand = description,
                    priority = priority,
                    isExecuted = false,
                    createdAt = System.currentTimeMillis()
                )
            )
            if (scheduledTime > System.currentTimeMillis()) {
                com.example.service.JarvisAlarmReceiver.scheduleAlarm(
                    context = getApplication(),
                    taskId = insertedId,
                    title = "NAHID AI প্রোটোকল: ${title.trim()}",
                    description = description.trim().ifBlank { "আপনার নির্ধারিত শিডিউলের সময় হয়েছে, স্যার।" },
                    triggerTimeMillis = scheduledTime
                )
            }
            onDone()
        }
    }

    fun updateAiTask(task: AiTaskEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            dao.updateAiTask(task)
            if (task.scheduledTime > System.currentTimeMillis() && !task.isExecuted) {
                com.example.service.JarvisAlarmReceiver.scheduleAlarm(
                    context = getApplication(),
                    taskId = task.id,
                    title = "NAHID AI প্রোটোকল: ${task.title.trim()}",
                    description = task.description.trim().ifBlank { "আপনার নির্ধারিত শিডিউলের সময় হয়েছে, স্যার।" },
                    triggerTimeMillis = task.scheduledTime
                )
            }
            onDone()
        }
    }

    fun deleteAiTask(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            com.example.service.JarvisAlarmReceiver.cancelAlarm(getApplication(), id)
            dao.deleteAiTask(id)
            onDone()
        }
    }

    fun markAiTaskCompleted(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            com.example.service.JarvisAlarmReceiver.cancelAlarm(getApplication(), id)
            dao.markAiTaskCompleted(id)
            onDone()
        }
    }

    // Holographic Chat History
    val jarvisChatHistory: StateFlow<List<JarvisChatHistoryEntity>> = dao.getAllJarvisChatHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allJarvisChatHistory: StateFlow<List<JarvisChatHistoryEntity>> get() = jarvisChatHistory

    fun saveJarvisChatRecord(
        userPrompt: String,
        aiResponse: String,
        commandType: String = "VOICE",
        status: String = "SUCCESS",
        executionDetails: String = ""
    ) {
        if (userPrompt.isBlank() && aiResponse.isBlank()) return
        viewModelScope.launch {
            dao.insertJarvisChat(
                JarvisChatHistoryEntity(
                    userPrompt = userPrompt.trim(),
                    aiResponse = aiResponse.trim(),
                    timestamp = System.currentTimeMillis(),
                    commandType = commandType,
                    status = status,
                    executionDetails = executionDetails
                )
            )
            jarvisMemoryRepo.recordConversationSnippet(
                userPrompt = userPrompt,
                aiResponse = aiResponse,
                topic = commandType,
                keywords = userPrompt.split(" ").filter { it.length > 3 }.take(4).joinToString(", "),
                importanceScore = if (status == "SUCCESS") 2 else 1
            )
        }
    }

    fun addJarvisChatMessage(
        userPrompt: String,
        aiResponse: String,
        commandType: String = "VOICE",
        status: String = "SUCCESS",
        executionDetails: String = ""
    ) {
        saveJarvisChatRecord(userPrompt, aiResponse, commandType, status, executionDetails)
    }

    fun deleteJarvisChatRecord(id: Long) {
        viewModelScope.launch { dao.deleteJarvisChatById(id) }
    }

    fun clearAllJarvisChatHistory() {
        viewModelScope.launch { dao.clearAllJarvisChatHistory() }
    }

    // Notes
    fun saveNote(note: NoteEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.insertOrUpdateNote(note)
            onDone()
        }
    }

    fun deleteNote(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteNote(id)
            onDone()
        }
    }

    // Profile
    fun saveProfile(prof: JarvisProfileEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val enteredPin = prof.loginPassword.trim()
            if (enteredPin != "__LOCKED_IN_VAULT__") {
                saveCredentials(prof.loginUserName.ifBlank { prof.ownerName }, enteredPin)
            } else {
                authPrefs.edit().putString("saved_username", prof.loginUserName.ifBlank { prof.ownerName }).apply()
                _currentOwnerName.value = prof.loginUserName.ifBlank { prof.ownerName }
            }
            // Never persist raw PIN plaintext inside SQLite row
            repository.updateProfile(prof.copy(loginPassword = ""))
            onDone()
        }
    }

    fun deleteProfile(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            clearSavedCredentials()
            repository.deleteProfile()
            onDone()
        }
    }

    // Local Personal Vault Backup
    private val _cloudBackupInfo = MutableStateFlow<CloudBackupInfo?>(null)
    val cloudBackupInfo: StateFlow<CloudBackupInfo?> = _cloudBackupInfo.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private val _isBackupLoading = MutableStateFlow(false)
    val isBackupLoading: StateFlow<Boolean> = _isBackupLoading.asStateFlow()

    private val _isCheckingCloud = MutableStateFlow(false)
    val isCheckingCloud: StateFlow<Boolean> = _isCheckingCloud.asStateFlow()

    fun checkCloudBackupStatus() {
        viewModelScope.launch {
            _isCheckingCloud.value = true
            val res = backupRepo.checkCloudBackupInfo()
            _cloudBackupInfo.value = res.getOrNull()
            _isCheckingCloud.value = false
        }
    }

    fun uploadToFirebase() {
        viewModelScope.launch {
            _isBackupLoading.value = true
            val result = backupRepo.uploadToCloud { _backupStatus.value = it }
            _isBackupLoading.value = false
            _backupStatus.value = result.getOrNull() ?: result.exceptionOrNull()?.message
            checkCloudBackupStatus()
        }
    }

    fun downloadFromFirebase() {
        viewModelScope.launch {
            _isBackupLoading.value = true
            val result = backupRepo.restoreFromCloud { _backupStatus.value = it }
            _isBackupLoading.value = false
            _backupStatus.value = result.getOrNull() ?: result.exceptionOrNull()?.message
        }
    }

    // Navigation & Voice AI Command System
    private val _navigationCommand = MutableSharedFlow<String>(replay = 1)
    val navigationCommand: SharedFlow<String> = _navigationCommand.asSharedFlow()

    fun navigateTo(screen: String) {
        _navigationCommand.tryEmit(screen)
    }

    private val assistantToolExecutor = AssistantToolExecutor(
        dao = dao,
        onNavigate = { screen ->
            _navigationCommand.tryEmit(screen)
        },
        context = application.applicationContext
    )

    // Gemini Live Session Management (FROZEN CORE PRESERVED)
    val aiSettings: StateFlow<AiSettingsState> = aiSettingsRepo.settingsState

    private val _aiLiveState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val aiLiveState: StateFlow<LiveSessionState> = _aiLiveState.asStateFlow()

    private val _aiStatusMessage = MutableStateFlow("প্রস্তুত")
    val aiStatusMessage: StateFlow<String> = _aiStatusMessage.asStateFlow()

    private val _aiMicLevel = MutableStateFlow(0f)
    val aiMicLevel: StateFlow<Float> = _aiMicLevel.asStateFlow()

    private val _aiOutputLevel = MutableStateFlow(0f)
    val aiOutputLevel: StateFlow<Float> = _aiOutputLevel.asStateFlow()

    private val liveSessionManager = GeminiLiveSessionManager(
        context = application.applicationContext,
        toolExecutor = assistantToolExecutor,
        onAudioLevelChanged = { mic, out ->
            _aiMicLevel.value = mic
            _aiOutputLevel.value = out
        },
        onStatusChanged = { state, msg ->
            _aiLiveState.value = state
            _aiStatusMessage.value = msg
            val isLiveReady = state == LiveSessionState.CONNECTED ||
                state == LiveSessionState.LISTENING ||
                state == LiveSessionState.SPEAKING
            com.example.service.GeminiLiveAnnouncementAdapter.setLiveSessionReady(isLiveReady)

            val micClaimedByLive = state != LiveSessionState.DISCONNECTED && state != LiveSessionState.ERROR
            if (micClaimedByLive) {
                offlineKeywordSpotter.pauseForLiveMic("live_${state.name}")
            } else {
                offlineKeywordSpotter.resumeAfterLiveMic("live_${state.name}")
            }
        }
    )

    val liveInputTranscript: StateFlow<String> = liveSessionManager.latestInputTranscript
    val liveOutputTranscript: StateFlow<String> = liveSessionManager.latestOutputTranscript

    // Companion Personality, Emotion, Full-Duplex Interruption & Orchestrator StateFlows
    val activePersonality: StateFlow<JarvisPersonalityMode> = JarvisPersonalityManager.currentMode
    val previousPersonalityMode: StateFlow<JarvisPersonalityMode?> = JarvisPersonalityManager.previousMode
    val activeCharacterProfile: StateFlow<CharacterProfile> = JarvisPersonalityManager.activeProfile
    val characterVersions: StateFlow<List<CharacterVersionRecord>> = JarvisPersonalityManager.versionHistory

    val currentEmotion: StateFlow<EmotionEvaluation> = EmotionContextEngine.currentEmotion
    val emotionConfig: StateFlow<EmotionControlConfig> = EmotionContextEngine.config
    val emotionHistory: StateFlow<List<EmotionEvaluation>> = EmotionContextEngine.history

    val activeInterruptionTask: StateFlow<ActiveTaskState?> = ActiveTaskInterruptionManager.currentTask
    val lastInterruptionStatus: StateFlow<String?> = ActiveTaskInterruptionManager.lastInterruptionStatus
    val voiceTurnState: StateFlow<TurnState> = VoiceTurnManager.turnState
    val isBargeInActive: StateFlow<Boolean> = PlaybackInterruptionBridge.isUserBargeInActive

    val activeOrchestrationPlan: StateFlow<MasterExecutionPlan?> = JarvisMasterOrchestrator.activePlan
    val selectedSpecialistAgent: StateFlow<AiAgent> = JarvisMasterOrchestrator.selectedSpecialist
    val lastOrchestrationResult: StateFlow<MasterOrchestrationResult?> = JarvisMasterOrchestrator.lastOrchestrationResult

    val isCameraVisionStreaming: StateFlow<Boolean> = JarvisCameraVisionCoordinator.isCameraStreaming
    val lastCameraSummary: StateFlow<String> = JarvisCameraVisionCoordinator.lastVisualSummaryBn
    val visualObservationState: StateFlow<VisualObservationState> = JarvisCameraVisionCoordinator.observationState

    // Centralized Permission & One-Time Approval StateFlows
    val pendingApproval: StateFlow<ActionApprovalRecord?> = ActionApprovalRegistry.pendingApproval
    val pendingOsAction: StateFlow<PendingOsPermissionAction?> = JarvisPermissionManager.pendingOsAction
    val permissionAuditTrail: StateFlow<List<PermissionAuditEntry>> = ActionApprovalRegistry.auditTrail
    val trustedLowRiskRules: StateFlow<List<TrustedActionRule>> = ActionApprovalRegistry.trustedRules
    val revokedPermissionNotices: StateFlow<List<String>> = JarvisPermissionManager.revokedNotices
    val lastAutoResumeResult: StateFlow<Pair<String, String>?> = JarvisPermissionManager.lastAutoResumeResult

    val pendingAnnouncements = com.example.service.GeminiLiveAnnouncementAdapter.pendingAnnouncements
    val latestSmsStatus = com.example.util.JarvisSmsManager.latestSmsStatus
    val isEmergencyStopActive: StateFlow<Boolean> = com.example.util.JarvisEmergencyStopCoordinator.isEmergencyStopActive
    val lastEmergencyStopReport = com.example.util.JarvisEmergencyStopCoordinator.lastReport

    init {
        val companionDao = db.companionDao()
        ActionApprovalRegistry.attachDao(companionDao)
        JarvisPersonalityManager.init(application.applicationContext)
        JarvisPersonalityManager.attachDao(companionDao)
        EmotionContextEngine.attachDao(companionDao)
        com.example.util.JarvisEmergencyStopCoordinator.registerRuntimeHooks(
            onStopLiveSession = { liveSessionManager.stopSession() },
            onStopOfflineSpotter = { offlineKeywordSpotter.stopListening() }
        )
        com.example.service.GeminiLiveAnnouncementAdapter.setLiveSessionReady(false) { announcementText ->
            if (_aiLiveState.value == LiveSessionState.CONNECTED ||
                _aiLiveState.value == LiveSessionState.LISTENING ||
                _aiLiveState.value == LiveSessionState.SPEAKING
            ) {
                liveSessionManager.sendUserTextMessage(
                    "অনুগ্রহ করে এই জরুরি তথ্যটি ব্যবহারকারীকে জানিয়ে দিন: $announcementText",
                    aiSettings.value
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _migrationReport.value = DatabaseMigrationHelper.verifyMigratedDataCounts(db)
            } catch (_: Exception) {
            }
        }

        // Persist completed voice transcripts into chat history cleanly
        var pendingVoiceUserTurn = ""
        viewModelScope.launch(Dispatchers.IO) {
            liveSessionManager.transcriptEvents.collect { evt ->
                if (evt.isTurnComplete) {
                    if (evt.speaker == "USER") {
                        pendingVoiceUserTurn = evt.text
                    } else if (evt.speaker == "ASSISTANT") {
                        saveJarvisChatRecord(
                            userPrompt = pendingVoiceUserTurn.ifBlank { "[লাইভ ভয়েস]" },
                            aiResponse = evt.text,
                            commandType = "LIVE_VOICE",
                            status = "SUCCESS"
                        )
                        pendingVoiceUserTurn = ""
                    }
                }
            }
        }
    }

    fun startLiveSession() {
        com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()
        offlineKeywordSpotter.pauseForLiveMic("starting_live_session")
        liveSessionManager.startSession(aiSettings.value)
    }

    fun startMicStreaming() {
        offlineKeywordSpotter.pauseForLiveMic("mic_streaming")
        liveSessionManager.startMicStreaming()
    }

    fun stopLiveSession() {
        liveSessionManager.stopSession()
        offlineKeywordSpotter.resumeAfterLiveMic("session_stopped")
    }

    fun sendAiImageFrame(base64Jpeg: String) {
        liveSessionManager.sendImageFrame(base64Jpeg)
    }

    fun toggleLiveSession() {
        if (_aiLiveState.value == LiveSessionState.DISCONNECTED || _aiLiveState.value == LiveSessionState.ERROR) {
            startLiveSession()
        } else {
            stopLiveSession()
        }
    }

    fun sendLiveTextMessage(text: String) {
        liveSessionManager.sendUserTextMessage(text, aiSettings.value)
    }

    fun interruptLivePlayback() {
        liveSessionManager.interruptPlayback()
    }

    fun setPersonalityMode(mode: JarvisPersonalityMode) {
        JarvisPersonalityManager.setPersonality(mode)
        viewModelScope.launch {
            jarvisMemoryRepo.savePreference(
                key = "companion_personality_mode",
                value = mode.id,
                category = "PERSONALITY",
                desc = mode.titleBengali
            )
        }
        if (_aiLiveState.value == LiveSessionState.CONNECTED ||
            _aiLiveState.value == LiveSessionState.LISTENING ||
            _aiLiveState.value == LiveSessionState.SPEAKING
        ) {
            liveSessionManager.sendUserTextMessage(
                "[System Persona Switch: Adopt personality '${mode.titleEnglish}' now. ${mode.shortDescriptionBn}]",
                aiSettings.value
            )
        }
    }

    fun revertToPreviousPersonality() {
        val prev = JarvisPersonalityManager.revertToPreviousMode()
        if (prev != null && (_aiLiveState.value == LiveSessionState.CONNECTED ||
                _aiLiveState.value == LiveSessionState.LISTENING ||
                _aiLiveState.value == LiveSessionState.SPEAKING)
        ) {
            liveSessionManager.sendUserTextMessage(
                "[System Persona Switch: Switched back to '${prev.titleEnglish}'.]",
                aiSettings.value
            )
        }
    }

    fun recordCharacterFeedback(feedbackBn: String) {
        JarvisPersonalityManager.recordUserCharacterFeedback(feedbackBn)
    }

    fun revertCharacterToVersion(version: Int): Boolean {
        return JarvisPersonalityManager.revertToVersion(version)
    }

    fun correctUserEmotion(emotion: UserEmotion) {
        EmotionContextEngine.applyUserEmotionCorrection(emotion)
    }

    fun updateEmotionConfig(cfg: EmotionControlConfig) {
        EmotionContextEngine.updateConfig(cfg)
    }

    fun resetEmotionBaseline() {
        EmotionContextEngine.resetBaseline()
    }

    fun clearEmotionHistory() {
        EmotionContextEngine.clearHistory()
    }

    fun approvePendingActionUi(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val approved = JarvisPermissionManager.approveUserAction(source = "ui_button") ?: run {
            onDone(false, "কোনো পেন্ডিং অনুমোদন নেই বা মেয়াদ শেষ হয়েছে।")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val args = try {
                JSONObject(approved.argsJson)
            } catch (_: Exception) {
                JSONObject()
            }
            args.put("_approvalId", approved.approvalId)
            args.put("_executionId", approved.executionId)
            val result = assistantToolExecutor.executeToolWithCaller(
                name = approved.toolName,
                args = args,
                caller = approved.caller
            )
            val ok = result.optBoolean("success", false)
            val summary = result.optString("summaryBengali", result.optString("message", "সম্পন্ন হয়েছে।"))
            saveJarvisChatRecord(
                userPrompt = "[অনুমোদন প্রদান: ${approved.recipientName}]",
                aiResponse = summary,
                commandType = approved.toolName,
                status = if (ok) "SUCCESS" else "FAILED",
                executionDetails = summary
            )
            withContext(Dispatchers.Main) {
                onDone(ok, summary)
            }
        }
    }

    fun rejectPendingActionUi() {
        JarvisPermissionManager.rejectUserAction(source = "ui_button", reason = "ব্যবহারকারী UI থেকে বাতিল করেছেন")
    }

    fun toggleTrustedLowRiskRule(toolName: String, scopeDescriptionBn: String, enabled: Boolean): Pair<Boolean, String> {
        return ActionApprovalRegistry.setTrustedLowRiskRule(toolName, scopeDescriptionBn, enabled)
    }

    fun clearRevokedNotices() = JarvisPermissionManager.clearRevokedNotices()

    fun clearLastAutoResumeResult() = JarvisPermissionManager.clearLastAutoResumeResult()

    fun triggerInstantBargeIn(reason: String = "manual_ui_barge_in") {
        PlaybackInterruptionBridge.triggerInstantBargeIn(reason)
        liveSessionManager.interruptPlayback()
    }

    fun pauseCurrentTask(): InterruptionHandlingResult {
        return ActiveTaskInterruptionManager.handleInterruptionCommand("একটু অপেক্ষা করো, পজ করো")
    }

    fun resumeCurrentTask(): InterruptionHandlingResult {
        return ActiveTaskInterruptionManager.handleInterruptionCommand("আবার শুরু করো")
    }

    fun cancelCurrentTask(): InterruptionHandlingResult {
        return ActiveTaskInterruptionManager.handleInterruptionCommand("কাজটা বাতিল করো")
    }

    fun triggerEmergencyStop(): com.example.util.EmergencyStopReport {
        return com.example.util.JarvisEmergencyStopCoordinator.triggerEmergencyStop(getApplication())
    }

    fun resetEmergencyStop() {
        com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    suspend fun orchestrateUserCommand(
        rawCommand: String,
        confirmationToken: String? = null
    ): MasterOrchestrationResult {
        return JarvisMasterOrchestrator.orchestrate(
            context = getApplication(),
            utterance = rawCommand,
            toolExecutor = assistantToolExecutor,
            caller = "user_ui",
            confirmationToken = confirmationToken
        )
    }

    fun captureCameraVisionFrame() {
        viewModelScope.launch {
            JarvisCameraVisionCoordinator.captureAndStreamVisualContext(getApplication())
        }
    }

    fun toggleCameraVisionStream(enabled: Boolean) {
        if (enabled) {
            JarvisCameraVisionCoordinator.startContinuousVisionStream(getApplication())
        } else {
            JarvisCameraVisionCoordinator.stopVisionStream()
        }
    }

    fun clearAnnouncementQueue() {
        com.example.service.GeminiLiveAnnouncementAdapter.clearQueue()
        com.example.util.JarvisVoiceSpeaker.clearQueue()
    }

    fun teachNewSkill(
        skillName: String,
        triggerPhrase: String,
        naturalLanguageSteps: String,
        onDone: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val created = JarvisSkillRecorder.teachSkillFromNaturalLanguage(
                    skillName = skillName,
                    skillNameBengali = skillName,
                    triggerPhrases = listOf(triggerPhrase),
                    stepsInstructions = naturalLanguageSteps,
                    repository = jarvisSkillRepo
                )
                onDone(true, "'${created.nameBengali}' (v${created.version}) সফলভাবে শেখানো হয়েছে!")
            } catch (e: Exception) {
                onDone(false, e.message ?: "স্কিল সংরক্ষণে ত্রুটি")
            }
        }
    }

    fun toggleSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            jarvisSkillRepo.setSkillEnabled(skillId, enabled)
        }
    }

    fun rollbackSkill(skillId: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = jarvisSkillRepo.rollbackSkillToPreviousVersion(skillId)
            onDone(res.first, res.second)
        }
    }

    fun deleteSkill(skill: JarvisSkillEntity) {
        viewModelScope.launch {
            jarvisSkillRepo.deleteSkill(skill.skillId)
        }
    }

    fun importSkillFromGitHub(
        repoUrl: String,
        onDone: (org.json.JSONObject) -> Unit
    ) {
        viewModelScope.launch {
            val res = com.example.automation.GitHubRepositoryLearningEngine.importFromRepository(
                context = getApplication(),
                repoUrl = repoUrl,
                skillRepo = jarvisSkillRepo
            )
            onDone(res)
        }
    }

    fun importSkillFromYouTube(
        youtubeUrl: String,
        manualTranscript: String = "",
        onDone: (org.json.JSONObject) -> Unit
    ) {
        viewModelScope.launch {
            val res = com.example.automation.YouTubeTutorialImporter.importFromTutorial(
                context = getApplication(),
                url = youtubeUrl,
                userProvidedTranscript = manualTranscript.ifBlank { null },
                skillRepo = jarvisSkillRepo
            )
            onDone(res)
        }
    }

    fun updateAiSettings(newSettings: AiSettingsState) {
        aiSettingsRepo.saveSettings(
            apiKey = newSettings.apiKey,
            modelName = newSettings.modelName,
            voiceName = newSettings.voiceName,
            systemInstruction = newSettings.systemInstruction,
            autoConnect = newSettings.autoConnect,
            voiceVolumeLevel = newSettings.voiceVolumeLevel,
            authMode = newSettings.authMode,
            ephemeralToken = newSettings.ephemeralToken,
            proxyEndpoint = newSettings.proxyEndpoint
        )
    }

    fun resetAiSettings() {
        aiSettingsRepo.resetToDefaults()
    }

    fun testAiConnection(key: String, model: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = aiSettingsRepo.testConnection(key, model)
            onResult(res.first, res.second)
        }
    }

    val isBackgroundModeActive: StateFlow<Boolean> = com.example.service.JarvisBackgroundService.isRunning

    fun toggleBackgroundMode(enabled: Boolean) {
        val appCtx = getApplication<Application>().applicationContext
        if (enabled) {
            com.example.service.JarvisBackgroundService.start(appCtx)
            if (_aiLiveState.value == LiveSessionState.DISCONNECTED || _aiLiveState.value == LiveSessionState.ERROR) {
                startLiveSession()
                startMicStreaming()
            }
        } else {
            com.example.service.JarvisBackgroundService.stop(appCtx)
        }
    }

    suspend fun executeNlpCommand(commandText: String, onPromptAccessibility: () -> Unit = {}): JarvisExecutionResult = withContext(Dispatchers.IO) {
        val parsed = JarvisNlpCommandParser.parse(commandText)
        val outcome = VoiceCommandUnderstandingEngine.processCommand(
            context = getApplication(),
            rawCommand = commandText,
            onPromptAccessibility = onPromptAccessibility,
            toolExecutor = assistantToolExecutor,
            onNavigate = { screen -> _navigationCommand.tryEmit(screen) },
            caller = "user_ui"
        )
        JarvisExecutionResult(
            success = outcome.success,
            feedbackMessage = outcome.feedbackMessage,
            command = parsed,
            needsAccessibilityPermission = outcome.needsAccessibility,
            needsLocationPermission = outcome.needsLocation,
            needsScreenSharingPrompt = outcome.needsScreenSharing
        )
    }

    suspend fun quickFetchWeather(location: String): LiveWeatherData = withContext(Dispatchers.IO) {
        WeatherService.fetchWeather(location)
    }

    val orbVisualState: StateFlow<OrbVisualState> = combine(
        _aiLiveState,
        _aiMicLevel,
        _aiOutputLevel
    ) { state, mic, out ->
        when (state) {
            LiveSessionState.CONNECTING,
            LiveSessionState.THINKING,
            LiveSessionState.TOOL_EXECUTING -> OrbVisualState.THINKING
            LiveSessionState.LISTENING -> OrbVisualState.LISTENING
            LiveSessionState.SPEAKING -> OrbVisualState.SPEAKING
            LiveSessionState.CONNECTED -> {
                if (out > 0.03f) {
                    OrbVisualState.SPEAKING
                } else if (mic > 0.03f) {
                    OrbVisualState.LISTENING
                } else {
                    OrbVisualState.IDLE
                }
            }
            LiveSessionState.ERROR -> OrbVisualState.ERROR
            LiveSessionState.DISCONNECTED -> OrbVisualState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrbVisualState.IDLE)

    private val _isOrbVisible = MutableStateFlow(true)
    val isOrbVisible: StateFlow<Boolean> = _isOrbVisible.asStateFlow()

    fun toggleOrbVisibility(visible: Boolean) {
        _isOrbVisible.value = visible
        viewModelScope.launch {
            jarvisMemoryRepo.savePreference("orb_overlay_enabled", visible.toString(), "UI", "ফ্লোটিং ওর্ব সক্ষম")
        }
    }

    private val _wakeWordOptIn = MutableStateFlow(authPrefs.getBoolean("wake_word_opt_in", false))
    val wakeWordOptIn: StateFlow<Boolean> = _wakeWordOptIn.asStateFlow()

    fun setWakeWordOptIn(enabled: Boolean) {
        authPrefs.edit().putBoolean("wake_word_opt_in", enabled).apply()
        _wakeWordOptIn.value = enabled
        viewModelScope.launch {
            jarvisMemoryRepo.savePreference("wake_word_enabled", enabled.toString(), "VOICE", "ওয়েক-ওয়ার্ড লিসেনিং অপ্ট-ইন")
        }
        if (enabled) {
            startOfflineKeywordSpotting()
        } else {
            stopOfflineKeywordSpotting()
        }
    }

    val offlineKeywordSpotter = JarvisOfflineKeywordSpotter(
        context = application.applicationContext,
        onWakeWordTriggered = { keyword ->
            viewModelScope.launch {
                jarvisMemoryRepo.recordTaskExecution(
                    taskTitle = "অফলাইন ওয়েক-ওয়ার্ড সনাক্ত: '$keyword'",
                    taskCategory = "VOICE_COMMAND",
                    triggerSource = "WAKE_WORD",
                    rawPrompt = keyword,
                    structuredCommand = "wake_voice_session",
                    executionStatus = "SUCCESS",
                    resultSummary = "ভয়েস অ্যাসিস্ট্যান্ট সেশন স্বয়ংক্রিয়ভাবে সক্রিয় করা হয়েছে।"
                )
            }
            if (_aiLiveState.value == LiveSessionState.DISCONNECTED || _aiLiveState.value == LiveSessionState.ERROR) {
                startLiveSession()
                startMicStreaming()
            }
        }
    )

    fun startOfflineKeywordSpotting() {
        if (_wakeWordOptIn.value) {
            offlineKeywordSpotter.startListening()
        }
    }

    fun stopOfflineKeywordSpotting() {
        offlineKeywordSpotter.stopListening()
    }

    val allUserPreferences: StateFlow<List<JarvisUserPreferenceEntity>> = jarvisMemoryRepo.allPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFrequentContacts: StateFlow<List<JarvisFrequentContactEntity>> = jarvisMemoryRepo.allFrequentContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTaskHistory: StateFlow<List<JarvisTaskHistoryEntity>> = jarvisMemoryRepo.recentTaskHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveUserPreference(key: String, value: String, category: String = "GENERAL", desc: String = "") {
        viewModelScope.launch {
            jarvisMemoryRepo.savePreference(key, value, category, desc)
        }
    }

    fun saveFrequentContact(
        name: String,
        alias: String = "",
        phoneNumber: String,
        relationship: String = "",
        hasWhatsApp: Boolean = true,
        isFavorite: Boolean = false,
        notes: String = ""
    ) {
        viewModelScope.launch {
            jarvisMemoryRepo.saveContact(name, alias, phoneNumber, relationship, hasWhatsApp, isFavorite, notes)
        }
    }

    fun deleteFrequentContact(id: Long) {
        viewModelScope.launch {
            jarvisMemoryRepo.deleteContact(id)
        }
    }

    fun recordTaskHistory(
        title: String,
        category: String = "VOICE_COMMAND",
        trigger: String = "FLOATING_ORB",
        prompt: String = "",
        command: String = "",
        status: String = "SUCCESS",
        summary: String = "",
        durationMs: Long = 0L
    ) {
        viewModelScope.launch {
            jarvisMemoryRepo.recordTaskExecution(title, category, trigger, prompt, command, status, summary, durationMs)
        }
    }

    suspend fun getEnrichedAiContext(query: String = ""): String = jarvisMemoryRepo.getEnrichedContextForAi(query)

    override fun onCleared() {
        super.onCleared()
        offlineKeywordSpotter.stopListening()
        liveSessionManager.stopSession()
    }
}
