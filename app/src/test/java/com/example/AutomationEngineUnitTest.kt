package com.example

import com.example.automation.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutomationEngineUnitTest {

    @Before
    fun setUp() {
        RateLimiter.reset()
        Idempotency.reset()
        HumanGate.autoApproveForTest = null
    }

    @Test
    fun playbookStore_containsAllRegisteredAppPlaybooks() {
        val all = PlaybookStore.getAll()
        assertTrue("PlaybookStore must contain registered playbooks", all.isNotEmpty())

        val waSend = PlaybookStore.get("wa.send_text")
        assertNotNull("wa.send_text must exist", waSend)
        assertEquals("com.whatsapp", waSend?.appPackage)

        val ytSearch = PlaybookStore.get("yt.search_play")
        assertNotNull("yt.search_play must exist", ytSearch)
        assertEquals("com.google.android.youtube", ytSearch?.appPackage)

        val fbCompose = PlaybookStore.get("fb.compose_post")
        assertNotNull("fb.compose_post must exist", fbCompose)
        assertEquals("com.facebook.katana", fbCompose?.appPackage)

        val demoSettings = PlaybookStore.get("demo.open_settings_battery")
        assertNotNull("demo.open_settings_battery must exist", demoSettings)
    }

    @Test
    fun playbookSelectors_mustContainMandatoryAlternatives() {
        // Section 3.4 rule: কোনো playbook-এ শুধু একটি selector থাকবে না — অন্তত একটি alts থাকবে।
        val all = PlaybookStore.getAll()
        for (pb in all) {
            for (step in pb.steps) {
                when (step) {
                    is Step.WaitFor -> assertTrue("WaitFor selector in ${pb.id} must have alts", step.selector.alts.isNotEmpty())
                    is Step.Tap -> assertTrue("Tap selector in ${pb.id} must have alts", step.selector.alts.isNotEmpty())
                    is Step.SetText -> assertTrue("SetText selector in ${pb.id} must have alts", step.selector.alts.isNotEmpty())
                    is Step.Assert -> assertTrue("Assert selector in ${pb.id} must have alts", step.selector.alts.isNotEmpty())
                    else -> {}
                }
            }
        }
    }

    @Test
    fun rateLimiter_enforcesMaxPerMinuteLimits() {
        // WhatsApp limit: 8 per minute
        for (i in 1..8) {
            val (canRun, reason) = RateLimiter.canExecute("com.whatsapp")
            assertTrue("Request $i should be permitted", canRun)
            assertNull(reason)
            RateLimiter.recordExecution("com.whatsapp")
        }

        // 9th request must be rejected
        val (canRun9, reason9) = RateLimiter.canExecute("com.whatsapp")
        assertFalse("9th request in a minute must be rate limited", canRun9)
        assertNotNull(reason9)
        assertTrue(reason9!!.contains("রেট লিমিট"))
    }

    @Test
    fun idempotency_preventsDuplicateDispatchWithin60Seconds() {
        val pkg = "com.whatsapp"
        val recipient = "8801700000000"
        val text = "জরুরি ওষুধের অর্ডার কনফার্ম"

        // First attempt allowed
        assertTrue("First dispatch must be allowed", Idempotency.canSend(pkg, recipient, text))
        Idempotency.recordSent(pkg, recipient, text)

        // Duplicate attempt blocked
        assertFalse("Duplicate dispatch within 60s must be blocked", Idempotency.canSend(pkg, recipient, text))

        // Different text allowed
        assertTrue("Different text must be allowed", Idempotency.canSend(pkg, recipient, "ভিন্ন বার্তা"))

        // Different recipient allowed
        assertTrue("Different recipient must be allowed", Idempotency.canSend(pkg, "8801800000000", text))
    }

    @Test
    fun humanGate_buildsAccurateBengaliPromptsForSensitiveActions() {
        val (msgPrompt, isDoubleMsg) = HumanGate.buildPrompt("SEND_MESSAGE", "রহিম", "কাল সকালে আসব")
        assertFalse(isDoubleMsg)
        assertTrue(msgPrompt.contains("রহিম-কে লিখব: 'কাল সকালে আসব' — পাঠাব?"))

        val (callPrompt, _) = HumanGate.buildPrompt("MAKE_CALL", "করিম", "")
        assertTrue(callPrompt.contains("করিম-কে কল দেব। নিশ্চিত?"))

        val (postPrompt, _) = HumanGate.buildPrompt("POST_PUBLIC", "ফেসবুক", "আজ দোকান খোলা")
        assertTrue(postPrompt.contains("ফেসবুকে পোস্ট করব: 'আজ দোকান খোলা' — প্রকাশ করব?"))

        val (delPrompt, isDoubleDel) = HumanGate.buildPrompt("DELETE_MESSAGE", "", "")
        assertTrue(isDoubleDel)
        assertTrue(delPrompt.contains("সবার জন্য মেসেজটি মুছে ফেলব — এটি ফেরানো যাবে না। নিশ্চিত?"))
    }

    @Test
    fun humanGate_requiresGateForHighRiskActions() {
        assertTrue(HumanGate.requiresGate(RiskLevel.EXTERNAL_COMM, "SEND_MESSAGE"))
        assertTrue(HumanGate.requiresGate(RiskLevel.DESTRUCTIVE, "DELETE_MESSAGE"))
        assertFalse(HumanGate.requiresGate(RiskLevel.READ_ONLY, "READ_ONLY"))
        assertFalse(HumanGate.requiresGate(RiskLevel.SAFE_NAVIGATION, "NAVIGATE"))
    }

    @Test
    fun actionVerifier_verifiesOutcomesAccurately() {
        // Hash changed test
        val (hashSuccess, _) = ActionVerifier.verify(
            spec = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED),
            root = null,
            initialScreenHash = "HASH_A",
            currentScreenHash = "HASH_B"
        )
        assertTrue(hashSuccess)

        val (hashFail, _) = ActionVerifier.verify(
            spec = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED),
            root = null,
            initialScreenHash = "HASH_A",
            currentScreenHash = "HASH_A"
        )
        assertFalse(hashFail)

        // Notification reply accepted test
        val (replySuccess, _) = ActionVerifier.verify(
            spec = VerifySpec(type = VerifyType.NOTIFICATION_REPLY_ACCEPTED),
            root = null,
            initialScreenHash = "HASH_A",
            currentScreenHash = "HASH_B",
            lastReplySuccess = true
        )
        assertTrue(replySuccess)
    }

    @Test
    fun playbookStore_tracksHealthAndFailureLogging() {
        val dummyResult = PlaybookRunResult(
            playbookId = "demo.open_settings_battery",
            success = true,
            code = "SUCCESS",
            messageBn = "সফল",
            executionTimeMs = 120
        )
        PlaybookStore.logRun("demo.open_settings_battery", mapOf("test" to "1"), dummyResult)

        val health = PlaybookStore.getHealth("demo.open_settings_battery")
        assertTrue(health.totalRuns >= 1)
        assertTrue(health.successRuns >= 1)
        assertEquals(100, health.successRatePercent)
        assertTrue(health.isHealthy)
    }
}
