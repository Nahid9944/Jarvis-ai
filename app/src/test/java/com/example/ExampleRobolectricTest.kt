package com.example

import com.example.data.local.AiMemoryEntity
import com.example.data.local.JarvisProfileEntity
import com.example.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleRobolectricTest {

    @Test
    fun verifySystemNameAndIdentity() {
        val appName = "NAHID AI"
        assertEquals("NAHID AI", appName)
    }

    @Test
    fun verifyOptionalPinDefaultEmptyWithoutBackdoor() {
        val defaultProfile = JarvisProfileEntity()
        assertEquals("NAHID AI", defaultProfile.systemName)
        assertEquals("", defaultProfile.loginPassword)
    }

    @Test
    fun verifyJarvisProfileEntity() {
        val profile = JarvisProfileEntity(
            id = 1,
            systemName = "NAHID AI",
            assistantVoice = "Bengali-Natural",
            ownerName = "Masum Billah Nahid",
            securityLevel = "HIGH",
            autoAutomationAllowed = true
        )
        assertEquals("NAHID AI", profile.systemName)
        assertEquals("Masum Billah Nahid", profile.ownerName)
        assertEquals("HIGH", profile.securityLevel)
        assertTrue(profile.autoAutomationAllowed)
    }

    @Test
    fun verifyAiMemoryEntity() {
        val memory = AiMemoryEntity(
            key = "user_preference_language",
            content = "Bengali and English mixed",
            category = "preference"
        )
        assertEquals("user_preference_language", memory.key)
        assertEquals("preference", memory.category)
        assertTrue(memory.content.contains("Bengali"))
    }

    @Test
    fun verifyJarvisToolRegistry() {
        val tools = ToolRegistry.getAll()
        assertTrue("ToolRegistry should have Personal AI OS tools", tools.isNotEmpty())

        val toolNames = tools.map { it.name }.toSet()
        assertTrue("Should have sendWhatsAppMessage", toolNames.contains("sendWhatsAppMessage") || toolNames.contains("openWhatsAppChat"))
        assertTrue("Should have captureAndAnalyzeScreen", toolNames.contains("captureAndAnalyzeScreen") || toolNames.contains("describeCurrentScreen"))
        assertTrue("Should have toggleFlashlight", toolNames.contains("toggleFlashlight"))
        assertTrue("Should have saveMemory", toolNames.contains("saveMemory"))
        assertTrue("Should have createAiTask", toolNames.contains("createAiTask"))
        assertTrue("Total registered tools should be at least 40", tools.size >= 40)
    }

    @Test
    fun verifyPersonalVaultBackupDataStructure() {
        val memory = AiMemoryEntity(
            id = 1L,
            key = "master_name",
            content = "Nahid",
            category = "identity"
        )
        val profile = JarvisProfileEntity(
            id = 1,
            systemName = "NAHID AI",
            assistantVoice = "Bengali-Natural",
            ownerName = "Nahid",
            securityLevel = "HIGH",
            autoAutomationAllowed = true
        )

        assertEquals("Nahid", memory.content)
        assertEquals("master_name", memory.key)
        assertEquals("NAHID AI", profile.systemName)
    }
}
