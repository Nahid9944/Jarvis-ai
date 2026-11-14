package com.example

import com.example.ai.ActionCommitStage
import com.example.ai.ActiveTaskInterruptionManager
import com.example.ai.AiAgentRegistry
import com.example.ai.EmotionContextEngine
import com.example.ai.ExecutionCommitController
import com.example.ai.InterruptionIntent
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.JarvisPersonalityManager
import com.example.ai.JarvisPersonalityMode
import com.example.ai.TaskStatus
import com.example.ai.UserEmotion
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.HttpFetchResult
import com.example.automation.JarvisSkillRecorder
import com.example.automation.YouTubeTutorialImporter
import com.example.data.local.JarvisSkillStepEntity
import com.example.service.AnnouncementDispatchOutcome
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.QueuedAnnouncement
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JarvisV9MasterOsUnitTest {

    private lateinit var origGitHubFetcher: (String) -> HttpFetchResult
    private lateinit var origNetworkCheck: (android.content.Context?) -> Boolean
    private lateinit var origYouTubeFetcher: (String) -> HttpFetchResult

    @Before
    fun setUp() {
        origGitHubFetcher = GitHubRepositoryLearningEngine.httpFetcher
        origNetworkCheck = GitHubRepositoryLearningEngine.networkAvailabilityCheck
        origYouTubeFetcher = YouTubeTutorialImporter.httpFetcher

        JarvisPersonalityManager.setPersonality(JarvisPersonalityMode.PROFESSIONAL)
        GeminiLiveAnnouncementAdapter.detachLiveSession()
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        GeminiLiveAnnouncementAdapter.clearQueue()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @After
    fun tearDown() {
        GitHubRepositoryLearningEngine.httpFetcher = origGitHubFetcher
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = origNetworkCheck
        YouTubeTutorialImporter.httpFetcher = origYouTubeFetcher
        GeminiLiveAnnouncementAdapter.detachLiveSession()
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        GeminiLiveAnnouncementAdapter.clearQueue()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @Test
    fun executionCommitController_enforcesAtomicPreDispatchAndIrreversibleBoundaries() {
        val actionId = "act_pre_cancel_001"
        ExecutionCommitController.registerAction(actionId, "makePhoneCall")
        assertTrue(ExecutionCommitController.canDispatchAction(actionId))

        val (cancelStage, _) = ExecutionCommitController.cancelAction(actionId)
        assertEquals(ActionCommitStage.CANCELLED, cancelStage)
        assertFalse("Cancelled action must NEVER pass pre-dispatch gate", ExecutionCommitController.canDispatchAction(actionId))

        val irreversibleId = "act_dispatched_sms_002"
        ExecutionCommitController.registerAction(irreversibleId, "sendSmsMessage")
        assertTrue(ExecutionCommitController.canDispatchAction(irreversibleId))
        ExecutionCommitController.markDispatched(irreversibleId)

        val (tooLateStage, msg) = ExecutionCommitController.cancelAction(irreversibleId)
        assertEquals(ActionCommitStage.TOO_LATE_TO_CANCEL, tooLateStage)
        assertTrue(msg.contains("ইতিমধ্যে"))
    }

    @Test
    fun activeTaskInterruptionManager_handlesPauseResumeCancelAndReplace() {
        val task = ActiveTaskInterruptionManager.registerNewTask(
            goalBn = "বাবাকে ফোন কল দেওয়া",
            toolName = "makePhoneCall",
            steps = listOf("কন্টাক্ট খোঁজা", "ডায়াল করা")
        )
        assertEquals(TaskStatus.EXECUTING, ActiveTaskInterruptionManager.currentTask.value?.status)

        val pauseRes = ActiveTaskInterruptionManager.handleInterruptionCommand("একটু দাঁড়াও, পজ করো")
        assertEquals(InterruptionIntent.PAUSE_CURRENT_TASK, pauseRes.intent)
        assertTrue(pauseRes.handled)
        assertEquals(TaskStatus.PAUSED, ActiveTaskInterruptionManager.currentTask.value?.status)

        val resumeRes = ActiveTaskInterruptionManager.handleInterruptionCommand("আবার শুরু করো")
        assertEquals(InterruptionIntent.RESUME_TASK, resumeRes.intent)
        assertTrue(resumeRes.handled)
        assertEquals(TaskStatus.EXECUTING, ActiveTaskInterruptionManager.currentTask.value?.status)

        val cancelRes = ActiveTaskInterruptionManager.handleInterruptionCommand("কাজ বন্ধ করো, ফোন দিও না")
        assertEquals(InterruptionIntent.CANCEL_CURRENT_TASK, cancelRes.intent)
        assertTrue(cancelRes.handled)
        assertNull(ActiveTaskInterruptionManager.currentTask.value)
    }

    @Test
    fun personalityAndEmotionEngine_supportsAllFiveModesAndBengaliEmotionCorrection() {
        assertEquals(JarvisPersonalityMode.ROMANTIC, JarvisPersonalityManager.parseVoicePersonalitySwitch("রোমান্টিক মোড অন করো"))
        assertEquals(JarvisPersonalityMode.BEST_FRIEND, JarvisPersonalityManager.parseVoicePersonalitySwitch("বেস্ট ফ্রেন্ড মোডে কথা বলো"))
        assertEquals(JarvisPersonalityMode.EMOTIONAL_COMFORT, JarvisPersonalityManager.parseVoicePersonalitySwitch("সান্ত্বনা মোড দাও"))
        assertEquals(JarvisPersonalityMode.PLAYFUL, JarvisPersonalityManager.parseVoicePersonalitySwitch("প্লেফুল মোড চালু করো"))
        assertEquals(JarvisPersonalityMode.PROFESSIONAL, JarvisPersonalityManager.parseVoicePersonalitySwitch("প্রফেশনাল জারভিস মোড"))

        val sadEval = EmotionContextEngine.analyzeText("আজকে আমার খুব মন খারাপ, কিছুই ভালো লাগছে না")
        assertEquals(UserEmotion.SAD, sadEval.emotion)
        assertTrue(sadEval.confidence >= 0.8f)

        EmotionContextEngine.applyUserEmotionCorrection(UserEmotion.CALM)
        val corrected = EmotionContextEngine.currentEmotion.value
        assertEquals(UserEmotion.CALM, corrected.emotion)
        assertTrue(corrected.userCorrected)
    }

    @Test
    fun masterOrchestrator_decomposesCompoundGoalsAndSelectsSpecialists() = runBlocking {
        val parts = JarvisMasterOrchestrator.decomposeGoal("আগে ফ্ল্যাশলাইট চালু করো তারপর ব্যাটারি চেক করো")
        assertEquals(2, parts.size)

        assertEquals(
            AiAgentRegistry.NOTIFICATION_AGENT.id,
            JarvisMasterOrchestrator.selectSpecialistAgent("সর্বশেষ নোটিফিকেশন পড়ে শোনাও").id
        )
        assertEquals(
            AiAgentRegistry.COMMUNICATION_AGENT.id,
            JarvisMasterOrchestrator.selectSpecialistAgent("বাবাকে হোয়াটসঅ্যাপে মেসেজ দাও").id
        )
        assertEquals(
            AiAgentRegistry.AUTOMATION_AGENT.id,
            JarvisMasterOrchestrator.selectSpecialistAgent("স্ক্রিন স্ক্রল করে ওকে ক্লিক করো").id
        )
        assertEquals(
            AiAgentRegistry.VISION_AGENT.id,
            JarvisMasterOrchestrator.selectSpecialistAgent("ক্যামেরা দিয়ে এই ছবিটা দেখো").id
        )

        val switchRes = JarvisMasterOrchestrator.orchestrate(
            context = null,
            utterance = "রোমান্টিক মোডে কথা বলো",
            toolExecutor = null
        )
        assertTrue(switchRes.success)
        assertEquals("PERSONALITY_SWITCHED", switchRes.finalState)
        assertEquals(JarvisPersonalityMode.ROMANTIC, JarvisPersonalityManager.currentMode.value)

        val convRes = JarvisMasterOrchestrator.orchestrate(
            context = null,
            utterance = "আজকের আকাশটা অনেক সুন্দর মনে হচ্ছে",
            toolExecutor = null
        )
        assertFalse(convRes.success)
        assertEquals("CONVERSATIONAL", convRes.finalState)
    }

    @Test
    fun announcementAdapter_queuesOfflineRedactsOtpAndRespectsInCallDeferral() {
        val otpOutcome = GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = "bkash_otp_1",
            rawText = "Your bKash verification OTP is 582914. Do not share.",
            category = QueuedAnnouncement.CATEGORY_NOTIFICATION,
            isSensitiveOtp = true
        )
        assertEquals(AnnouncementDispatchOutcome.QUEUED_OFFLINE, otpOutcome)
        val queuedText = GeminiLiveAnnouncementAdapter.pendingAnnouncements.value.first().text
        assertFalse("OTP digits MUST be redacted before voice output", queuedText.contains("582914"))
        assertTrue(queuedText.contains("[গোপন-কোড]"))

        // Duplicate within window should be skipped
        val dupOutcome = GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = "bkash_otp_1",
            rawText = "Your bKash verification OTP is 582914. Do not share.",
            category = QueuedAnnouncement.CATEGORY_NOTIFICATION,
            isSensitiveOtp = true
        )
        assertEquals(AnnouncementDispatchOutcome.SKIPPED_DUPLICATE, dupOutcome)

        // Attach live session while in active call -> should NOT flush normal notifications until call ends
        val dispatched = mutableListOf<String>()
        GeminiLiveAnnouncementAdapter.setInActiveCall(true)
        GeminiLiveAnnouncementAdapter.attachLiveSession { msg -> dispatched.add(msg) }
        assertTrue("Non-call announcements must stay queued during active call", dispatched.isEmpty())

        // Urgent incoming call announces immediately even when in call
        val callOutcome = GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = "call_baba",
            rawText = "বাবা কল করছেন।",
            category = QueuedAnnouncement.CATEGORY_INCOMING_CALL,
            priority = QueuedAnnouncement.PRIORITY_URGENT
        )
        assertEquals(AnnouncementDispatchOutcome.DISPATCHED_LIVE, callOutcome)
        assertEquals(1, dispatched.size)

        // Ending active call flushes deferred queue automatically
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        assertEquals(2, dispatched.size)
        assertEquals(0, GeminiLiveAnnouncementAdapter.getPendingCount())
    }

    @Test
    fun gitHubEngine_handlesOffline404RateLimitAndUnsupportedSwiftRepoTruthfully() = runBlocking {
        // Offline detection
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = { false }
        val offlineRes = GitHubRepositoryLearningEngine.importFromRepository(repoUrl = "https://github.com/user/demo")
        assertFalse(offlineRes.getBoolean("success"))
        assertEquals("OFFLINE_LIMITED", offlineRes.getString("status"))
        assertEquals("NO_INTERNET", offlineRes.getString("error"))

        // Restore online, test 404 & 429 & iOS Swift rejection
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = { true }
        GitHubRepositoryLearningEngine.httpFetcher = { url ->
            when {
                url.contains("missing-repo") -> HttpFetchResult(404, """{"message":"Not Found"}""")
                url.contains("busy-repo") -> HttpFetchResult(429, """{"message":"API rate limit exceeded"}""")
                url.endsWith("/repos/apple/swift-app") -> HttpFetchResult(200, """{"default_branch":"main","language":"Swift"}""")
                url.contains("/git/trees/main") -> HttpFetchResult(200, """{"tree":[{"path":"App.xcodeproj"},{"path":"Package.swift"}]}""")
                else -> HttpFetchResult(404, "")
            }
        }

        val notFound = GitHubRepositoryLearningEngine.importFromRepository(repoUrl = "https://github.com/user/missing-repo")
        assertFalse(notFound.getBoolean("success"))
        assertEquals("REPO_NOT_FOUND", notFound.getString("error"))

        val rateLimited = GitHubRepositoryLearningEngine.importFromRepository(repoUrl = "https://github.com/user/busy-repo")
        assertFalse(rateLimited.getBoolean("success"))
        assertEquals("RATE_LIMITED", rateLimited.getString("error"))

        val iosRepo = GitHubRepositoryLearningEngine.importFromRepository(repoUrl = "https://github.com/apple/swift-app")
        assertFalse(iosRepo.getBoolean("success"))
        assertEquals("UNSUPPORTED", iosRepo.getString("status"))
        assertFalse(iosRepo.getBoolean("runtimeCompatible"))
    }

    @Test
    fun youTubeImporter_enforcesTranscriptGateRejectsNonAndroidAndParsesCaptions() = runBlocking {
        // Reject cooking video without Android signals
        YouTubeTutorialImporter.httpFetcher = { url ->
            if (url.contains("oembed")) {
                HttpFetchResult(200, """{"title":"Best Chicken Biryani Recipe at Home"}""")
            } else {
                HttpFetchResult(200, "")
            }
        }
        val nonAndroid = YouTubeTutorialImporter.importFromTutorial(url = "https://youtu.be/AbCdEf12345")
        assertFalse(nonAndroid.getBoolean("success"))
        assertEquals("UNSUPPORTED", nonAndroid.getString("status"))

        // Missing transcript on Android video -> TRANSCRIPT_REQUIRED (never fabricate steps!)
        YouTubeTutorialImporter.httpFetcher = { url ->
            if (url.contains("oembed")) {
                HttpFetchResult(200, """{"title":"Android Hidden Settings Tutorial"}""")
            } else {
                HttpFetchResult(200, "<html>no captions</html>")
            }
        }
        val missingTranscript = YouTubeTutorialImporter.importFromTutorial(url = "https://youtu.be/XyZ12345678")
        assertFalse(missingTranscript.getBoolean("success"))
        assertEquals("TRANSCRIPT_REQUIRED", missingTranscript.getString("status"))

        // Valid caption XML extraction
        YouTubeTutorialImporter.httpFetcher = { url ->
            when {
                url.contains("oembed") -> HttpFetchResult(200, """{"title":"Android Wi-Fi Settings Guide"}""")
                url.contains("/watch?v=") -> HttpFetchResult(200, """{"captionTracks":[{"baseUrl":"https://captions.example/track?lang=en"}]}""")
                url.contains("captions.example") -> HttpFetchResult(
                    200,
                    "<transcript><text>Open Settings</text><text>Click Network</text><text>Scroll down</text></transcript>"
                )
                else -> HttpFetchResult(404, "")
            }
        }
        val extracted = YouTubeTutorialImporter.importFromTutorial(url = "https://youtu.be/WiFi1234567")
        assertTrue(extracted.getBoolean("success"))
        assertTrue(extracted.getInt("stepCount") >= 3)
    }

    @Test
    fun skillRecorder_extractsUserParametersExcludingSystemDateAndTime() {
        val steps = listOf(
            JarvisSkillStepEntity(skillId = "s1", stepIndex = 1, actionType = "CLICK", targetText = "{contact}", descriptionBengali = "কন্টাক্ট বাছাই"),
            JarvisSkillStepEntity(skillId = "s1", stepIndex = 2, actionType = "INPUT_TEXT", inputPayload = "{message} ({date} {time})", descriptionBengali = "মেসেজ লেখা")
        )
        val allParams = JarvisSkillRecorder.extractParameterNames(steps)
        assertEquals(listOf("contact", "message", "date", "time"), allParams)

        val userRequired = JarvisSkillRecorder.extractUserRequiredParameters(steps)
        assertEquals(listOf("contact", "message"), userRequired)
    }

    @Test
    fun emergencyStopCoordinator_haltsAllRegisteredSubsystemsAndResetsCleanly() {
        var liveStopped = false
        var spotterStopped = false
        JarvisEmergencyStopCoordinator.registerRuntimeHooks(
            onStopLiveSession = { liveStopped = true },
            onStopOfflineSpotter = { spotterStopped = true }
        )
        ActiveTaskInterruptionManager.registerNewTask("Test Goal", "makePhoneCall")

        val report = JarvisEmergencyStopCoordinator.triggerEmergencyStop(null)
        assertTrue(report.success)
        assertTrue(report.activeTaskCancelled)
        assertTrue(report.autonomousLoopHalted)
        assertTrue(report.livePlaybackInterrupted)
        assertTrue(report.announcementsCleared)
        assertTrue(report.offlineSpotterHalted)
        assertTrue(liveStopped)
        assertTrue(spotterStopped)
        assertTrue(JarvisEmergencyStopCoordinator.isEmergencyStopActive.value)
        assertNull(ActiveTaskInterruptionManager.currentTask.value)

        JarvisEmergencyStopCoordinator.resetEmergencyStop()
        assertFalse(JarvisEmergencyStopCoordinator.isEmergencyStopActive.value)
    }
}
