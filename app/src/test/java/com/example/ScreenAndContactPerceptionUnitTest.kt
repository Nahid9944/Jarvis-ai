package com.example

import com.example.ai.ScreenPerceptionEngine
import com.example.util.JarvisContactSearchEngine
import org.junit.Assert.*
import org.junit.Test

class ScreenAndContactPerceptionUnitTest {

    @Test
    fun testBengaliDigitConversion() {
        val bengali = "০১৭ ১২৩৪ ৫৬৭৮"
        val western = JarvisContactSearchEngine.convertBengaliDigits(bengali)
        assertEquals("017 1234 5678", western)
    }

    @Test
    fun testBangladeshPhoneNormalization() {
        val local11 = "01712345678"
        assertEquals("+8801712345678", JarvisContactSearchEngine.normalizePhoneNumber(local11))

        val localBengali = "০১৭১২৩৪৫৬৭৮"
        assertEquals("+8801712345678", JarvisContactSearchEngine.normalizePhoneNumber(localBengali))

        val withCountryCode = "8801812345678"
        assertEquals("+8801812345678", JarvisContactSearchEngine.normalizePhoneNumber(withCountryCode))

        val alreadyNormalized = "+8801912345678"
        assertEquals("+8801912345678", JarvisContactSearchEngine.normalizePhoneNumber(alreadyNormalized))
    }

    @Test
    fun testIsPurePhoneNumberDetection() {
        assertTrue(JarvisContactSearchEngine.isPurePhoneNumber("01712345678"))
        assertTrue(JarvisContactSearchEngine.isPurePhoneNumber("+8801712345678"))
        assertTrue(JarvisContactSearchEngine.isPurePhoneNumber("০১৮১২৩৪৫৬৭৮"))

        assertFalse(JarvisContactSearchEngine.isPurePhoneNumber("Rahim"))
        assertFalse(JarvisContactSearchEngine.isPurePhoneNumber("বাবা"))
        assertFalse(JarvisContactSearchEngine.isPurePhoneNumber("Maa"))
        assertFalse(JarvisContactSearchEngine.isPurePhoneNumber("Doctor Smith"))
    }

    @Test
    fun testScreenPerceptionSensitiveDataSanitization() {
        val cardText = "Please confirm payment of $50 on card 4123-4567-8901-2345 now."
        val sanitizedCard = ScreenPerceptionEngine.sanitizeText(cardText)
        assertTrue(sanitizedCard.contains("[REDACTED_CARD]"))
        assertFalse(sanitizedCard.contains("4123-4567-8901-2345"))

        val otpText = "Your verification OTP is 849201. Do not share."
        val sanitizedOtp = ScreenPerceptionEngine.sanitizeText(otpText)
        assertTrue(sanitizedOtp.contains("[REDACTED_OTP]"))
        assertFalse(sanitizedOtp.contains("849201"))

        val bengaliOtp = "আপনার লগইন কোড: ৯৩৮৪"
        // Ensure standard text without leak
        assertNotNull(ScreenPerceptionEngine.sanitizeText(bengaliOtp))
    }

    @Test
    fun testScreenPerceptionSafeTextPreservation() {
        val safeText = "WhatsApp messages are end-to-end encrypted."
        val result = ScreenPerceptionEngine.sanitizeText(safeText)
        assertEquals(safeText, result)
    }

    @Test
    fun testAutonomousLoopModelAndJsonSerialization() {
        val step1 = com.example.automation.AutonomousLoopStep(
            stepIndex = 1,
            phase = "UNDERSTAND",
            action = "parse_intent",
            target = "SendMessage",
            success = true,
            observation = "Intent parsed"
        )
        val step2 = com.example.automation.AutonomousLoopStep(
            stepIndex = 2,
            phase = "ACT",
            action = "click",
            target = "Send",
            success = true,
            observation = "Clicked send button"
        )
        val execution = com.example.automation.AutonomousLoopExecution(
            success = true,
            goal = "Send WhatsApp message to Rahim",
            stepsTaken = listOf(step1, step2),
            verified = true,
            summaryBengali = "বার্তা সফলভাবে পাঠানো হয়েছে।",
            finalState = "SUCCEEDED"
        )

        val json = com.example.automation.JarvisAutonomousLoopOrchestrator.toJsonObject(execution)
        assertTrue(json.getBoolean("success"))
        assertTrue(json.getBoolean("verified"))
        assertEquals("SUCCEEDED", json.getString("finalState"))
        assertEquals(2, json.getJSONArray("steps").length())
        assertEquals("UNDERSTAND", json.getJSONArray("steps").getJSONObject(0).getString("phase"))
    }

    @Test
    fun testSmsDispatchResultContract() {
        val res = com.example.util.SmsDispatchResult(
            success = true,
            status = com.example.util.JarvisSmsManager.STATUS_SEND_REQUEST_ACCEPTED,
            directSend = true,
            partsCount = 2,
            recipient = "+8801712345678",
            messageSnippet = "Hello Bangladesh",
            summaryBengali = "সফল বার্তা"
        )
        assertTrue(res.success)
        assertTrue(res.directSend)
        assertEquals(2, res.partsCount)
        assertEquals("+8801712345678", res.recipient)
        assertEquals("SEND_REQUEST_ACCEPTED", res.status)
    }

    @Test
    fun testEmergencyStopCoordinatorReporting() {
        val report = com.example.util.JarvisEmergencyStopCoordinator.triggerEmergencyStop(null)
        assertTrue(report.success)
        assertTrue(report.autonomousLoopHalted)
        assertTrue(report.skillRecorderHalted)
        assertTrue(report.cameraVisionReset)
        assertTrue(report.summaryBengali.contains("জরুরি বিরতি সক্রিয়"))
        val json = report.toJson()
        assertTrue(json.getBoolean("success"))
        assertTrue(json.getBoolean("autonomousLoopHalted"))
        com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @Test
    fun testToolResultVerifierZeroFakeSuccess() {
        val rawFailing = org.json.JSONObject().apply {
            put("error", "Simulated failure")
        }
        val spec = com.example.tools.ToolRegistry.get("setBrightness")
        assertNotNull(spec)
        val dummyArgs = org.json.JSONObject()
        val verifiedFailing = com.example.tools.ToolResultVerifier.verifyAndEnrich("setBrightness", spec, dummyArgs, rawFailing)
        assertFalse(verifiedFailing.optBoolean("verified", false))

        val rawExplicitSuccess = org.json.JSONObject().apply {
            put("success", true)
            put("brightnessPercent", 75)
        }
        val verifiedSuccess = com.example.tools.ToolResultVerifier.verifyAndEnrich("setBrightness", spec, dummyArgs, rawExplicitSuccess)
        assertTrue(verifiedSuccess.optBoolean("verified", false))
    }

    @Test
    fun testAiAgentRegistryResolution() {
        val master = com.example.ai.AiAgentRegistry.getAgentById("jarvis_master")
        assertEquals("jarvis_master", master.id)

        val friday = com.example.ai.AiAgentRegistry.getAgentById("friday_agent")
        assertEquals("friday_agent", friday.id)

        val fallback = com.example.ai.AiAgentRegistry.getAgentById("unknown_invalid_agent")
        assertEquals(com.example.ai.AiAgentRegistry.JARVIS_MASTER.id, fallback.id)

        assertEquals(12, com.example.ai.AiAgentRegistry.ALL_AGENTS.size)
    }
}

