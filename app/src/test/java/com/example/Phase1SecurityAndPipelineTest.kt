package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiSettingsRepository
import com.example.permissions.JarvisPermissionManager
import com.example.security.SecureKeystoreVault
import com.example.tools.ToolExecutionPipeline
import com.example.viewmodel.AssistantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase1SecurityAndPipelineTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("nahid_auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("nahid_ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        JarvisPermissionManager.resetForTest()
    }

    @Test
    fun pinHashingAndVerification_usesSaltedPbkdf2WithoutPlaintext() {
        val hash1 = SecureKeystoreVault.hashPinWithSalt("2580")
        val hash2 = SecureKeystoreVault.hashPinWithSalt("2580")

        assertTrue(SecureKeystoreVault.isHashedPin(hash1))
        assertNotEquals("Salted hashes must be unique per salt", hash1, hash2)
        assertFalse(hash1.contains("2580"))
        assertTrue(SecureKeystoreVault.verifyPin("2580", hash1))
        assertFalse(SecureKeystoreVault.verifyPin("994400", hash1))
        assertFalse(SecureKeystoreVault.verifyPin("", hash1))
    }

    @Test
    fun aiSettingsRepository_encryptsApiKeyAndSupportsModelSelection() {
        val repo = AiSettingsRepository(app)
        val fakeKey = "AIzaSyTestFakeKey12345678901234567890"
        repo.saveSettings(
            apiKey = fakeKey,
            modelName = AiSettingsRepository.DEFAULT_MODEL,
            voiceName = "Aoede",
            systemInstruction = AiSettingsRepository.DEFAULT_SYSTEM_INSTRUCTION,
            autoConnect = true
        )

        val prefs = app.getSharedPreferences("nahid_ai_settings", Context.MODE_PRIVATE)
        val rawStored = prefs.getString("gemini_api_key", "") ?: ""
        assertTrue("Stored API key must be encrypted", SecureKeystoreVault.isEncryptedPayload(rawStored))
        assertFalse("Plaintext API key must never sit in SharedPreferences", rawStored.contains(fakeKey))

        val reloaded = AiSettingsRepository(app)
        assertEquals(fakeKey, reloaded.settingsState.value.apiKey)
        assertEquals("gemini-3.8-live", reloaded.settingsState.value.modelName)
    }

    @Test
    fun toolExecutionPipeline_failsClosedWhenContextIsNullForPermissionedTool() = runBlocking(Dispatchers.IO) {
        val res = ToolExecutionPipeline.execute(
            toolName = "makePhoneCall",
            args = JSONObject().put("phoneNumber", "01711000000"),
            context = null,
            caller = "voice"
        ) { _, _ ->
            fail("Executor must never run when context is null on a permissioned tool")
            JSONObject().put("success", true)
        }

        assertEquals("PERMISSION_REQUIRED", res.optString("status"))
        assertFalse(res.optBoolean("success", true))
        assertFalse(res.optBoolean("verified", true))
    }

    @Test
    fun viewModel_lockPinEnforcesLockStateAndWakeWordOptInDefaultsFalse() {
        val vm = AssistantViewModel(app)
        assertFalse("Wake word must default to opt-in false", vm.wakeWordOptIn.value)
        assertTrue("Unlocked by default when user has not set custom PIN", vm.isLoggedIn.value)

        vm.saveCredentials("Nahid", "4567")
        val prefs = app.getSharedPreferences("nahid_auth_prefs", Context.MODE_PRIVATE)
        assertNull("Plaintext saved_password must never exist", prefs.getString("saved_password", null))
        assertTrue("Hashed PIN must exist", SecureKeystoreVault.isHashedPin(prefs.getString("saved_pin_hash", null)))

        // Simulate cold relaunch with saved PIN
        val vm2 = AssistantViewModel(app)
        assertTrue(vm2.hasLockPin.value)
        assertFalse("App must start locked when custom PIN exists", vm2.isLoggedIn.value)
        assertFalse(vm2.verifyLockPin("0000"))
        assertFalse(vm2.isLoggedIn.value)
        assertTrue(vm2.verifyLockPin("4567"))
        assertTrue(vm2.isLoggedIn.value)
    }
}
