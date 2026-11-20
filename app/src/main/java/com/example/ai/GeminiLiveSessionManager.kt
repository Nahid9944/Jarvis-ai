package com.example.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    TOOL_EXECUTING,
    SPEAKING,
    ERROR
}

data class LiveTranscriptEvent(
    val speaker: String, // "USER" or "ASSISTANT"
    val text: String,
    val isTurnComplete: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolExecutionTelemetry(
    val id: String,
    val toolName: String,
    val module: String,
    val durationMs: Long,
    val status: String, // RUNNING, SUCCEEDED, PERMISSION_REQUIRED, CONFIRMATION_REQUIRED, FAILED
    val timestamp: Long = System.currentTimeMillis()
)

class GeminiLiveSessionManager(
    private val context: Context? = null,
    private val toolExecutor: AssistantToolExecutor,
    private val onAudioLevelChanged: (micLevel: Float, outputLevel: Float) -> Unit,
    private val onStatusChanged: (LiveSessionState, String) -> Unit
) {

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        private const val BASE_RECONNECT_DELAY_MS = 1500L

        fun buildWebSocketUrl(settings: AiSettingsState): String {
            if (settings.authMode == AiSettingsRepository.AUTH_MODE_EPHEMERAL_PROXY &&
                settings.ephemeralToken.isNotBlank()
            ) {
                val token = settings.ephemeralToken.trim()
                return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$token"
            }
            val key = settings.apiKey.trim()
            return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        }

        fun computeReconnectDelayMs(attempt: Int): Long {
            val safeAttempt = attempt.coerceIn(1, MAX_RECONNECT_ATTEMPTS)
            return BASE_RECONNECT_DELAY_MS * (1L shl (safeAttempt - 1))
        }

        fun isNonRetryableAuthError(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return message.contains("400") || message.contains("401") || message.contains("403")
        }

        fun buildSetupJson(
            settings: AiSettingsState,
            toolDeclarations: JSONArray,
            memoryContext: String = ""
        ): JSONObject {
            val setupObj = JSONObject()
            val setupBody = JSONObject()

            val rawModel = settings.modelName.trim().ifBlank { AiSettingsRepository.DEFAULT_MODEL }
            val modelName = if (rawModel.startsWith("models/")) rawModel else "models/$rawModel"
            setupBody.put("model", modelName)

            val genConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            genConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", settings.voiceName.ifBlank { AiSettingsRepository.DEFAULT_VOICE })
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)
            setupBody.put("generationConfig", genConfig)

            // Official Gemini Live bidirectional audio transcription config
            setupBody.put("inputAudioTranscription", JSONObject())
            setupBody.put("outputAudioTranscription", JSONObject())

            // System instructions
            val systemInstruction = JSONObject()
            val parts = JSONArray()
            val part = JSONObject()
            var fullInstruction = settings.systemInstruction.ifBlank { AiSettingsRepository.DEFAULT_SYSTEM_INSTRUCTION }
            try {
                fullInstruction += "\n\n[Central Master Authority]: ${AiAgentRegistry.JARVIS_MASTER.name} remains the Central Controller (Master Brain) across all turns; any active specialist agent operates strictly under Master risk, confirmation, and factual-truth governance."
                val activeAgent = AiAgentRegistry.getAgentById(settings.selectedAgentId)
                if (activeAgent.systemPromptExtension.isNotBlank() && !activeAgent.isController) {
                    fullInstruction += "\n\n[Active Specialized Agent Persona: ${activeAgent.name} (${activeAgent.titleBengali})]:\nRole: ${activeAgent.roleBengali}\nDirectives: ${activeAgent.systemPromptExtension}"
                }
                if (memoryContext.isNotBlank()) {
                    fullInstruction += "\n\n[Active Long-Term Memories & Custom Rules from User]:\n$memoryContext"
                }
            } catch (_: Exception) {
            }
            val langPolicy = when (settings.preferredLanguage) {
                "en" -> "\n\n[Preferred Language Policy]: Respond clearly in natural English unless the user explicitly speaks Bengali."
                "mixed" -> "\n\n[Preferred Language Policy]: Respond naturally in conversational Bengali, Banglish, or English matching the user's input language."
                else -> "\n\n[Preferred Language Policy]: Respond primarily in natural, clear Bengali (বাংলা), smoothly understanding Banglish and English commands."
            }
            val stylePolicy = when (settings.speakingStyle) {
                "SOFT" -> "\n\n[Speaking Style]: Speak in a calm, soft, gentle, reassuring feminine tone with clear Bengali pronunciation."
                "SWEET" -> "\n\n[Speaking Style]: Speak warmly and pleasantly like a kind, attentive personal assistant with sweet, natural Bengali intonation."
                "PROFESSIONAL" -> "\n\n[Speaking Style]: Speak crisply, concisely, and professionally while remaining polite and clear."
                else -> "\n\n[Speaking Style]: Speak naturally, warmly, and clearly in everyday conversational tone."
            }
            fullInstruction += langPolicy + stylePolicy
            fullInstruction = LiveConversationContextBridge.generateFullCompanionSystemPrompt(fullInstruction)
            part.put("text", fullInstruction)
            parts.put(part)
            systemInstruction.put("parts", parts)
            setupBody.put("systemInstruction", systemInstruction)

            // Function calling declarations
            val toolsArray = JSONArray()
            val toolObj = JSONObject()
            toolObj.put("functionDeclarations", toolDeclarations)
            toolsArray.put(toolObj)
            setupBody.put("tools", toolsArray)

            setupObj.put("setup", setupBody)
            return setupObj
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSessionActive = AtomicBoolean(false)
    private val isSetupReady = AtomicBoolean(false)
    private val manualStopRequested = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlightToolJobs = ConcurrentHashMap<String, Job>()

    private var audioStreamer: AudioInputStreamer? = null
    private var audioPlayer: AudioOutputPlayer? = null
    private val isEchoCooldownActive = AtomicBoolean(false)

    private val _currentState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val currentState: StateFlow<LiveSessionState> = _currentState.asStateFlow()

    private val _latestInputTranscript = MutableStateFlow("")
    val latestInputTranscript: StateFlow<String> = _latestInputTranscript.asStateFlow()

    private val _latestOutputTranscript = MutableStateFlow("")
    val latestOutputTranscript: StateFlow<String> = _latestOutputTranscript.asStateFlow()

    private val _transcriptEvents = MutableSharedFlow<LiveTranscriptEvent>(extraBufferCapacity = 32)
    val transcriptEvents: SharedFlow<LiveTranscriptEvent> = _transcriptEvents.asSharedFlow()

    private val _toolTelemetry = MutableStateFlow<List<ToolExecutionTelemetry>>(emptyList())
    val toolTelemetry: StateFlow<List<ToolExecutionTelemetry>> = _toolTelemetry.asStateFlow()

    private val currentUserTurnBuffer = StringBuilder()
    private val currentAssistantTurnBuffer = StringBuilder()

    private var currentMicLevel = 0f
    private var currentOutputLevel = 0f
    private var pendingTextMessage: String? = null
    private var activeSettings: AiSettingsState? = null

    private fun inferModuleName(toolName: String): String {
        val t = toolName.lowercase()
        return when {
            t.contains("news") || t.contains("wiki") || t.contains("search") || t.contains("github") || t.contains("youtube") -> "web_search"
            t.contains("whatsapp") || t.contains("sms") || t.contains("call") || t.contains("contact") || t.contains("email") -> "comms_bridge"
            t.contains("camera") || t.contains("screen") || t.contains("vision") || t.contains("click") || t.contains("type") -> "vision_a11y"
            t.contains("memory") || t.contains("note") || t.contains("artifact") -> "memory_docs"
            t.contains("skill") || t.contains("routine") || t.contains("autonomous") || t.contains("task") -> "automation"
            else -> "device_os"
        }
    }

    private fun recordTelemetry(entry: ToolExecutionTelemetry) {
        val current = _toolTelemetry.value.filterNot { it.id == entry.id }
        _toolTelemetry.value = (listOf(entry) + current).take(16)
    }

    fun updateActiveSessionOrReconnect(newSettings: AiSettingsState) {
        val prev = activeSettings
        activeSettings = newSettings
        audioPlayer?.volumeGain = newSettings.volumeGain
        if (!isSessionActive.get() || !isSetupReady.get() || webSocket == null || prev == null) {
            return
        }
        val structuralChange = prev.modelName != newSettings.modelName ||
            prev.voiceName != newSettings.voiceName ||
            prev.authMode != newSettings.authMode ||
            prev.apiKey != newSettings.apiKey ||
            prev.ephemeralToken != newSettings.ephemeralToken ||
            prev.proxyEndpoint != newSettings.proxyEndpoint ||
            prev.systemInstruction != newSettings.systemInstruction ||
            prev.preferredLanguage != newSettings.preferredLanguage

        if (structuralChange) {
            stopSession()
            startSession(newSettings)
        } else if (prev.selectedAgentId != newSettings.selectedAgentId) {
            val agent = AiAgentRegistry.getAgentById(newSettings.selectedAgentId)
            sendUserTextMessage(
                "[System Agent Persona Update: Switch to specialist '${agent.name}' (${agent.titleBengali}). Directives: ${agent.systemPromptExtension}]",
                newSettings
            )
        }
    }

    fun startSession(settings: AiSettingsState) {
        manualStopRequested.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        activeSettings = settings

        if (isSessionActive.get() && webSocket != null && isSetupReady.get()) {
            audioPlayer?.volumeGain = settings.volumeGain
            return
        }

        val hasEphemeralOrProxy = settings.authMode == AiSettingsRepository.AUTH_MODE_EPHEMERAL_PROXY &&
            (settings.ephemeralToken.isNotBlank() || settings.proxyEndpoint.isNotBlank())
        val key = settings.apiKey.trim()
        if (!hasEphemeralOrProxy && (key.isBlank() || key == "MY_GEMINI_API_KEY" || key.contains("YOUR_GEMINI"))) {
            updateState(LiveSessionState.ERROR, "AI API সেটআপ করা হয়নি। Settings থেকে API Key বা Ephemeral Proxy প্রদান করুন।")
            return
        }

        reconnectAttempts.set(0)
        scope.launch {
            val resolvedSettings = resolveCredentialsIfNeeded(settings)
            if (resolvedSettings == null) {
                updateState(LiveSessionState.ERROR, "Ephemeral Proxy থেকে টোকেন সংগ্রহ করা যায়নি।")
                return@launch
            }
            activeSettings = resolvedSettings
            connectWebSocketInternal(resolvedSettings)
        }
    }

    private suspend fun resolveCredentialsIfNeeded(settings: AiSettingsState): AiSettingsState? {
        if (settings.authMode != AiSettingsRepository.AUTH_MODE_EPHEMERAL_PROXY) return settings
        if (settings.ephemeralToken.isNotBlank()) return settings
        val endpoint = settings.proxyEndpoint.trim()
        if (endpoint.isBlank()) return if (settings.apiKey.isNotBlank()) settings else null

        return try {
            val req = Request.Builder().url(endpoint).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val token = json.optString("access_token")
                    .ifBlank { json.optString("token") }
                    .ifBlank { json.optString("ephemeralToken") }
                if (token.isNotBlank()) settings.copy(ephemeralToken = token) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun connectWebSocketInternal(settings: AiSettingsState) {
        if (manualStopRequested.get()) return
        updateState(LiveSessionState.CONNECTING, "সংযোগ হচ্ছে (${settings.modelName})...")

        cleanupAudio()
        try {
            webSocket?.close(1000, "Restarting session")
        } catch (_: Exception) {
        }
        webSocket = null
        isSetupReady.set(false)

        PlaybackInterruptionBridge.registerSessionManager(this)
        JarvisCameraVisionCoordinator.registerSessionManager(this)
        LiveConversationContextBridge.setFlushListener { packets ->
            val ws = webSocket ?: return@setFlushListener
            if (!isSessionActive.get() || !isSetupReady.get()) {
                throw IllegalStateException("Live WebSocket not ready")
            }
            val joined = packets.joinToString("\n") { "- [${it.dedupKey}] ${it.content}" }
            val turns = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "[LIVE CONTEXT UPDATE]:\n$joined")
                        })
                    })
                })
            }
            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", turns)
                    put("turnComplete", false)
                })
            }
            ws.send(payload.toString())
        }

        audioPlayer = AudioOutputPlayer(
            onPlaybackLevel = { level ->
                currentOutputLevel = level
                onAudioLevelChanged(currentMicLevel, currentOutputLevel)
                if (level > 0.02f && _currentState.value != LiveSessionState.SPEAKING) {
                    updateState(LiveSessionState.SPEAKING, "আমি বলছি...")
                    VoiceTurnManager.onAssistantStartedSpeaking()
                }
            },
            onPlaybackFinished = {
                VoiceTurnManager.onAssistantFinishedSpeaking()
                scope.launch {
                    isEchoCooldownActive.set(true)
                    delay(350)
                    isEchoCooldownActive.set(false)
                    if (_currentState.value == LiveSessionState.SPEAKING) {
                        updateState(LiveSessionState.LISTENING, "আমি শুনছি...")
                    }
                }
            }
        ).apply {
            volumeGain = settings.volumeGain
        }
        audioPlayer?.start()

        audioStreamer = AudioInputStreamer(
            context = context,
            onAudioChunk = { base64Audio, level ->
                currentMicLevel = level
                onAudioLevelChanged(currentMicLevel, currentOutputLevel)
                EmotionContextEngine.observeVoiceEnergySample(level)

                val isBargeIn = VoiceTurnManager.processMicrophoneChunk(level)
                if ((_currentState.value == LiveSessionState.SPEAKING || isEchoCooldownActive.get()) && !isBargeIn) {
                    return@AudioInputStreamer
                }

                if (isSessionActive.get() && isSetupReady.get() && webSocket != null) {
                    sendMediaChunk(base64Audio)
                }
            },
            onError = { errMsg ->
                updateState(LiveSessionState.ERROR, errMsg)
            }
        )

        val liveUrl = buildWebSocketUrl(settings)
        val request = Request.Builder().url(liveUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isSessionActive.set(true)
                reconnectAttempts.set(0)
                sendSetupMessage(ws, settings)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isSessionActive.set(false)
                isSetupReady.set(false)
                cleanupAudio()
                if (!manualStopRequested.get() && code != 1000 && settings.autoConnect) {
                    scheduleTransientReconnect(settings, reason.ifBlank { "অপ্রত্যাশিত সংযোগ বিচ্ছিন্ন ($code)" })
                } else {
                    val msg = if (reason.isNotBlank()) "সংযোগ বিচ্ছিন্ন: $reason" else "সংযোগ বিচ্ছিন্ন"
                    updateState(LiveSessionState.DISCONNECTED, msg)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isSessionActive.set(false)
                isSetupReady.set(false)
                cleanupAudio()
                val responseMsg = try { response?.body?.string() } catch (_: Exception) { null }
                val rawErr = "${t.message ?: ""} ${responseMsg ?: ""}"
                val msg = when {
                    rawErr.contains("403") -> "API Key বা অ্যাক্সেস অনুমতি সঠিক নয় (Error 403)"
                    rawErr.contains("401") -> "অননুমোদিত টোকেন বা অনুমোদন ব্যর্থ (Error 401)"
                    rawErr.contains("400") -> "অনুরোধে ত্রুটি বা মডেল উপলব্ধ নয় (Error 400): ${responseMsg ?: ""}"
                    rawErr.contains("429") -> "কোটা শেষ বা রেট লিমিট অতিক্রম করেছে (Error 429)"
                    else -> "সংযোগ ব্যর্থ: ${t.localizedMessage ?: responseMsg ?: "নেটওয়ার্ক বিচ্ছিন্ন"}"
                }

                if (!manualStopRequested.get() && settings.autoConnect && !isNonRetryableAuthError(rawErr)) {
                    scheduleTransientReconnect(settings, msg)
                } else {
                    updateState(LiveSessionState.ERROR, msg)
                }
            }
        })
    }

    private fun scheduleTransientReconnect(settings: AiSettingsState, reason: String) {
        val nextAttempt = reconnectAttempts.incrementAndGet()
        if (nextAttempt > MAX_RECONNECT_ATTEMPTS) {
            updateState(LiveSessionState.ERROR, "$reason (সর্বোচ্চ পুনঃসংযোগ চেষ্টা সম্পন্ন)")
            return
        }
        val delayMs = computeReconnectDelayMs(nextAttempt)
        updateState(
            LiveSessionState.CONNECTING,
            "$reason — পুনঃসংযোগ চেষ্টা চলছে ($nextAttempt/$MAX_RECONNECT_ATTEMPTS)..."
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualStopRequested.get()) {
                val refreshed = resolveCredentialsIfNeeded(settings) ?: settings
                connectWebSocketInternal(refreshed)
            }
        }
    }

    private fun sendSetupMessage(ws: WebSocket, settings: AiSettingsState) {
        scope.launch(Dispatchers.IO) {
            try {
                val memoryContext = try {
                    toolExecutor.getEnrichedContextForSession(settings.systemInstruction)
                } catch (_: Exception) {
                    try {
                        toolExecutor.getTopMemoriesForContext()
                    } catch (_: Exception) {
                        ""
                    }
                }
                val setupObj = buildSetupJson(
                    settings = settings,
                    toolDeclarations = toolExecutor.getToolDeclarations(),
                    memoryContext = memoryContext
                )
                ws.send(setupObj.toString())
            } catch (e: Exception) {
                updateState(LiveSessionState.ERROR, "সেটআপ তৈরিতে ত্রুটি: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Streams progressive knowledge stages (Stage 1: Fast Cached/Summary -> Stage 2: Deep Verified Update)
     * into an active Gemini Live session without blocking or cancelling the user's microphone turn.
     */
    fun sendProgressiveKnowledgeUpdate(stageLabel: String, summaryText: String, requestVoiceSpeech: Boolean = false) {
        val cleanSummary = summaryText.trim()
        if (cleanSummary.isBlank()) return
        val ws = webSocket
        if (!isSessionActive.get() || !isSetupReady.get() || ws == null) {
            LiveConversationContextBridge.enqueueContextUpdate(
                dedupKey = "knowledge_$stageLabel",
                content = cleanSummary
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val turns = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "[PROGRESSIVE KNOWLEDGE UPDATE - $stageLabel]: $cleanSummary")
                            })
                        })
                    })
                }
                val payload = JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", turns)
                        put("turnComplete", requestVoiceSpeech)
                    })
                }
                ws.send(payload.toString())
            } catch (_: Exception) {
                LiveConversationContextBridge.enqueueContextUpdate("knowledge_$stageLabel", cleanSummary)
            }
        }
    }

    internal fun handleServerMessage(jsonString: String) {
        scope.launch {
            try {
                val root = JSONObject(jsonString)

                if (root.has("error")) {
                    val err = root.getJSONObject("error")
                    val errMsg = err.optString("message", "সার্ভার এরর")
                    updateState(LiveSessionState.ERROR, "Gemini Live এরর: $errMsg")
                    return@launch
                }

                // 1. Setup Complete
                if (root.has("setupComplete")) {
                    isSetupReady.set(true)
                    reconnectAttempts.set(0)
                    updateState(LiveSessionState.CONNECTED, "প্রস্তুত (${activeSettings?.modelName ?: AiSettingsRepository.DEFAULT_MODEL})")

                    scope.launch(Dispatchers.IO) {
                        val ok = audioStreamer?.start() ?: false
                        if (!ok) {
                            updateState(LiveSessionState.CONNECTED, "AI প্রস্তুত (মাইক্রোফোন ছাড়া সংযুক্ত)")
                        }
                    }

                    val pending = pendingTextMessage
                    if (pending != null) {
                        pendingTextMessage = null
                        delay(100)
                        sendUserTextMessage(pending, activeSettings)
                    } else {
                        delay(200)
                        updateState(LiveSessionState.LISTENING, "আমি শুনছি... সরাসরি কথা বলুন")
                    }
                    return@launch
                }

                // 2. Official Gemini Live Tool Call Cancellation (Barge-In / Interrupt mid-tool)
                if (root.has("toolCallCancellation")) {
                    val cancellation = root.optJSONObject("toolCallCancellation")
                    val cancelledIds = cancellation?.optJSONArray("ids")
                    if (cancelledIds != null) {
                        for (i in 0 until cancelledIds.length()) {
                            val cancelledId = cancelledIds.optString(i, "")
                            if (cancelledId.isNotBlank()) {
                                inFlightToolJobs.remove(cancelledId)?.cancel()
                            }
                        }
                    }
                    audioPlayer?.interrupt()
                    updateState(LiveSessionState.LISTENING, "কাজ বাতিল করা হয়েছে — আমি শুনছি...")
                    return@launch
                }

                // 3. Tool Call / Function Calling
                if (root.has("toolCall")) {
                    updateState(LiveSessionState.TOOL_EXECUTING, "তথ্য খুঁজছি ও যাচাই করছি...")
                    val toolCall = root.getJSONObject("toolCall")
                    val functionCalls = toolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        val functionResponses = JSONArray()
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val callId = call.optString("id", "call_$i")
                            val name = call.optString("name", "")
                            val args = call.optJSONObject("args") ?: JSONObject()

                            val moduleName = inferModuleName(name)
                            val telemetryId = "${callId}_${System.currentTimeMillis()}"
                            val startMs = System.currentTimeMillis()
                            recordTelemetry(
                                ToolExecutionTelemetry(
                                    id = telemetryId,
                                    toolName = name,
                                    module = moduleName,
                                    durationMs = 0L,
                                    status = "RUNNING"
                                )
                            )

                            val job = async {
                                JarvisMasterOrchestrator.orchestrateToolCall(
                                    context = context,
                                    toolName = name,
                                    args = args,
                                    caller = "gemini_live_voice",
                                    toolExecutor = toolExecutor
                                )
                            }
                            inFlightToolJobs[callId] = job
                            val result = try {
                                job.await()
                            } finally {
                                inFlightToolJobs.remove(callId)
                            }
                            val duration = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            val telStatus = when {
                                result.optString("status") == "PERMISSION_REQUIRED" -> "PERMISSION_REQUIRED"
                                result.optString("status") == "CONFIRMATION_REQUIRED" -> "CONFIRMATION_REQUIRED"
                                result.optBoolean("success", false) -> "SUCCEEDED"
                                else -> "FAILED"
                            }
                            recordTelemetry(
                                ToolExecutionTelemetry(
                                    id = telemetryId,
                                    toolName = name,
                                    module = moduleName,
                                    durationMs = duration,
                                    status = telStatus
                                )
                            )

                            val respObj = JSONObject().apply {
                                put("id", callId)
                                put("name", name)
                                put("response", JSONObject().apply {
                                    put("output", result)
                                })
                            }
                            functionResponses.put(respObj)
                        }

                        val toolResponseMsg = JSONObject().apply {
                            put("toolResponse", JSONObject().apply {
                                put("functionResponses", functionResponses)
                            })
                        }
                        webSocket?.send(toolResponseMsg.toString())
                    }
                    return@launch
                }

                // 4. Server Content (Audio, Transcriptions, Turn Complete, Interruption)
                if (root.has("serverContent")) {
                    val serverContent = root.getJSONObject("serverContent")

                    // User input audio transcription
                    serverContent.optJSONObject("inputTranscription")?.optString("text", "")?.let { delta ->
                        if (delta.isNotBlank()) {
                            currentUserTurnBuffer.append(delta)
                            val full = currentUserTurnBuffer.toString().trim()
                            _latestInputTranscript.value = full
                            _transcriptEvents.tryEmit(LiveTranscriptEvent("USER", full, isTurnComplete = false))
                        }
                    }

                    // Assistant output audio transcription
                    serverContent.optJSONObject("outputTranscription")?.optString("text", "")?.let { delta ->
                        if (delta.isNotBlank()) {
                            currentAssistantTurnBuffer.append(delta)
                            val full = currentAssistantTurnBuffer.toString().trim()
                            _latestOutputTranscript.value = full
                            _transcriptEvents.tryEmit(LiveTranscriptEvent("ASSISTANT", full, isTurnComplete = false))
                        }
                    }

                    // Server-side barge-in interruption
                    if (serverContent.optBoolean("interrupted", false)) {
                        if (_currentState.value == LiveSessionState.SPEAKING && !isEchoCooldownActive.get()) {
                            PlaybackInterruptionBridge.onServerInterrupted()
                            audioPlayer?.interrupt()
                            finalizeAssistantTranscriptTurn()
                            updateState(LiveSessionState.LISTENING, "আমি শুনছি...")
                        }
                    }

                    val modelTurn = serverContent.optJSONObject("modelTurn")
                    if (modelTurn != null) {
                        val parts = modelTurn.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                p.optString("text", "").takeIf { it.isNotBlank() }?.let { textChunk ->
                                    if (currentAssistantTurnBuffer.isEmpty()) {
                                        currentAssistantTurnBuffer.append(textChunk)
                                        val full = currentAssistantTurnBuffer.toString().trim()
                                        _latestOutputTranscript.value = full
                                        _transcriptEvents.tryEmit(LiveTranscriptEvent("ASSISTANT", full, isTurnComplete = false))
                                    }
                                }
                                val inlineData = p.optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val dataBase64 = inlineData.optString("data", "")
                                    if (dataBase64.isNotBlank()) {
                                        val pcmBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                                        audioPlayer?.enqueueAudio(pcmBytes)
                                        if (_currentState.value != LiveSessionState.SPEAKING) {
                                            updateState(LiveSessionState.SPEAKING, "আমি বলছি...")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (serverContent.optBoolean("turnComplete", false)) {
                        finalizeUserTranscriptTurn()
                        finalizeAssistantTranscriptTurn()
                        audioPlayer?.notifyTurnComplete()
                    }
                }
            } catch (_: Exception) {
                // Ignore transient metadata parse errors
            }
        }
    }

    private fun finalizeUserTranscriptTurn() {
        val text = currentUserTurnBuffer.toString().trim()
        currentUserTurnBuffer.clear()
        if (text.isNotBlank()) {
            _transcriptEvents.tryEmit(LiveTranscriptEvent("USER", text, isTurnComplete = true))
        }
    }

    private fun finalizeAssistantTranscriptTurn() {
        val text = currentAssistantTurnBuffer.toString().trim()
        currentAssistantTurnBuffer.clear()
        if (text.isNotBlank()) {
            _transcriptEvents.tryEmit(LiveTranscriptEvent("ASSISTANT", text, isTurnComplete = true))
        }
    }

    private fun sendMediaChunk(base64Audio: String) {
        try {
            val root = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    })
                })
            }
            webSocket?.send(root.toString())
        } catch (_: Exception) {
        }
    }

    fun sendImageFrame(base64Jpeg: String) {
        if (!isSessionActive.get() || !isSetupReady.get() || webSocket == null) return
        try {
            val mediaChunks = JSONArray().apply {
                put(JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Jpeg)
                })
            }
            val root = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", mediaChunks)
                })
            }
            webSocket?.send(root.toString())
        } catch (_: Exception) {
        }
    }

    fun startMicStreaming() {
        if (isSessionActive.get() && isSetupReady.get()) {
            scope.launch(Dispatchers.IO) {
                audioStreamer?.start()
            }
        }
    }

    fun stopMicStreaming() {
        audioStreamer?.stop()
        if (_currentState.value == LiveSessionState.LISTENING) {
            updateState(LiveSessionState.CONNECTED, "মাইক্রোফোন বিরতিতে আছে")
        }
    }

    fun sendUserTextMessage(text: String, settings: AiSettingsState? = null) {
        val query = text.trim()
        if (query.isBlank()) return

        val active = settings ?: activeSettings
        if (active != null) {
            activeSettings = active
        }

        if (!isSessionActive.get() || !isSetupReady.get() || webSocket == null) {
            pendingTextMessage = query
            if (active != null) {
                startSession(active)
            }
            return
        }

        scope.launch {
            try {
                audioPlayer?.interrupt()
                updateState(LiveSessionState.THINKING, "তথ্য বুঝছি...")

                val turns = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", query) })
                        })
                    })
                }
                val root = JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", turns)
                        put("turnComplete", true)
                    })
                }
                webSocket?.send(root.toString())
            } catch (e: Exception) {
                updateState(LiveSessionState.ERROR, "বার্তা পাঠাতে ত্রুটি: ${e.localizedMessage}")
            }
        }
    }

    fun interruptPlayback() {
        isEchoCooldownActive.set(false)
        audioPlayer?.interrupt()
        if (_currentState.value == LiveSessionState.SPEAKING) {
            updateState(LiveSessionState.LISTENING, "আমি শুনছি...")
        }
    }

    fun stopSession() {
        manualStopRequested.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        inFlightToolJobs.values.forEach { it.cancel() }
        inFlightToolJobs.clear()
        currentUserTurnBuffer.clear()
        currentAssistantTurnBuffer.clear()
        isSessionActive.set(false)
        isSetupReady.set(false)
        PlaybackInterruptionBridge.unregisterSessionManager()
        LiveConversationContextBridge.setFlushListener(null)
        try {
            webSocket?.close(1000, "User stopped session")
        } catch (_: Exception) {
        }
        webSocket = null
        cleanupAudio()
        updateState(LiveSessionState.DISCONNECTED, "সংযোগ বিচ্ছিন্ন")
    }

    private fun cleanupAudio() {
        isEchoCooldownActive.set(false)
        audioStreamer?.stop()
        audioStreamer = null
        audioPlayer?.stop()
        audioPlayer = null
        currentMicLevel = 0f
        currentOutputLevel = 0f
        onAudioLevelChanged(0f, 0f)
    }

    private fun updateState(newState: LiveSessionState, message: String) {
        _currentState.value = newState
        LiveConversationContextBridge.onConnectionStateChanged(newState)
        scope.launch(Dispatchers.Main) {
            onStatusChanged(newState, message)
        }
    }
}
