package com.example

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ai.AssistantToolExecutor
import com.example.ai.AudioInputStreamer
import com.example.ai.JarvisCameraVisionCoordinator
import com.example.ai.JarvisOfflineKeywordSpotter
import com.example.ai.NewsAndRegulatoryService
import com.example.ai.WeatherService
import com.example.audio.MicOwner
import com.example.audio.MicrophoneCoordinator
import com.example.audio.OfflineWakeWordEngine
import com.example.automation.ActionVerifier
import com.example.automation.AutomationToolHandler
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.HttpFetchResult
import com.example.automation.VerifySpec
import com.example.automation.VerifyType
import com.example.automation.YouTubeTutorialImporter
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import com.example.data.repository.JarvisNotificationRepository
import com.example.permissions.JarvisPermissionManager
import com.example.permissions.PermissionOrchestrator
import com.example.research.SearchResultItem
import com.example.research.UniversalBrowserAgent
import com.example.research.WebPageContentExtractor
import com.example.research.WebSearchProvider
import com.example.service.EnhancedNotification
import com.example.service.JarvisWakeWordService
import com.example.tools.ToolRegistry
import com.example.util.JarvisCameraManager
import com.example.util.JarvisEmergencyStopCoordinator
import com.example.util.JarvisLiveLocationTracker
import com.example.util.LiveGpsLocation
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase1RepairAndToolAuditTest {

    private lateinit var app: Application
    private lateinit var dao: AssistantDao
    private lateinit var executor: AssistantToolExecutor

    private lateinit var origWeatherClient: OkHttpClient
    private lateinit var origGeoClient: OkHttpClient
    private var origPageClient: OkHttpClient? = null
    private lateinit var origNewsFetcher: (String) -> String?
    private lateinit var origYtFetcher: (String) -> HttpFetchResult
    private lateinit var origGhFetcher: (String) -> HttpFetchResult
    private var origGhHeaderFetcher: ((String, Map<String, String>) -> HttpFetchResult)? = null
    private lateinit var origGhNetCheck: (Context?) -> Boolean

    @Before
    fun setUp() {
        app = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        origWeatherClient = WeatherService.httpClient
        origGeoClient = com.example.util.BangladeshCalendarAndGeoEngine.geoHttpClient
        origPageClient = WebPageContentExtractor.httpClientOverride
        origNewsFetcher = NewsAndRegulatoryService.httpTextFetcher
        origYtFetcher = YouTubeTutorialImporter.httpFetcher
        origGhFetcher = GitHubRepositoryLearningEngine.httpFetcher
        origGhHeaderFetcher = GitHubRepositoryLearningEngine.httpFetcherWithHeaders
        origGhNetCheck = GitHubRepositoryLearningEngine.networkAvailabilityCheck

        app.getSharedPreferences("jarvis_wakeword_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("nahid_ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("jarvis_gps_cache", Context.MODE_PRIVATE).edit().clear().commit()

        OfflineWakeWordEngine.resetCooldown()
        MicrophoneCoordinator.forceReleaseAll()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.dismiss()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
        JarvisWakeWordService.setRunningForTest(false)
        runBlocking { JarvisNotificationRepository.clearAll(app) }
        AssistantDatabase.resetInstanceForTest()

        dao = AssistantDatabase.getDatabase(app).assistantDao()
        executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)
    }

    @After
    fun tearDown() {
        WeatherService.httpClient = origWeatherClient
        com.example.util.BangladeshCalendarAndGeoEngine.geoHttpClient = origGeoClient
        WebPageContentExtractor.httpClientOverride = origPageClient
        NewsAndRegulatoryService.httpTextFetcher = origNewsFetcher
        YouTubeTutorialImporter.httpFetcher = origYtFetcher
        GitHubRepositoryLearningEngine.httpFetcher = origGhFetcher
        GitHubRepositoryLearningEngine.httpFetcherWithHeaders = origGhHeaderFetcher
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = origGhNetCheck

        OfflineWakeWordEngine.resetCooldown()
        MicrophoneCoordinator.forceReleaseAll()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.dismiss()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
        JarvisWakeWordService.setRunningForTest(false)
        runBlocking { JarvisNotificationRepository.clearAll(app) }
        JarvisCameraVisionCoordinator.resetForTest()
        JarvisCameraManager.activeCameraControl = null
        JarvisCameraManager.activeImageCapture = null
        AssistantDatabase.resetInstanceForTest()
    }

    private fun stubOfflineFastMocks() {
        val fastClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = when {
                    url.contains("geocoding-api.open-meteo.com") -> """{"results":[{"name":"Dhaka","country":"Bangladesh","latitude":23.8103,"longitude":90.4125}]}"""
                    url.contains("api.open-meteo.com") -> """{"current":{"temperature_2m":30.0,"apparent_temperature":32.0,"relative_humidity_2m":65,"wind_speed_10m":10.0,"precipitation":0.0,"weather_code":1}}"""
                    else -> "<html><head><title>Example</title></head><body><p>Sample web page body text.</p></body></html>"
                }
                val mime = if (body.trimStart().startsWith("{")) "application/json; charset=utf-8" else "text/html; charset=utf-8"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(mime.toMediaType()))
                    .build()
            }
            .build()

        WeatherService.httpClient = fastClient
        com.example.util.BangladeshCalendarAndGeoEngine.geoHttpClient = fastClient
        WebPageContentExtractor.httpClientOverride = fastClient
        NewsAndRegulatoryService.httpTextFetcher = { targetUrl ->
            if (targetUrl.contains("wikipedia.org")) {
                """{"type":"standard","title":"Dhaka","extract":"Dhaka is the capital of Bangladesh.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Dhaka"}}}"""
            } else {
                null
            }
        }
        YouTubeTutorialImporter.httpFetcher = { HttpFetchResult(404, "", "mock_offline") }
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = { false }
        GitHubRepositoryLearningEngine.httpFetcher = { HttpFetchResult(404, "", "mock_offline") }
    }

    @Test
    fun all141RegisteredTools_dispatchToRealHandlersWithoutUnknownToolOrBlankSummary() = runBlocking {
        stubOfflineFastMocks()
        val allSpecs = ToolRegistry.getAll()
        assertEquals(158, allSpecs.size)

        val failures = mutableListOf<String>()
        for (spec in allSpecs) {
            JarvisEmergencyStopCoordinator.resetEmergencyStop()
            val args = sampleArgsForTool(spec.name)
            val res = executor.executeCoreInternal(spec.name, args)
            val err = res.optString("error", "")
            val summary = res.optString("summaryBengali", "")
            if (err.startsWith("Unknown tool", ignoreCase = true) ||
                res.optBoolean("toolExecutionRejected", false)
            ) {
                failures.add("${spec.name}: hit Unknown tool branch (error=$err)")
            }
            if (summary.isBlank()) {
                failures.add("${spec.name}: blank summaryBengali (keys=${res.keys().asSequence().toList()})")
            }
        }

        assertTrue("158-tool audit failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun sampleArgsForTool(name: String): JSONObject = JSONObject().apply {
        when (name) {
            "openApp" -> put("appName", "Settings")
            "navigateApp" -> put("screen", "hud")
            "openPermissionSettings" -> put("permissionType", "app_info")
            "openDeepLink" -> put("uri", "https://example.com")
            "searchAndPlayYouTube" -> put("query", "Bangladesh nature")
            "mediaControl" -> put("action", "PLAY")
            "openFacebook" -> put("postText", "")
            "toggleFlashlight" -> put("enabled", false)
            "setVolume" -> put("volumePercent", 50)
            "makePhoneCall" -> put("phoneNumber", "01700000000")
            "sendWhatsAppMessage" -> {
                put("phoneNumber", "01700000000")
                put("messageText", "Hello")
            }
            "startWhatsAppVoiceCall", "startWhatsAppVideoCall" -> put("contact", "01700000000")
            "controlWhatsApp", "openWhatsAppConversation" -> {
                put("contact", "01700000000")
                put("message", "Hi")
            }
            "sendSmsMessage" -> {
                put("phoneNumber", "01700000000")
                put("message", "Hi")
            }
            "replyFromNotification", "sendNotificationReply" -> {
                put("packageFilter", "com.whatsapp")
                put("replyText", "Ok")
            }
            "shareViaIntent" -> {
                put("targetPackage", "com.whatsapp")
                put("text", "Hello")
            }
            "clickScreen", "longPressOnScreen", "scrollToText" -> put("targetText", "Send")
            "typeOnScreen", "clearAndTypeOnScreen", "typeIntoFocusedField", "createFacebookPostDraft", "commentOnFacebookPost" -> put("text", "Hello")
            "performSystemAction" -> put("action", "home")
            "captureAndAnalyzeScreen", "analyzeCameraView" -> put("question", "What is visible?")
            "executeVisualSuggestion" -> {
                put("actionType", "click")
                put("targetTextOrId", "OK")
                put("confidence", 0.85)
            }
            "waitForNode" -> {
                put("selectorQuery", "Send")
                put("timeoutMs", 25L)
            }
            "assertScreen" -> put("expectedPackage", "com.example")
            "runPlaybook", "validatePlaybook" -> put("playbookId", "wa.send_text")
            "runAutonomousTask" -> put("goal", "Open settings")
            "listPlaybooks", "getAppVersion" -> put("appPackage", "com.whatsapp")
            "recordPlaybook" -> {
                put("id", "custom.test.pb")
                put("titleBn", "টেস্ট প্লেবুক")
                put("appPackage", "com.example")
            }
            "humanConfirm" -> {
                put("actionType", "SEND_MSG")
                put("target", "Karim")
            }
            "confirmPendingAction" -> put("userConfirmationPhrase", "Cancel")
            "saveMemory" -> {
                put("key", "test_pref")
                put("content", "Bengali")
            }
            "searchMemories" -> put("query", "test")
            "deleteMemory" -> put("keyOrId", "nonexistent_mem")
            "createAiTask" -> {
                put("title", "Sample Task")
                put("timeOrMinutesFromNow", "30")
            }
            "setExactScheduleAlarm" -> {
                put("title", "Alarm Test")
                put("delayMinutes", 30)
            }
            "getAllAiTasks" -> put("status", "ALL")
            "completeAiTask", "deleteAiTask" -> put("taskId", 99999L)
            "createNote", "addNoteDirect" -> {
                put("title", "Note 1")
                put("content", "Body 1")
            }
            "deleteNote" -> put("titleOrId", "nonexistent_note")
            "getLiveWeather" -> put("locationName", "Dhaka")
            "searchWikipedia" -> put("query", "Dhaka")
            "searchExternalKnowledge" -> put("url", "https://example.com")
            "getLiveNews" -> put("category", "সব")
            "getRecentNotifications" -> put("limit", "5")
            "getNotificationsByApp", "getNotificationDetails" -> {
                put("appName", "WhatsApp")
                put("sender", "Rahim")
            }
            "classifyNotification" -> put("text", "Your OTP is 1234")
            "suggestSmartReplies" -> put("messageText", "Kemon acho?")
            "dismissNotification" -> put("key", "missing_key")
            "muteActiveCall" -> put("muted", false)
            "toggleCallSpeaker" -> put("speakerOn", false)
            "setCameraZoom" -> put("zoomRatio", 1.0)
            "toggleCameraTorch" -> put("enabled", false)
            "findUiElement", "clickUiElement", "longPressUiElement" -> put("query", "OK")
            "swipeScreen", "scrollScreen" -> put("direction", "DOWN")
            "verifyUiState" -> put("expectedText", "Ready")
            "searchContacts", "getContactDetails" -> put("name", "Baba")
            "createContact" -> {
                put("name", "Test Contact")
                put("phone", "01700000001")
            }
            "setBrightness" -> put("percent", 50)
            "executeRoutine" -> put("routineName", "Morning")
            "startSkillRecording" -> {
                put("skillName", "Open Calc")
                put("triggerPhrase", "open calc")
            }
            "stopAndSaveSkill" -> put("description", "desc")
            "executeLearnedSkill" -> put("skillNameOrTrigger", "unknown skill")
            "deleteLearnedSkill" -> put("skillId", "unknown_skill_id")
            "importSkillFromYouTube" -> put("url", "https://youtube.com/watch?v=dQw4w9WgXcQ")
            "importSkillFromGitHub" -> put("repoUrl", "https://github.com/example/repo")
            "learnFromText" -> {
                put("instruction", "মনে রাখো আমার প্রিয় রং সবুজ")
                put("dryRun", true)
            }
            "deepResearchWeb" -> put("query", "Dhaka")
            "calculateDistanceAndRoute" -> {
                put("origin", "ঢাকা")
                put("destination", "খুলনা")
                put("launchMaps", false)
            }
            "getBangladeshDateTimeAndCalendar" -> put("isoDate", "2026-04-14")
            "listInstalledApps" -> put("query", "")
            "readVisibleAppData" -> put("filterKeyword", "")
            "startHomeDevTask" -> {
                put("goal", "Verify capability audit")
                put("category", "SELF_REPAIR")
                put("sourceType", "GAP_DETECTOR")
            }
            "pauseHomeDeveloper" -> put("reason", "audit_pause")
            "resumeHomeDeveloper", "cancelHomeDeveloper" -> put("taskId", "")
            "createCombinedSkill" -> {
                put("skillIds", "s1,s2")
                put("newSkillName", "Combined Audit")
            }
            "learnFromDocument" -> {
                put("fileName", "guide.md")
                put("content", "1. Open Settings app")
            }
            "learnFromScreenshot" -> {
                put("observedText", "Open Chrome app\nClick Search")
                put("targetApp", "com.android.chrome")
            }
            "setSelfDevelopmentMode" -> put("enabled", false)
            "generateDeveloperArtifact" -> {
                put("targetFile", "AuditSample.kt")
                put("goal", "Audit helper spec")
            }
            "intelligentKnowledgeQuery" -> {
                put("query", "Dhaka")
                put("mode", "FAST_RETRIEVAL")
            }
        }
    }

    @Test
    fun parseScheduleTimestamp_parsesAmPm24hBengaliDurationAndNumericMinutes() {
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseNow = baseCal.timeInMillis

        val amResult = executor.parseScheduleTimestamp("08:00 AM", nowMillis = baseNow)
        val amCal = Calendar.getInstance().apply { timeInMillis = amResult }
        assertEquals(8, amCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, amCal.get(Calendar.MINUTE))
        assertEquals(baseCal.get(Calendar.DAY_OF_YEAR), amCal.get(Calendar.DAY_OF_YEAR))

        val milResult = executor.parseScheduleTimestamp("20:30", nowMillis = baseNow)
        val milCal = Calendar.getInstance().apply { timeInMillis = milResult }
        assertEquals(20, milCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, milCal.get(Calendar.MINUTE))

        val bnHourResult = executor.parseScheduleTimestamp("২ ঘণ্টা", nowMillis = baseNow)
        assertEquals(baseNow + 120L * 60_000L, bnHourResult)

        val minResult = executor.parseScheduleTimestamp("30", nowMillis = baseNow)
        assertEquals(baseNow + 30L * 60_000L, minResult)
    }

    @Test
    fun taskLifecycleTools_filterAndVerifyDatabaseStateAccurately() = runBlocking {
        val t1 = executor.executeCoreInternal("createAiTask", JSONObject().apply {
            put("title", "Morning Water")
            put("timeOrMinutesFromNow", "08:00 AM")
        })
        assertTrue(t1.optBoolean("success"))
        val t1Id = t1.optLong("taskId")
        assertTrue(t1Id > 0)

        val t2 = executor.executeCoreInternal("createAiTask", JSONObject().apply {
            put("title", "Evening Walk")
            put("timeOrMinutesFromNow", "20:30")
        })
        assertTrue(t2.optBoolean("success"))
        val t2Id = t2.optLong("taskId")

        val badComplete = executor.executeCoreInternal("completeAiTask", JSONObject().apply { put("taskId", 999999L) })
        assertFalse(badComplete.optBoolean("success"))
        assertFalse(badComplete.optBoolean("found", true))

        val okComplete = executor.executeCoreInternal("completeAiTask", JSONObject().apply { put("taskId", t1Id) })
        assertTrue(okComplete.optBoolean("success"))

        val pendingRes = executor.executeCoreInternal("getAllAiTasks", JSONObject().apply { put("status", "PENDING") })
        assertTrue(pendingRes.optBoolean("success"))
        assertEquals(1, pendingRes.optInt("count"))
        assertEquals("Evening Walk", pendingRes.getJSONArray("tasks").getJSONObject(0).getString("title"))

        val completedRes = executor.executeCoreInternal("getAllAiTasks", JSONObject().apply { put("status", "COMPLETED") })
        assertTrue(completedRes.optBoolean("success"))
        assertEquals(1, completedRes.optInt("count"))
        assertEquals("Morning Water", completedRes.getJSONArray("tasks").getJSONObject(0).getString("title"))

        val badDelete = executor.executeCoreInternal("deleteAiTask", JSONObject().apply { put("taskId", 888888L) })
        assertFalse(badDelete.optBoolean("success"))
        assertFalse(badDelete.optBoolean("deleted", true))

        val okDelete = executor.executeCoreInternal("deleteAiTask", JSONObject().apply { put("taskId", t2Id) })
        assertTrue(okDelete.optBoolean("success"))
        assertTrue(okDelete.optBoolean("deleted"))
        assertTrue(dao.getAllAiTasksSync().none { it.id == t2Id })
    }

    @Test
    fun diagnosticAndReadTools_emitExplicitSuccessFlag() = runBlocking {
        stubOfflineFastMocks()
        val diag = executor.executeCoreInternal("getSystemDiagnostics", JSONObject())
        assertTrue(diag.has("success"))
        assertTrue(diag.optBoolean("success"))

        val emptyLatest = executor.executeCoreInternal("getLatestNote", JSONObject())
        assertTrue(emptyLatest.has("success"))
        assertFalse(emptyLatest.optBoolean("success"))

        val notesList = executor.executeCoreInternal("getNotes", JSONObject())
        assertTrue(notesList.optBoolean("success"))

        val memSearch = executor.executeCoreInternal("searchMemories", JSONObject().apply { put("query", "xyz") })
        assertTrue(memSearch.optBoolean("success"))

        val memAll = executor.executeCoreInternal("getAllMemories", JSONObject())
        assertTrue(memAll.optBoolean("success"))

        val wikiOk = executor.executeCoreInternal("searchWikipedia", JSONObject().apply { put("query", "Dhaka") })
        assertTrue(wikiOk.optBoolean("success"))

        val skillsList = executor.executeCoreInternal("listLearnedSkills", JSONObject())
        assertTrue(skillsList.optBoolean("success"))
    }

    @Test
    fun getNotificationDetails_filtersByBothAppNameAndSender() = runBlocking {
        JarvisNotificationRepository.persistNotificationSync(
            app,
            EnhancedNotification(
                key = "k1",
                id = 101,
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                sender = "Karim",
                title = "Karim",
                text = "Meeting at 5",
                bigText = "Meeting at 5",
                timestamp = 1000L
            )
        )
        JarvisNotificationRepository.persistNotificationSync(
            app,
            EnhancedNotification(
                key = "k2",
                id = 102,
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                sender = "Rahim",
                title = "Rahim",
                text = "Hello brother",
                bigText = "Hello brother",
                timestamp = 2000L
            )
        )

        val karimDetail = executor.executeCoreInternal("getNotificationDetails", JSONObject().apply {
            put("appName", "WhatsApp")
            put("sender", "Karim")
        })
        assertTrue(karimDetail.optBoolean("success"))
        assertEquals("Karim", karimDetail.optString("sender"))
        assertEquals("Meeting at 5", karimDetail.optString("text"))

        val missingSender = executor.executeCoreInternal("getNotificationDetails", JSONObject().apply {
            put("appName", "WhatsApp")
            put("sender", "UnknownPerson")
        })
        assertFalse(missingSender.optBoolean("success"))
    }

    @Test
    fun stopCameraVision_clearsCoordinatorAndCameraHardwareRefs() = runBlocking {
        val stopRes = executor.executeCoreInternal("stopCameraVision", JSONObject())
        assertTrue(stopRes.optBoolean("success"))
        assertNull(JarvisCameraManager.activeCameraControl)
        assertNull(JarvisCameraManager.activeImageCapture)
        assertFalse(JarvisCameraVisionCoordinator.observationState.value.isStreaming)
    }

    @Test
    fun actionVerifier_lastOutgoingMessage_rejectsBlankAndVerifiesExpectedOrParams() {
        val mockNode = AccessibilityNodeInfo.obtain().apply {
            text = "Assalamu Alaikum"
        }
        val blankOutcome = ActionVerifier.verify(
            spec = VerifySpec(VerifyType.LAST_OUTGOING_MESSAGE_EQUALS, expected = ""),
            root = mockNode,
            initialScreenHash = "h0",
            currentScreenHash = "h1",
            params = emptyMap()
        )
        assertFalse("Blank expected message must NOT pass verification", blankOutcome.first)

        val matchedFromParams = ActionVerifier.verify(
            spec = VerifySpec(VerifyType.LAST_OUTGOING_MESSAGE_EQUALS, expected = null),
            root = mockNode,
            initialScreenHash = "h0",
            currentScreenHash = "h1",
            params = mapOf("text" to "Assalamu Alaikum")
        )
        assertTrue(matchedFromParams.first)
    }

    @Test
    fun automationToolHandler_emitsSummaryBengaliAndHonestStatuses() {
        val handler = AutomationToolHandler(app)

        val validation = handler.validatePlaybook("wa.send_text")
        assertTrue(validation.optBoolean("success"))
        assertFalse(validation.optBoolean("valid"))
        assertTrue(validation.optString("summaryBengali").isNotBlank())

        val callState = handler.verifyWhatsAppCallState()
        assertFalse(callState.optBoolean("success"))
        assertEquals("PERMISSION_REQUIRED", callState.optString("status"))
        assertTrue(callState.optString("summaryBengali").isNotBlank())
    }

    @Test
    fun liveLocationTracker_preservesCachedTimestampAndNeverFallsBackToMorrelganj() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val savedTime = 1700000000000L
        app.getSharedPreferences("jarvis_gps_cache", Context.MODE_PRIVATE).edit()
            .putFloat("lat", 23.8103f)
            .putFloat("lng", 90.4125f)
            .putString("addr", "ধানমন্ডি, ঢাকা, বাংলাদেশ")
            .putLong("time", savedTime)
            .commit()

        val (ok, loc) = JarvisLiveLocationTracker.fetchCurrentLocation(app)
        assertTrue(ok)
        assertTrue(loc != null)
        assertTrue(loc!!.isCached)
        assertEquals(savedTime, loc.timestamp)
        assertFalse(loc.fullAddress.contains("মোড়লগঞ্জ"))

        val spoken = JarvisLiveLocationTracker.formatLocationSpokenSummary(loc)
        assertTrue(spoken.contains("সংরক্ষিত") || spoken.contains("ক্যাশড"))
        assertFalse(spoken.contains("মোড়লগঞ্জ"))

        val freshLoc = LiveGpsLocation(23.8, 90.4, 5f, "২৩.৮০০০, ৯০.৪০০০", isCached = false)
        assertTrue(JarvisLiveLocationTracker.formatLocationSpokenSummary(freshLoc).contains("বর্তমান"))
    }

    @Test
    fun micCoordinatorAndSpotter_releaseMicCleanlyAndPreventDualRecognizerCollision() {
        val streamer = AudioInputStreamer(app, onAudioChunk = { _, _ -> }, onError = {})
        val started = streamer.start()
        assertFalse(started)
        assertEquals(MicOwner.NONE, MicrophoneCoordinator.currentOwner.value)

        JarvisWakeWordService.setRunningForTest(true)
        val spotter = JarvisOfflineKeywordSpotter(app) { _, _ -> }
        spotter.startListening()
        assertFalse(spotter.isListening.value)
        assertEquals(MicOwner.NONE, MicrophoneCoordinator.currentOwner.value)
        spotter.stopListening()
    }

    @Test
    fun universalBrowserAgent_marksSnippetOnlyProvenanceHonestly() = runBlocking {
        val mockProvider = object : WebSearchProvider {
            override val providerId: String = "MockTestProvider"
            override val requiresApiKey: Boolean = false
            override fun isConfigured(): Boolean = true
            override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> {
                return Result.success(
                    listOf(
                        SearchResultItem(
                            title = "Kotlin Coroutines Guide",
                            url = "https://kotlinlang.org/docs/coroutines-overview.html",
                            snippet = "Asynchronous programming with coroutines.",
                            sourceDomain = "kotlinlang.org",
                            publishedDate = "2026-01-10",
                            providerName = "MockTestProvider",
                            verified = true
                        )
                    )
                )
            }
        }

        val resp = UniversalBrowserAgent.searchAndVerify(
            query = "Kotlin coroutines",
            customProviders = listOf(mockProvider)
        )
        assertTrue(resp.success)
        assertEquals(1, resp.results.size)
        assertTrue(resp.results[0].snippetOnly)
        assertFalse(resp.results[0].contentExtracted)
        assertTrue(resp.summaryBengali.contains("সার্চ স্নিপেট"))
    }
}
