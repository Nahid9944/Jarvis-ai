package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.security.SecureKeystoreVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiSettingsState(
    val apiKey: String,
    val modelName: String,
    val voiceName: String,
    val systemInstruction: String,
    val autoConnect: Boolean,
    val voiceVolumeLevel: String = "NORMAL", // "NORMAL" (1.0x pure clarity) or "LOUD" (1.15x soft limiter)
    val selectedAgentId: String = "jarvis_master",
    val authMode: String = AiSettingsRepository.AUTH_MODE_API_KEY, // "API_KEY" or "EPHEMERAL_PROXY"
    val ephemeralToken: String = "",
    val proxyEndpoint: String = "",
    val braveSearchApiKey: String = "",
    val tavilySearchApiKey: String = "",
    val githubToken: String = "",
    val preferredLanguage: String = AiSettingsRepository.DEFAULT_LANGUAGE,
    val offlineTtsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val silentMode: Boolean = false,
    val speakingStyle: String = AiSettingsRepository.DEFAULT_SPEAKING_STYLE,
    val knowledgeServerUrl: String = "",
    val knowledgeServerToken: String = ""
) {
    val isApiKeyConfigured: Boolean
        get() = if (authMode == AiSettingsRepository.AUTH_MODE_EPHEMERAL_PROXY) {
            ephemeralToken.isNotBlank() || proxyEndpoint.isNotBlank()
        } else {
            apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY" && !apiKey.contains("YOUR_GEMINI")
        }

    val isWebSearchKeyConfigured: Boolean
        get() = braveSearchApiKey.isNotBlank() || tavilySearchApiKey.isNotBlank()

    val volumeGain: Float
        get() = if (voiceVolumeLevel == "LOUD") 1.15f else 1.0f

    val maskedApiKey: String
        get() = if (apiKey.length > 8) {
            "${apiKey.take(4)}••••••••${apiKey.takeLast(4)}"
        } else if (apiKey.isNotBlank()) {
            "••••••••"
        } else {
            "সেট করা নেই"
        }
}

class AiSettingsRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences = appContext.getSharedPreferences("nahid_ai_settings", Context.MODE_PRIVATE).also { target ->
        val legacy = appContext.getSharedPreferences("janani_ai_settings", Context.MODE_PRIVATE)
        if (target.all.isEmpty() && legacy.all.isNotEmpty()) {
            val editor = target.edit()
            legacy.all.forEach { (k, v) ->
                when (v) {
                    is String -> editor.putString(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                }
            }
            editor.apply()
            legacy.edit().clear().apply()
        }
    }

    companion object {
        const val KEY_ALIAS_API_KEY = "nahid_ai_gemini_api_key"
        const val KEY_ALIAS_EPHEMERAL_TOKEN = "nahid_ai_ephemeral_token"
        const val KEY_ALIAS_BRAVE_SEARCH = "nahid_ai_brave_search_key"
        const val KEY_ALIAS_TAVILY_SEARCH = "nahid_ai_tavily_search_key"
        const val KEY_ALIAS_GITHUB_PAT = "nahid_ai_github_pat"
        const val KEY_ALIAS_KNOWLEDGE_TOKEN = "nahid_ai_knowledge_server_token"

        const val AUTH_MODE_API_KEY = "API_KEY"
        const val AUTH_MODE_EPHEMERAL_PROXY = "EPHEMERAL_PROXY"

        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val FALLBACK_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val DEFAULT_VOICE = "Aoede"
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_LANGUAGE = "bn"
        const val DEFAULT_SPEAKING_STYLE = "NATURAL"
        val AVAILABLE_LANGUAGES = listOf("bn", "en", "mixed")
        val AVAILABLE_SPEAKING_STYLES = listOf("NATURAL", "SOFT", "SWEET", "PROFESSIONAL")
        val AVAILABLE_VOICES = listOf("Aoede", "Leda", "Kore", "Zephyr", "Puck", "Charon", "Fenrir")
        val VOICE_DESCRIPTIONS_BN = mapOf(
            "Aoede" to "কোমল ও উষ্ণ (নারী)",
            "Leda" to "মিষ্টি ও প্রাণবন্ত (নারী)",
            "Kore" to "স্পষ্ট ও দৃঢ় (নারী)",
            "Zephyr" to "উজ্জ্বল ও মসৃণ (নারী)",
            "Puck" to "চটপটে (পুরুষ)",
            "Charon" to "গভীর (পুরুষ)",
            "Fenrir" to "উচ্চশক্তি (পুরুষ)"
        )
        val AVAILABLE_MODELS = listOf(
            DEFAULT_MODEL,
            FALLBACK_LIVE_MODEL
        )

        val DEFAULT_SYSTEM_INSTRUCTION = JarvisCinematicConfig.MASTER_SYSTEM_INSTRUCTION
    }

