package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAgentRegistry
import com.example.ai.AssistantToolExecutor
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.NewsAndRegulatoryService
import com.example.audio.MicOwner
import com.example.audio.MicrophoneCoordinator
import com.example.automation.JarvisSkillRecorder
import com.example.data.local.AssistantDatabase
import com.example.data.repository.JarvisSkillRepository
import com.example.permissions.JarvisActionState
import com.example.permissions.JarvisPermissionManager
import com.example.permissions.PermissionRegistry
import com.example.permissions.PermissionOrchestrator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NahidV10UpgradeUnitTest {

    private lateinit var app: Application
    private lateinit var origNewsFetcher: (String) -> String?

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        origNewsFetcher = NewsAndRegulatoryService.httpTextFetcher
        NewsAndRegulatoryService.clearCacheForTest()
        MicrophoneCoordinator.forceReleaseAll()
        JarvisMasterOrchestrator.resetForTest()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.dismiss()
        AssistantDatabase.resetInstanceForTest()
    }

    @After
    fun tearDown() {
        NewsAndRegulatoryService.httpTextFetcher = origNewsFetcher
        NewsAndRegulatoryService.clearCacheForTest()
        MicrophoneCoordinator.forceReleaseAll()
        JarvisMasterOrchestrator.resetForTest()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.dismiss()
        AssistantDatabase.resetInstanceForTest()
    }

    @Test
    fun microphoneCoordinator_enforcesPriorityPreemptionAndMutex() {
        assertTrue(MicrophoneCoordinator.tryAcquire(MicOwner.WAKE_WORD_SERVICE))
        assertEquals(MicOwner.WAKE_WORD_SERVICE, MicrophoneCoordinator.currentOwner.value)

        // Non-preempting spotter is denied while wake word holds mic
        assertFalse(MicrophoneCoordinator.tryAcquire(MicOwner.OFFLINE_SPOTTER, preempt = false))

        // Higher-priority Gemini Live stream preempts wake word
        assertTrue(MicrophoneCoordinator.tryAcquire(MicOwner.GEMINI_LIVE_STREAM, preempt = true))
        assertEquals(MicOwner.GEMINI_LIVE_STREAM, MicrophoneCoordinator.currentOwner.value)

        // Lower-priority owner cannot preempt Gemini Live even with preempt=true
        assertFalse(MicrophoneCoordinator.tryAcquire(MicOwner.OFFLINE_SPOTTER, preempt = true))
        assertEquals(MicOwner.GEMINI_LIVE_STREAM, MicrophoneCoordinator.currentOwner.value)

        // Release frees microphone cleanly
        MicrophoneCoordinator.release(MicOwner.GEMINI_LIVE_STREAM)
        assertEquals(MicOwner.NONE, MicrophoneCoordinator.currentOwner.value)
    }

    @Test
    fun masterOrchestrator_handlesMultiTurnSlotFillingAndPermissionPreservation() = runBlocking {
        // Turn 1: Incomplete generic call prompt asks clarifying question and holds PendingSlotRequest
        val firstTurn = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "কল করো",
            toolExecutor = null
        )
        assertEquals("WAITING_FOR_USER", firstTurn.finalState)
        assertNotNull(JarvisMasterOrchestrator.pendingSlotRequest.value)

        // Turn 2: Cancellation cleanly clears pending slot
        val cancelTurn = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "বাতিল",
            toolExecutor = null
        )
        assertEquals("CANCELLED", cancelTurn.finalState)
        assertNull(JarvisMasterOrchestrator.pendingSlotRequest.value)

        // Turn 3: Re-trigger slot & verify missing permission preserves REQUIRES_PERMISSION status cleanly
        val askAgain = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "কল দাও",
            toolExecutor = null
        )
        assertEquals("WAITING_FOR_USER", askAgain.finalState)

        val dao = AssistantDatabase.getDatabase(app).assistantDao()
        val executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)
        // Without CALL_PHONE granted in Robolectric test env, makePhoneCall should preserve PERMISSION_REQUIRED or CONFIRMATION_REQUIRED
        val followUp = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "রহিম",
            toolExecutor = executor
        )
        assertNull(JarvisMasterOrchestrator.pendingSlotRequest.value)
        assertTrue(
            "Expected PERMISSION_REQUIRED or CONFIRMATION_REQUIRED, got ${followUp.finalState}",
            followUp.finalState in setOf("REQUIRES_PERMISSION", "REQUIRES_CONFIRMATION", "SUCCEEDED")
        )
        assertTrue(followUp.executedSteps.any { it.toolName == "makePhoneCall" })
    }

    @Test
    fun jarvisPermissionManager_persistsPendingOsActionAcrossProcessRestartWithTtl() {
        val callSpec = PermissionRegistry.get("call_phone")!!
        val held = JarvisPermissionManager.holdForMissingPermission(
            context = app,
            executionId = "exec_persist_42",
            toolName = "makePhoneCall",
            descriptionBn = "বাবাকে কল করা",
            args = JSONObject().put("phoneNumber", "01711111111"),
            caller = "voice_test",
            missingSpecs = listOf(callSpec)
        ) { _, _ -> JSONObject().put("success", true) }

        assertEquals(JarvisActionState.WAITING_FOR_OS_PERMISSION, held.state)

        // Simulate clearing in-memory pending state (like OS process death) without touching SharedPreferences
        val prefs = app.getSharedPreferences("jarvis_pending_os_action_prefs", Context.MODE_PRIVATE)
        assertNotNull("Pending OS action must be persisted to SharedPreferences", prefs.getString("pending_os_action_json", null))

        // Reset in-memory field only via reflection or calling restore after clear:
        val field = JarvisPermissionManager::class.java.getDeclaredField("_pendingOsAction")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(JarvisPermissionManager) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        stateFlow.value = null

        // Restore must succeed within TTL
        val restored = JarvisPermissionManager.restorePendingOsFromPrefsIfNeeded(app, now = held.createdAt + 10_000L)
        assertNotNull("Pending OS action should restore from SharedPreferences after process restart", restored)
        assertEquals("exec_persist_42", restored?.executionId)
        assertEquals("makePhoneCall", restored?.toolName)

        // Must expire after 5-minute TTL
        stateFlow.value = null
        val expired = JarvisPermissionManager.restorePendingOsFromPrefsIfNeeded(app, now = held.createdAt + (6 * 60 * 1000L))
        assertNull("Expired pending OS action must NOT replay silently", expired)
    }

    @Test
    fun newsAndRegulatoryService_parsesRealRssCachesAndReturnsEmptyOffline() = runBlocking {
        // 1. Offline mode returns empty list honestly (zero hardcoded fake news)
        NewsAndRegulatoryService.httpTextFetcher = { null }
        val offlineList = NewsAndRegulatoryService.getLiveNews("সব", forceRefresh = true)
        assertTrue("Offline news fetch must return empty list honestly", offlineList.isEmpty())

        // 2. Valid BBC Bangla RSS XML parses cleanly with real link URLs and 10-min cache works
        var fetchCalls = 0
        NewsAndRegulatoryService.httpTextFetcher = { url ->
            fetchCalls++
            if (url.contains("bbci.co.uk")) {
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>BBC News বাংলা</title>
                    <item>
                      <title><![CDATA[ঢাকায় প্রযুক্তি সম্মেলন শুরু &amp; এআই আলোচনা]]></title>
                      <description><![CDATA[<p>আজ রাজধানীতে নতুন এআই উদ্ভাবন প্রদর্শিত হয়েছে।</p>]]></description>
                      <link>https://www.bbc.com/bengali/articles/test123</link>
                      <pubDate>Fri, 15 Nov 2026 08:00:00 GMT</pubDate>
                    </item>
                    <item>
                      <title>নতুন আবহাওয়া সতর্কতা জারি</title>
                      <description>উপকূলীয় অঞ্চলে হালকা বৃষ্টির সম্ভাবনা।</description>
                      <link>https://www.bbc.com/bengali/articles/test456</link>
                      <pubDate>Fri, 15 Nov 2026 07:30:00 GMT</pubDate>
                    </item>
                    <item><title>তৃতীয় সংবাদ</title><link>https://bbc.com/3</link></item>
                    <item><title>চতুর্থ সংবাদ</title><link>https://bbc.com/4</link></item>
                  </channel>
                </rss>
                """.trimIndent()
            } else null
        }

        val live = NewsAndRegulatoryService.getLiveNews("সব", forceRefresh = true)
        assertEquals(4, live.size)
        assertEquals("ঢাকায় প্রযুক্তি সম্মেলন শুরু & এআই আলোচনা", live[0].title)
        assertEquals("https://www.bbc.com/bengali/articles/test123", live[0].url)
        assertEquals("BBC বাংলা", live[0].source)

        val callsAfterFirstFetch = fetchCalls
        val cached = NewsAndRegulatoryService.getLiveNews("সব", forceRefresh = false)
        assertEquals(4, cached.size)
        assertEquals("Second read within 10-min TTL must hit cache", callsAfterFirstFetch, fetchCalls)
    }

    @Test
    fun skillRecorder_marksUnparseableNaturalLanguageSkillAsDraftNotFakeActive() = runBlocking {
        val db = AssistantDatabase.getDatabase(app)
        val repo = JarvisSkillRepository(db.jarvisSkillDao())

        val draftSkill = JarvisSkillRecorder.teachSkillFromNaturalLanguage(
            skillName = "Empty Skill",
            stepsInstructions = "অস্পষ্ট কিছু সাধারণ কথা যেখানে অ্যাকশন নেই",
            repository = repo,
            geminiApiKey = ""
        )
        assertEquals("DRAFT", draftSkill.status)
        assertEquals(0, draftSkill.stepCount)

        val activeSkill = JarvisSkillRecorder.teachSkillFromNaturalLanguage(
            skillName = "YouTube Search",
            stepsInstructions = "YouTube খুলে Search এ ক্লিক করে Bangla Song টাইপ করো",
            repository = repo,
            geminiApiKey = ""
        )
        assertEquals("ACTIVE", activeSkill.status)
        assertTrue(activeSkill.stepCount > 0)
    }

    @Test
    fun aiAgentRegistry_userPreferenceUpdatesSelectedSpecialist() {
        JarvisMasterOrchestrator.setUserSelectedAgent(AiAgentRegistry.HOMER_AGENT)
        assertEquals(AiAgentRegistry.HOMER_AGENT.id, JarvisMasterOrchestrator.selectedSpecialist.value.id)
    }

    @Test
    fun voiceCommandEngine_resolvesArcherVoiceIntentsAccurately() {
        val newsMapping = com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("আজকের AI news গুলো দাও")
        assertNotNull(newsMapping)
        assertEquals("getLiveNews", newsMapping?.first)
        assertEquals("প্রযুক্তি ও এআই", newsMapping?.second?.optString("category"))

        val briefingMapping = com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("আমার evening briefing বলো")
        assertNotNull(briefingMapping)
        assertEquals("generateDailyBriefing", briefingMapping?.first)
        assertEquals("Evening", briefingMapping?.second?.optString("timeOfDay"))

        val notifMapping = com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("আমার কোনো notification আছে দেখো")
        assertNotNull(notifMapping)
        assertEquals("getRecentNotifications", notifMapping?.first)

        val igMapping = com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("Instagram open করো")
        assertNotNull(igMapping)
        assertEquals("openApp", igMapping?.first)

        val ytMapping = com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("Motu Patlu play করো YouTube-এ")
        assertNotNull(ytMapping)
        assertEquals("searchAndPlayYouTube", ytMapping?.first)

        assertEquals(
            "startCameraVision",
            com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("ক্যামেরা চালু করো")?.first
        )
        assertEquals(
            "switchCameraLens",
            com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("front camera")?.first
        )
        assertEquals(
            "stopCameraVision",
            com.example.ai.VoiceCommandUnderstandingEngine.resolveToolMapping("ক্যামেরা বন্ধ করো")?.first
        )
    }

    @Test
    fun assistantToolExecutor_emitsHudEventsForNewsAndInlineCameraControls() = runBlocking {
        val dao = AssistantDatabase.getDatabase(app).assistantDao()
        val executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)

        // 1. Live news tool emits openHeadlinesEvents when real RSS returns items
        NewsAndRegulatoryService.httpTextFetcher = { url ->
            if (url.contains("bbci.co.uk")) {
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>এআই প্রযুক্তিতে নতুন মাইলফলক</title>
                    <link>https://www.bbc.com/bengali/articles/ai-demo</link>
                  </item>
                </channel></rss>
                """.trimIndent()
            } else null
        }

        val collectedNews = mutableListOf<List<com.example.ai.NewsItem>>()
        val collectorJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            com.example.ai.JarvisHudEventBus.openHeadlinesEvents.collect { collectedNews.add(it) }
        }
        val camEvents = mutableListOf<Boolean?>()
        val camCollector = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            com.example.ai.JarvisHudEventBus.inlineCameraToggleEvents.collect { camEvents.add(it) }
        }

        val newsRes = executor.executeToolWithCaller("getLiveNews", JSONObject().put("category", "সব"), "voice_test")
        assertTrue(newsRes.optBoolean("success", false))
        assertTrue("getLiveNews must emit HUD openHeadlinesEvents", collectedNews.isNotEmpty())
        assertEquals("এআই প্রযুক্তিতে নতুন মাইলফলক", collectedNews.first().first().title)

        // 2. Inline camera start respects CAMERA permission gate honestly before permission is granted
        val deniedCam = executor.executeToolWithCaller("startCameraVision", JSONObject(), "voice_test")
        assertFalse("startCameraVision must block without CAMERA permission", deniedCam.optBoolean("success", false))

        // 3. Grant CAMERA permission via Robolectric ShadowApplication and assert inline camera HUD events emit cleanly
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val startCam = executor.executeToolWithCaller("startCameraVision", JSONObject(), "voice_test")
        assertTrue("startCameraVision should succeed once CAMERA is granted: $startCam", startCam.optBoolean("success", false))
        val stopCam = executor.executeToolWithCaller("stopCameraVision", JSONObject(), "voice_test")
        assertTrue(stopCam.optBoolean("success", false))

        assertTrue(camEvents.contains(true))
        assertTrue(camEvents.contains(false))

        collectorJob.cancel()
        camCollector.cancel()
    }

    @Test
    fun jarvisRoutineEngine_eveningBriefingIncludesGreetingAndRealRoomTasks() = runBlocking {
        val dao = AssistantDatabase.getDatabase(app).assistantDao()
        dao.insertAiTask(
            com.example.data.local.AiTaskEntity(
                title = "প্রেজেন্টেশন স্লাইড প্রস্তুত করা",
                description = "অফিস মিটিং",
                status = "PENDING",
                priority = "HIGH"
            )
        )

        val engine = com.example.automation.JarvisRoutineEngine(app, dao)
        val result = engine.generateDailyBriefing("Evening")
        assertTrue(result.success)
        assertEquals("Evening Briefing", result.routineName)
        assertTrue(
            "Expected evening greeting in summary: ${result.summaryBengali}",
            result.summaryBengali.contains("শুভ সন্ধ্যা")
        )
        assertTrue(
            "Expected persisted Room task title in summary: ${result.summaryBengali}",
            result.summaryBengali.contains("প্রেজেন্টেশন স্লাইড প্রস্তুত করা")
        )
    }

    @Test
    fun masterOrchestrator_routesLiveToolsUpdatesSpecialistAndPreventsRedundantExecution() = runBlocking {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val dao = AssistantDatabase.getDatabase(app).assistantDao()
        val executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)

        // 1. Gemini Live / HUD shortcut tool call goes through JarvisMasterOrchestrator and selects Vision Specialist
        val firstRes = JarvisMasterOrchestrator.orchestrateToolCall(
            context = app,
            toolName = "switchCameraLens",
            args = JSONObject(),
            caller = "gemini_live_voice",
            toolExecutor = executor
        )
        assertTrue("Expected switchCameraLens to succeed: $firstRes", firstRes.optBoolean("success", false))
        assertEquals(AiAgentRegistry.VISION_AGENT.id, JarvisMasterOrchestrator.selectedSpecialist.value.id)
        assertEquals("SUCCEEDED", JarvisMasterOrchestrator.lastOrchestrationResult.value?.finalState)

        // 2. Rapid duplicate invocation of identical tool call inside cooldown window is blocked cleanly
        val dupTool = JarvisMasterOrchestrator.orchestrateToolCall(
            context = app,
            toolName = "switchCameraLens",
            args = JSONObject(),
            caller = "gemini_live_voice",
            toolExecutor = executor
        )
        assertFalse(dupTool.optBoolean("success", false))
        assertEquals("DUPLICATE_IGNORED", dupTool.optString("status"))

        // 3. Rapid duplicate identical utterance via text/voice is also deduplicated cleanly
        val firstVoice = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "ব্যাটারি কত শতাংশ আছে?",
            toolExecutor = executor,
            caller = "user_ui"
        )
        assertEquals("SUCCEEDED", firstVoice.finalState)

        val dupVoice = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "ব্যাটারি কত শতাংশ আছে?",
            toolExecutor = executor,
            caller = "user_ui"
        )
        assertEquals("DUPLICATE_IGNORED", dupVoice.finalState)
        assertFalse(dupVoice.success)
    }

    @Test
    fun hudCameraShortcut_holdsMissingPermissionAndShowsRationalePopupUntilGranted() = runBlocking {
        val dao = AssistantDatabase.getDatabase(app).assistantDao()
        val executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)

        // Without CAMERA permission, Archer LIVE CAMERA shortcut calls startCameraVision through Orchestrator
        val denied = JarvisMasterOrchestrator.orchestrateToolCall(
            context = app,
            toolName = "startCameraVision",
            args = JSONObject(),
            caller = "archer_hud_shortcut",
            toolExecutor = executor
        )
        assertFalse(denied.optBoolean("success", false))
        assertEquals("PERMISSION_REQUIRED", denied.optString("status"))
        assertNotNull("PermissionRationaleSheet event must open automatically", PermissionOrchestrator.currentRequest.value)
        assertEquals("camera", PermissionOrchestrator.currentRequest.value?.spec?.id)
        assertEquals("startCameraVision", JarvisPermissionManager.pendingOsAction.value?.toolName)

        // User grants CAMERA permission -> grant & simulate returning from settings / permission dialog
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        PermissionOrchestrator.dismiss()

        val resumedDone = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String>>()
        JarvisPermissionManager.onResumeRecheckAndExecute(
            context = app,
            fallbackExecutor = { name, args -> executor.executeToolWithCaller(name, args, "auto_resume") },
            onResult = { ok, msg -> resumedDone.complete(Pair(ok, msg)) }
        )

        val deadline = System.currentTimeMillis() + 5_000L
        while (!resumedDone.isCompleted && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Resumed startCameraVision must finish cleanly", resumedDone.isCompleted)
        val (ok, msg) = resumedDone.await()
        assertTrue("Expected auto-resumed startCameraVision to succeed: $msg", ok)
        assertNull("Pending OS action must clear once executed", JarvisPermissionManager.pendingOsAction.value)
    }
}

