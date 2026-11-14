package com.example

import com.example.ai.AiSettingsRepository
import com.example.ai.AiSettingsState
import com.example.ai.AssistantToolExecutor
import com.example.ai.GeminiLiveSessionManager
import com.example.ai.JarvisCinematicConfig
import com.example.ai.LiveSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2LiveVoiceUpgradeTest {

    @Test
    fun buildWebSocketUrl_usesV1BetaAndEphemeralConstrainedEndpointWhenConfigured() {
        val apiKeySettings = AiSettingsState(
            apiKey = "AIzaTestKey12345678901234567890",
            modelName = AiSettingsRepository.DEFAULT_MODEL,
            voiceName = "Aoede",
            systemInstruction = JarvisCinematicConfig.MASTER_SYSTEM_INSTRUCTION,
            autoConnect = true
        )
        val directUrl = GeminiLiveSessionManager.buildWebSocketUrl(apiKeySettings)
        assertTrue(directUrl.contains("v1beta.GenerativeService.BidiGenerateContent"))
        assertTrue(directUrl.endsWith("?key=AIzaTestKey12345678901234567890"))

        val ephemeralSettings = apiKeySettings.copy(
            authMode = AiSettingsRepository.AUTH_MODE_EPHEMERAL_PROXY,
            ephemeralToken = "eph_tok_999"
        )
        val ephemeralUrl = GeminiLiveSessionManager.buildWebSocketUrl(ephemeralSettings)
        assertTrue(ephemeralUrl.contains("v1beta.GenerativeService.BidiGenerateContentConstrained"))
        assertTrue(ephemeralUrl.endsWith("?access_token=eph_tok_999"))
    }

    @Test
    fun buildSetupJson_includesLiveModelAudioConfigAndBidirectionalTranscription() {
        val settings = AiSettingsState(
            apiKey = "AIzaTestKey12345678901234567890",
            modelName = "gemini-3.8-live",
            voiceName = "Kore",
            systemInstruction = JarvisCinematicConfig.MASTER_SYSTEM_INSTRUCTION,
            autoConnect = true
        )
        val setup = GeminiLiveSessionManager.buildSetupJson(settings, JSONArray()).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertTrue(setup.has("generationConfig"))
        assertTrue(setup.has("tools"))
    }

    @Test
    fun exponentialBackoffAndAuthClassification_workCorrectly() {
        assertEquals(1500L, GeminiLiveSessionManager.computeReconnectDelayMs(1))
        assertEquals(3000L, GeminiLiveSessionManager.computeReconnectDelayMs(2))
        assertEquals(6000L, GeminiLiveSessionManager.computeReconnectDelayMs(3))

        assertTrue(GeminiLiveSessionManager.isNonRetryableAuthError("HTTP 403 Forbidden"))
        assertTrue(GeminiLiveSessionManager.isNonRetryableAuthError("Error 401"))
        assertFalse(GeminiLiveSessionManager.isNonRetryableAuthError("SocketTimeoutException"))
    }

    @Test
    fun handleServerMessage_parsesInputAndOutputTranscriptions() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.example.data.local.AssistantDatabase::class.java
        ).allowMainThreadQueries().build()

        val executor = AssistantToolExecutor(db.assistantDao(), onNavigate = {}, context = null)
        val mgr = GeminiLiveSessionManager(null, executor, { _, _ -> }, { _, _ -> })

        val serverMsg = JSONObject().apply {
            put("serverContent", JSONObject().apply {
                put("inputTranscription", JSONObject().put("text", "আজ আবহাওয়া "))
                put("outputTranscription", JSONObject().put("text", "ঢাকায় ২৮ ডিগ্রি।"))
                put("turnComplete", true)
            })
        }

        mgr.handleServerMessage(serverMsg.toString())
        delay(100)

        assertEquals("আজ আবহাওয়া", mgr.latestInputTranscript.value)
        assertEquals("ঢাকায় ২৮ ডিগ্রি।", mgr.latestOutputTranscript.value)
        assertEquals(LiveSessionState.DISCONNECTED, mgr.currentState.value)
        db.close()
    }
}
