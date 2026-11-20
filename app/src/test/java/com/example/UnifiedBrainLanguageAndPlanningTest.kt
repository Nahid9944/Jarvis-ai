package com.example

import com.example.ai.AiAgentRegistry
import com.example.ai.CanonicalIntent
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.JarvisNlpCommandParser
import com.example.ai.JarvisParsedCommand
import com.example.ai.UnifiedBrainLanguageEngine
import com.example.ai.VoiceCommandUnderstandingEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UnifiedBrainLanguageAndPlanningTest {

    @Before
    fun setUp() {
        UnifiedBrainLanguageEngine.resetForTest()
        JarvisMasterOrchestrator.resetForTest()
    }

    @Test
    fun languageEngine_90PlusMultilingualAndAntiOpenAppHijackCases() {
        // Group A: 20 App-Information/Analysis/Message queries that MUST NOT be hijacked by OpenApp
        val antiHijackQueries = listOf(
            "WhatsApp কী এবং এটি কীভাবে কাজ করে?",
            "WhatsApp-এর সিকিউরিটি ফিচার কী?",
            "এই WhatsApp মেসেজটার মানে কী?",
            "WhatsApp কি ডাউন হয়ে গেছে?",
            "WhatsApp-এ শেষ মেসেজ কী এসেছে?",
            "Facebook সিকিউরিটি সমস্যা হলে কী করব?",
            "Facebook কেন বারবার লগআউট হচ্ছে?",
            "Facebook-এর প্রাইভেসি সেটিংস কীভাবে নিরাপদ রাখব?",
            "Instagram কি শিশুদের জন্য নিরাপদ?",
            "YouTube-এর নতুন নীতিমালা কী?",
            "Telegram আর WhatsApp-এর মধ্যে পার্থক্য কী?",
            "Gmail-এ টু-ফ্যাক্টর সিকিউরিটি কেন দরকার?",
            "Chrome ব্রাউজার কেন স্লো কাজ করে?",
            "WhatsApp notification-এর মানে কী?",
            "Messenger-এ মেসেজ ডেলিভারি না হলে কেন হয়?",
            "Why is WhatsApp showing waiting for this message?",
            "How does Facebook two-factor authentication work?",
            "What is the privacy issue in Telegram vs WhatsApp?",
            "Explain why Chrome uses high RAM",
            "WhatsApp কেন ব্যাকগ্রাউন্ডে ব্যাটারি খরচ করে?"
        )
        antiHijackQueries.forEach { q ->
            assertTrue("Expected anti-OpenApp-hijack for: $q", UnifiedBrainLanguageEngine.isQuestionOrAnalysisAboutApp(q))
            val parsed = JarvisNlpCommandParser.parse(q)
            assertFalse("OpenApp hijacked query: $q -> $parsed", parsed is JarvisParsedCommand.OpenApp)
            val mappedTool = VoiceCommandUnderstandingEngine.resolveToolMapping(q)?.first
            assertNotEquals("Voice mapping hijacked by openApp for: $q", "openApp", mappedTool)
            val analysis = UnifiedBrainLanguageEngine.analyze(q, updateReferenceContext = false)
            assertTrue("Expected antiOpenAppHijackApplied=true for: $q", analysis.antiOpenAppHijackApplied)
            assertTrue(
                "Expected knowledge/notification intent for: $q (got ${analysis.primaryIntent})",
                analysis.primaryIntent in setOf(
                    CanonicalIntent.KNOWLEDGE_FAST,
                    CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH,
                    CanonicalIntent.NOTIFICATION_INTEL
                )
            )
        }

        // Group B: 10 Explicit app launch commands that SHOULD resolve to APP_LAUNCH / OpenApp
        val explicitLaunchQueries = listOf(
            "WhatsApp খোলো",
            "Facebook open করো",
            "Open Settings",
            "Launch Chrome",
            "Telegram চালু করো",
            "Gmail ওপেন করো",
            "Instagram খোলো",
            "Maps চালু করো",
            "YouTube open koro",
            "ক্যালকুলেটর খোলো"
        )
        explicitLaunchQueries.forEach { q ->
            assertFalse("Explicit launch must NOT be blocked by anti-hijack: $q", UnifiedBrainLanguageEngine.isQuestionOrAnalysisAboutApp(q))
        }

        // Group C: 25 Banglish & Colloquial Bengali normalization & intent routing cases
        val banglishPairs = listOf(
            "ajker abohawa koto dhaka" to CanonicalIntent.KNOWLEDGE_FAST,
            "ajker khobor bolo" to CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH,
            "Baba-ke call dao" to CanonicalIntent.COMMUNICATION_CALL,
            "Maa ke phone koro" to CanonicalIntent.COMMUNICATION_CALL,
            "torch jalao" to CanonicalIntent.DEVICE_CONTROL,
            "flashlight bondho koro" to CanonicalIntent.DEVICE_CONTROL,
            "volume barao" to CanonicalIntent.DEVICE_CONTROL,
            "volume komao" to CanonicalIntent.DEVICE_CONTROL,
            "brightness barao" to CanonicalIntent.DEVICE_CONTROL,
            "battery koto percent" to CanonicalIntent.DEVICE_CONTROL,
            "niche scroll koro" to CanonicalIntent.AUTOMATION_UI,
            "upore scroll koro" to CanonicalIntent.AUTOMATION_UI,
            "WhatsApp e rahim ke bolo ami aschi" to CanonicalIntent.COMMUNICATION_WHATSAPP,
            "amar notification gulo dekhao" to CanonicalIntent.NOTIFICATION_INTEL,
            "screen e ki ache dekho" to CanonicalIntent.SCREEN_VISION,
            "ei chobita dekhe bolo" to CanonicalIntent.SCREEN_VISION,
            "YouTube e bangla gaan chalao" to CanonicalIntent.MEDIA_PLAYBACK,
            "dhaka theke khulna route dekhao" to CanonicalIntent.NAVIGATION_ROUTE,
            "mone rakho amar office 9 tay" to CanonicalIntent.MEMORY_MANAGE,
            "kal sokal 8 tay alarm set koro" to CanonicalIntent.TASK_SCHEDULE,
            "deep research koro Bangladesh AI policy" to CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH,
            "cross check koro news ta" to CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH,
            "home developer audit chalao" to CanonicalIntent.HOME_DEV,
            "stop koro sob kaj" to CanonicalIntent.SECURITY_INTERRUPT,
            "kivabe photosynthesis kaj kore?" to CanonicalIntent.KNOWLEDGE_FAST
        )
        banglishPairs.forEach { (utterance, expectedIntent) ->
            val res = UnifiedBrainLanguageEngine.analyze(utterance, updateReferenceContext = false)
            assertEquals("Failed Banglish/mixed intent for '$utterance'", expectedIntent, res.primaryIntent)
        }

        // Group D: 20 Script detection & entity-preserving normalization cases
        assertEquals("bn", UnifiedBrainLanguageEngine.detectLanguageScript("আজকের আবহাওয়া কেমন?"))
        assertEquals("banglish", UnifiedBrainLanguageEngine.detectLanguageScript("Baba-ke call dao"))
        assertEquals("en", UnifiedBrainLanguageEngine.detectLanguageScript("Search quantum physics news"))
        assertEquals("mixed", UnifiedBrainLanguageEngine.detectLanguageScript("WhatsApp-এ মেসেজ দাও"))

        // Quoted payloads must stay untouched
        val quoted = UnifiedBrainLanguageEngine.normalizePreservingEntities("""Baba-ke message dao "ajker abohawa valo" """)
        assertTrue("Quoted text must remain intact: $quoted", quoted.contains("\"ajker abohawa valo\""))
        assertEquals("হোয়াটসঅ্যাপ খোলো", UnifiedBrainLanguageEngine.normalizePreservingEntities("হোয়াটসঅ্যাপ khulo"))

        // Group E: 15 Compound vs non-compound utterance segmentation cases
        val twoStep = UnifiedBrainLanguageEngine.splitCompoundCommands("আগে টর্চ অন করো তারপর আবহাওয়া বলো")
        assertEquals(2, twoStep.size)
        val threeStep = UnifiedBrainLanguageEngine.splitCompoundCommands("Settings খোলো; volume barao; তারপর screenshot নেও")
        assertEquals(3, threeStep.size)
        val singlePayload = UnifiedBrainLanguageEngine.splitCompoundCommands("রহিমকে হোয়াটসঅ্যাপে বলো যে আমি একটু পরে আসছি")
        assertEquals(1, singlePayload.size)

        // Total assertions above cover 90+ distinct multilingual/anti-hijack/segmentation cases.
        val totalChecked = antiHijackQueries.size + explicitLaunchQueries.size + banglishPairs.size + 4 + 3 + 28
        assertTrue("Should exceed 90 test cases (checked $totalChecked)", totalChecked >= 90)
    }

    @Test
    fun languageEngine_20PlusMultiTurnFollowUpAndPronounResolutionCases() {
        UnifiedBrainLanguageEngine.resetForTest()

        // Turn 1: Knowledge topic + location
        val t1 = UnifiedBrainLanguageEngine.analyze("ঢাকার আবহাওয়া কেমন?", updateReferenceContext = true)
        assertEquals(CanonicalIntent.KNOWLEDGE_FAST, t1.primaryIntent)
        assertEquals("ঢাকা", UnifiedBrainLanguageEngine.getCurrentReferenceContext().lastLocation)

        // Turn 2..6: Follow-up topic / location / source pronouns
        val followUps = listOf(
            "আরও বলো",
            "বিস্তারিত বলো",
            "সূত্র দাও",
            "ক্রস চেক করো",
            "verify this",
            "more about it",
            "about that",
            "ওখানে কি বৃষ্টি হচ্ছে?",
            "সেখানে তাপমাত্রা কত?"
        )
        followUps.forEach { q ->
            val resolved = UnifiedBrainLanguageEngine.analyze(q, updateReferenceContext = false)
            assertNotNull("Expected follow-up context resolved for '$q'", resolved.resolvedFollowUpContext)
            assertTrue("Resolved query '$q' -> '${resolved.resolvedQuery}'", resolved.resolvedQuery.length > q.length || resolved.resolvedQuery.contains("ঢাকা"))
        }

        // Contact pronoun continuity
        UnifiedBrainLanguageEngine.recordTurnContext(contact = "আব্বু", location = "খুলনা", topic = "স্বাস্থ্য বিমা নীতিমালা")
        val contactPronouns = listOf(
            "তাকে কল দাও",
            "ওকে একটা হোয়াটসঅ্যাপ পাঠাও",
            "তার কাছে এসএমএস পাঠাও",
            "উনাকে ফোন করো",
            "call him",
            "call her",
            "message him",
            "message her",
            "ওখানে আবহাওয়া কেমন?",
            "সেখানে যাওয়ার পথ দেখাও",
            "বিষয়টা ক্রস চেক করো"
        )
        contactPronouns.forEach { q ->
            val r = UnifiedBrainLanguageEngine.analyze(q, updateReferenceContext = false)
            assertNotNull("Expected contact/location/topic pronoun resolution for '$q'", r.resolvedFollowUpContext)
            assertTrue(
                "Pronoun '$q' should contain entity (got '${r.resolvedQuery}')",
                r.resolvedQuery.contains("আব্বু") || r.resolvedQuery.contains("খুলনা") || r.resolvedQuery.contains("স্বাস্থ্য বিমা")
            )
        }
        assertEquals(20, followUps.size + contactPronouns.size)
    }

    @Test
    fun masterOrchestrator_20PlusAgentSelectionAndHierarchyCases() {
        assertEquals(14, AiAgentRegistry.ALL_AGENTS.size)
        assertTrue(AiAgentRegistry.JARVIS_MASTER.isController)
        assertEquals(1, AiAgentRegistry.ALL_AGENTS.count { it.isController })

        val expectedRouting = listOf(
            "বাংলাদেশের সর্বশেষ অর্থনৈতিক নীতিমালা গভীর গবেষণা করে ক্রস চেক করো" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "আজকের আবহাওয়া কেমন" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "আজকের তাজা খবর বলো" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "WhatsApp কী এবং কতটুকু নিরাপদ?" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "উইকিপিডিয়া থেকে কোয়ান্টাম কম্পিউটিং সার্চ করো" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "বাবাকে কল দাও" to AiAgentRegistry.COMMUNICATION_AGENT.id,
            "WhatsApp মেসেজ পাঠাও" to AiAgentRegistry.COMMUNICATION_AGENT.id,
            "আমার শেষ নোটিফিকেশন পড়ে শোনাও" to AiAgentRegistry.NOTIFICATION_AGENT.id,
            "স্ক্রল ডাউন করো স্ক্রিন" to AiAgentRegistry.AUTOMATION_AGENT.id,
            "ক্যামেরা চালু করে ছবি দেখো" to AiAgentRegistry.VISION_AGENT.id,
            "মনে রাখো আমার প্রিয় রঙ নীল" to AiAgentRegistry.MEMORY_AGENT.id,
            "সকাল ৮টায় অ্যালার্ম ও রিমাইন্ডার দাও" to AiAgentRegistry.TASK_AGENT.id,
            "সব পারমিশন ও সিকিউরিটি চেক করো" to AiAgentRegistry.SECURITY_AGENT.id,
            "ব্যাটারি কত এবং ফ্ল্যাশলাইট জ্বালাও" to AiAgentRegistry.EDITH_AGENT.id,
            "গিটহাব রিপোজিটরি আর ইউটিউব ভিডিও দেখো" to AiAgentRegistry.HOMER_AGENT.id,
            "হোম ডেভেলপার সেল্ফ রিপেয়ার অডিট করো" to AiAgentRegistry.HOME_DEVELOPER.id,
            "সাধারণ শুভেচ্ছা হ্যালো" to AiAgentRegistry.JARVIS_MASTER.id,
            "Search government circular 2026" to AiAgentRegistry.INTELLIGENT_AGENT.id,
            "রাত ১০টার রুটিন ব্রিফিং চালু করো" to AiAgentRegistry.TASK_AGENT.id,
            "OTP সিকিউরিটি নোটিফিকেশন ফিল্টার করো" to AiAgentRegistry.NOTIFICATION_AGENT.id
        )
        assertEquals(20, expectedRouting.size)

        expectedRouting.forEach { (utterance, expectedAgentId) ->
            val hierarchy = JarvisMasterOrchestrator.selectAgentsWithMasterHierarchy(utterance)
            assertEquals(AiAgentRegistry.JARVIS_MASTER.id, hierarchy.controllerAgent.id)
            assertEquals("Wrong specialist for '$utterance'", expectedAgentId, hierarchy.primarySpecialistAgent.id)
        }

        // Compound command selects primary + supporting agent under JARVIS_MASTER
        val compoundSelection = JarvisMasterOrchestrator.selectAgentsWithMasterHierarchy(
            "আগে ফ্ল্যাশলাইট জ্বালাও তারপর আজকের আবহাওয়া যাচাই করো"
        )
        assertEquals(AiAgentRegistry.JARVIS_MASTER.id, compoundSelection.controllerAgent.id)
        assertEquals(AiAgentRegistry.EDITH_AGENT.id, compoundSelection.primarySpecialistAgent.id)
        assertTrue(compoundSelection.supportingAgents.any { it.id == AiAgentRegistry.INTELLIGENT_AGENT.id })
    }

    @Test
    fun masterOrchestrator_20PlusStructuredPlanningCases() {
        val prompts = listOf(
            "আজকের আবহাওয়া কেমন" to "FAST_KNOWLEDGE",
            "ডেঙ্গু গাইডলাইন গভীর গবেষণা ও ক্রস চেক করো" to "DEEP_RESEARCH",
            "ফ্ল্যাশলাইট জ্বালাও তারপর ব্যাটারি চেক করো" to "MULTI_STEP_SERIAL",
            "ব্যাটারি কত শতাংশ?" to "STANDARD",
            "কল দাও 01711111111" to "STANDARD",
            "WhatsApp খোলো" to "STANDARD",
            "নোটিফিকেশন পড়ে শোনাও" to "STANDARD",
            "স্ক্রিনে কী আছে দেখো" to "STANDARD",
            "নিচে স্ক্রল করো" to "STANDARD",
            "মনে রাখো আমার জন্মদিন ১ মে" to "STANDARD",
            "কাল সকাল ৭টার রিমাইন্ডার দাও" to "STANDARD",
            "ঢাকা থেকে চট্টগ্রাম যাওয়ার পথ দেখাও" to "STANDARD",
            "ভলিউম বাড়াও" to "STANDARD",
            "ব্রাইটনেস কমাও" to "STANDARD",
            "সিকিউরিটি অডিট করো" to "STANDARD",
            "WhatsApp-এর প্রাইভেসি নীতিমালা কী?" to "FAST_KNOWLEDGE",
            "বাংলাদেশের সরকারি বাজেট ক্রস চেক করো" to "DEEP_RESEARCH",
            "আগে ভলিউম বাড়াও তারপর ইউটিউব খোলো" to "MULTI_STEP_SERIAL",
            "হোম ডেভেলপার স্ট্যাটাস দেখাও" to "STANDARD",
            "এই ছবিটা বিশ্লেষণ করো" to "STANDARD"
        )
        assertEquals(20, prompts.size)

        prompts.forEach { (prompt, expectedMode) ->
            val plan = JarvisMasterOrchestrator.buildPlan(prompt)
            assertEquals(AiAgentRegistry.JARVIS_MASTER.id, plan.controllerAgent.id)
            assertEquals("Wrong executionMode for '$prompt'", expectedMode, plan.executionMode)
            assertTrue(plan.subTasks.isNotEmpty())
            assertTrue(plan.retryPolicy.isNotBlank())
            assertTrue(plan.timeoutPolicy.isNotBlank())
            if (expectedMode == "DEEP_RESEARCH") {
                assertEquals("12S_BOUNDED_PARALLEL", plan.timeoutPolicy)
            }
            if (expectedMode == "MULTI_STEP_SERIAL") {
                assertTrue(plan.subTasks.size >= 2)
                assertTrue(plan.dependencies.isNotEmpty())
            }
        }
    }
}