    private val _settingsState = MutableStateFlow(loadSettings())
    val settingsState: StateFlow<AiSettingsState> = _settingsState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun readEncryptedSecret(prefKey: String, alias: String): String {
        val raw = prefs.getString(prefKey, null) ?: return ""
        if (raw.isBlank()) return ""
        val decrypted = SecureKeystoreVault.decryptString(appContext, alias, raw)
        if (!SecureKeystoreVault.isEncryptedPayload(raw) && decrypted.isNotBlank()) {
            // Auto-migrate legacy plaintext value to Android Keystore encrypted payload
            val sealed = SecureKeystoreVault.encryptString(appContext, alias, decrypted)
            prefs.edit().putString(prefKey, sealed).apply()
        }
        return decrypted
    }

    private fun writeEncryptedSecret(prefKey: String, alias: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            prefs.edit().remove(prefKey).apply()
        } else {
            val sealed = SecureKeystoreVault.encryptString(appContext, alias, clean)
            prefs.edit().putString(prefKey, sealed).apply()
        }
    }

    private fun loadSettings(): AiSettingsState {
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY.takeIf {
                it.isNotBlank() && it != "MY_GEMINI_API_KEY" && !it.contains("YOUR_GEMINI")
            } ?: ""
        } catch (_: Throwable) {
            ""
        }

        val savedKey = readEncryptedSecret("gemini_api_key", KEY_ALIAS_API_KEY)
        val key = if (savedKey.isNotBlank() && savedKey != "MY_GEMINI_API_KEY" && !savedKey.contains("YOUR_GEMINI") && savedKey.length > 20) {
            savedKey
        } else if (buildConfigKey.isNotBlank() && buildConfigKey.length > 20) {
            buildConfigKey
        } else {
            DEFAULT_API_KEY
        }

        val savedModel = prefs.getString("gemini_model_name", null)?.trim()
        val model = if (!savedModel.isNullOrBlank() && AVAILABLE_MODELS.contains(savedModel)) {
            savedModel
        } else {
            prefs.edit().putString("gemini_model_name", DEFAULT_MODEL).apply()
            DEFAULT_MODEL
        }

        val voice = prefs.getString("gemini_voice_name", DEFAULT_VOICE) ?: DEFAULT_VOICE
        val savedPrompt = prefs.getString("gemini_system_prompt", null)
        val systemPrompt = if (savedPrompt.isNullOrBlank() || savedPrompt.length < 50) {
            prefs.edit().putString("gemini_system_prompt", DEFAULT_SYSTEM_INSTRUCTION).apply()
            DEFAULT_SYSTEM_INSTRUCTION
        } else {
            savedPrompt
        }
        val autoConnect = prefs.getBoolean("gemini_auto_connect", true)
        val volumeLevel = prefs.getString("gemini_volume_level", "NORMAL") ?: "NORMAL"
        val savedAgentId = prefs.getString("selected_ai_agent_id", "jarvis_master") ?: "jarvis_master"
        val authMode = prefs.getString("gemini_auth_mode", AUTH_MODE_API_KEY)
            ?.takeIf { it == AUTH_MODE_API_KEY || it == AUTH_MODE_EPHEMERAL_PROXY }
            ?: AUTH_MODE_API_KEY
        val ephemeralToken = readEncryptedSecret("gemini_ephemeral_token", KEY_ALIAS_EPHEMERAL_TOKEN)
        val proxyEndpoint = prefs.getString("gemini_proxy_endpoint", "") ?: ""
        val braveSearchKey = readEncryptedSecret("brave_search_api_key", KEY_ALIAS_BRAVE_SEARCH)
        val tavilySearchKey = readEncryptedSecret("tavily_search_api_key", KEY_ALIAS_TAVILY_SEARCH)
        val githubPat = readEncryptedSecret("github_pat_token", KEY_ALIAS_GITHUB_PAT)
        val preferredLanguage = prefs.getString("gemini_preferred_language", DEFAULT_LANGUAGE)
            ?.takeIf { it in AVAILABLE_LANGUAGES }
            ?: DEFAULT_LANGUAGE
        val offlineTtsEnabled = prefs.getBoolean("jarvis_offline_tts_enabled", true)
        val ttsSpeechRate = prefs.getFloat("jarvis_tts_speech_rate", 1.0f).coerceIn(0.6f, 1.8f)
        val silentMode = prefs.getBoolean("jarvis_voice_silent_mode", false)
        val speakingStyle = prefs.getString("gemini_speaking_style", DEFAULT_SPEAKING_STYLE)
            ?.takeIf { it in AVAILABLE_SPEAKING_STYLES }
            ?: DEFAULT_SPEAKING_STYLE
        val knowledgeServerUrl = prefs.getString("knowledge_server_url", "") ?: ""
        val knowledgeServerToken = readEncryptedSecret("knowledge_server_token", KEY_ALIAS_KNOWLEDGE_TOKEN)

        com.example.util.JarvisVoiceSpeaker.configureVoiceSettings(
            offlineEnabled = offlineTtsEnabled,
            speechRate = ttsSpeechRate,
            silentMode = silentMode,
            preferredLanguage = preferredLanguage
        )

        return AiSettingsState(
            apiKey = key,
            modelName = model,
            voiceName = voice,
            systemInstruction = systemPrompt,
            autoConnect = autoConnect,
            voiceVolumeLevel = volumeLevel,
            selectedAgentId = savedAgentId,
            authMode = authMode,
            ephemeralToken = ephemeralToken,
            proxyEndpoint = proxyEndpoint,
            braveSearchApiKey = braveSearchKey,
            tavilySearchApiKey = tavilySearchKey,
            githubToken = githubPat,
            preferredLanguage = preferredLanguage,
            offlineTtsEnabled = offlineTtsEnabled,
            ttsSpeechRate = ttsSpeechRate,
            silentMode = silentMode,
            speakingStyle = speakingStyle,
            knowledgeServerUrl = knowledgeServerUrl,
            knowledgeServerToken = knowledgeServerToken
        )
    }

    fun getSelectedAgentId(): String {
        return prefs.getString("selected_ai_agent_id", "jarvis_master") ?: "jarvis_master"
    }

    fun setSelectedAgentId(agentId: String) {
        val cleanId = agentId.trim().ifBlank { "jarvis_master" }
        prefs.edit().putString("selected_ai_agent_id", cleanId).apply()
        _settingsState.value = _settingsState.value.copy(selectedAgentId = cleanId)
    }

    fun saveSettings(
        apiKey: String,
        modelName: String,
        voiceName: String,
        systemInstruction: String,
        autoConnect: Boolean,
        voiceVolumeLevel: String = "NORMAL",
        authMode: String = _settingsState.value.authMode,
        ephemeralToken: String = _settingsState.value.ephemeralToken,
        proxyEndpoint: String = _settingsState.value.proxyEndpoint,
        braveSearchApiKey: String = _settingsState.value.braveSearchApiKey,
        tavilySearchApiKey: String = _settingsState.value.tavilySearchApiKey,
        githubToken: String = _settingsState.value.githubToken,
        preferredLanguage: String = _settingsState.value.preferredLanguage,
        offlineTtsEnabled: Boolean = _settingsState.value.offlineTtsEnabled,
        ttsSpeechRate: Float = _settingsState.value.ttsSpeechRate,
        silentMode: Boolean = _settingsState.value.silentMode,
        speakingStyle: String = _settingsState.value.speakingStyle,
        knowledgeServerUrl: String = _settingsState.value.knowledgeServerUrl,
        knowledgeServerToken: String = _settingsState.value.knowledgeServerToken
    ) {
        val trimmedKey = apiKey.trim()
        val chosenModel = modelName.trim().takeIf { AVAILABLE_MODELS.contains(it) } ?: DEFAULT_MODEL
        val normalizedAuthMode = if (authMode == AUTH_MODE_EPHEMERAL_PROXY) {
            AUTH_MODE_EPHEMERAL_PROXY
        } else {
            AUTH_MODE_API_KEY
        }
        val normalizedLang = preferredLanguage.trim().takeIf { it in AVAILABLE_LANGUAGES } ?: DEFAULT_LANGUAGE
        val normalizedRate = ttsSpeechRate.coerceIn(0.6f, 1.8f)
        val normalizedStyle = speakingStyle.trim().takeIf { it in AVAILABLE_SPEAKING_STYLES } ?: DEFAULT_SPEAKING_STYLE

        writeEncryptedSecret("gemini_api_key", KEY_ALIAS_API_KEY, trimmedKey)
        writeEncryptedSecret("gemini_ephemeral_token", KEY_ALIAS_EPHEMERAL_TOKEN, ephemeralToken)
        writeEncryptedSecret("brave_search_api_key", KEY_ALIAS_BRAVE_SEARCH, braveSearchApiKey)
        writeEncryptedSecret("tavily_search_api_key", KEY_ALIAS_TAVILY_SEARCH, tavilySearchApiKey)
        writeEncryptedSecret("github_pat_token", KEY_ALIAS_GITHUB_PAT, githubToken)
        writeEncryptedSecret("knowledge_server_token", KEY_ALIAS_KNOWLEDGE_TOKEN, knowledgeServerToken)

        prefs.edit()
            .putString("gemini_model_name", chosenModel)
            .putString("gemini_voice_name", voiceName.trim().ifBlank { DEFAULT_VOICE })
            .putString("gemini_system_prompt", systemInstruction.trim().ifBlank { DEFAULT_SYSTEM_INSTRUCTION })
            .putBoolean("gemini_auto_connect", autoConnect)
            .putString("gemini_volume_level", voiceVolumeLevel)
            .putString("gemini_auth_mode", normalizedAuthMode)
            .putString("gemini_proxy_endpoint", proxyEndpoint.trim())
            .putString("gemini_preferred_language", normalizedLang)
            .putBoolean("jarvis_offline_tts_enabled", offlineTtsEnabled)
            .putFloat("jarvis_tts_speech_rate", normalizedRate)
            .putBoolean("jarvis_voice_silent_mode", silentMode)
            .putString("gemini_speaking_style", normalizedStyle)
            .putString("knowledge_server_url", knowledgeServerUrl.trim())
            .apply()

        _settingsState.value = loadSettings()
    }

    fun getGitHubToken(): String = _settingsState.value.githubToken
    fun getBraveSearchApiKey(): String = _settingsState.value.braveSearchApiKey
    fun getTavilySearchApiKey(): String = _settingsState.value.tavilySearchApiKey
    fun getKnowledgeServerUrl(): String = _settingsState.value.knowledgeServerUrl
    fun getKnowledgeServerToken(): String = _settingsState.value.knowledgeServerToken

    fun updateSystemInstruction(instruction: String) {
        val curr = _settingsState.value
        saveSettings(
            apiKey = curr.apiKey,
            modelName = curr.modelName,
            voiceName = curr.voiceName,
            systemInstruction = instruction,
            autoConnect = curr.autoConnect,
            voiceVolumeLevel = curr.voiceVolumeLevel,
            authMode = curr.authMode,
            ephemeralToken = curr.ephemeralToken,
            proxyEndpoint = curr.proxyEndpoint,
            preferredLanguage = curr.preferredLanguage,
            offlineTtsEnabled = curr.offlineTtsEnabled,
            ttsSpeechRate = curr.ttsSpeechRate,
            silentMode = curr.silentMode,
            speakingStyle = curr.speakingStyle
        )
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _settingsState.value = loadSettings()
    }

    suspend fun testConnection(apiKey: String, modelName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val current = _settingsState.value
        val key = apiKey.trim()
        if (current.authMode == AUTH_MODE_EPHEMERAL_PROXY && current.proxyEndpoint.isNotBlank()) {
            return@withContext try {
                val request = Request.Builder().url(current.proxyEndpoint.trim()).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Pair(true, "Ephemeral Proxy সংযোগ সফল (${response.code})!")
                    } else {
                        Pair(false, "Ephemeral Proxy ত্রুটি (${response.code})")
                    }
                }
            } catch (e: Exception) {
                Pair(false, "Proxy সংযোগ ব্যর্থ: ${e.localizedMessage ?: "নেটওয়ার্ক বিচ্ছিন্ন"}")
            }
        }

        if (key.isBlank() || key == "MY_GEMINI_API_KEY") {
            return@withContext Pair(false, "API Key ফাঁকা রাখা যাবে না")
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val root = JSONObject(body)
                    val models = root.optJSONArray("models")
                    val count = models?.length() ?: 0
                    Pair(true, "সংযোগ সফল! $count টি মডেল পাওয়া গেছে (সক্রিয়: $modelName)।")
                } else {
                    val errBody = response.body?.string() ?: ""
                    val code = response.code
                    val msg = when (code) {
                        400 -> "API অনুরোধ সঠিক নয় (Error 400)"
                        403 -> "API Key ভুল বা অ্যাক্সেস নেই (Error 403)"
                        404 -> "মডেল পাওয়া যায়নি (Error 404)"
                        429 -> "কোটা শেষ বা সীমা অতিক্রম করেছে (Error 429)"
                        else -> "সার্ভার এরর ($code): $errBody"
                    }
                    Pair(false, msg)
                }
            }
        } catch (e: Exception) {
            Pair(false, "সংযোগ ব্যর্থ: ${e.localizedMessage ?: "নেটওয়ার্ক বিচ্ছিন্ন"}")
        }
    }
}
