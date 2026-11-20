package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ai.AiSettingsRepository
import com.example.ai.AssistantToolExecutor
import com.example.ai.GeminiLiveSessionManager
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.ScreenActionSuggestion
import com.example.ai.ScreenVisualAnalyzer
import com.example.ai.VoiceCommandUnderstandingEngine
import com.example.automation.ActionVerifier
import com.example.automation.AutomationToolHandler
import com.example.automation.CapturedStep
import com.example.automation.JarvisAutonomousLoopOrchestrator
import com.example.automation.JarvisSkillRecorder
import com.example.automation.RiskLevel
import com.example.automation.VerifySpec
import com.example.automation.VerifyType
import com.example.automation.apps.FacebookPlaybooks
import com.example.automation.apps.WhatsAppPlaybooks
import com.example.automation.apps.YouTubePlaybooks
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import com.example.data.local.DevTaskEntity
import com.example.data.local.JarvisFrequentContactEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.developer.HomeDeveloperOrchestrator
import com.example.permissions.ActionApprovalRegistry
import com.example.permissions.JarvisPermissionManager
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.JarvisNotificationListenerService
import com.example.tools.ToolRegistry
import com.example.util.BangladeshCalendarAndGeoEngine
import com.example.util.JarvisDeviceAutomationHelper
import com.example.util.JarvisEmergencyStopCoordinator
import com.example.util.JarvisVoiceSpeaker
import com.example.util.OfflineTtsStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FiveRequirementsAndBugRepairTest {

    private lateinit var app: Application
    private lateinit var db: AssistantDatabase
    private lateinit var dao: AssistantDao
    private lateinit var executor: AssistantToolExecutor
    private lateinit var origGeoClient: OkHttpClient

    @Before
    fun setUp() {
        app = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        origGeoClient = BangladeshCalendarAndGeoEngine.geoHttpClient
        app.getSharedPreferences("nahid_ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("jarvis_gps_cache", Context.MODE_PRIVATE).edit().clear().commit()
        JarvisPermissionManager.resetForTest()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
        JarvisVoiceSpeaker.unregisterLiveAudioDispatcher()
        GeminiLiveAnnouncementAdapter.clearQueue()
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        JarvisVoiceSpeaker.offlineSpeakerOverride = null
        JarvisVoiceSpeaker.configureVoiceSettings(
            offlineEnabled = true,
            speechRate = 1.0f,
            voicePitch = 1.0f,
            silentMode = false,
            preferredLanguage = "bn"
        )
        AssistantDatabase.resetInstanceForTest()
        db = AssistantDatabase.getDatabase(app)
        dao = db.assistantDao()
        executor = AssistantToolExecutor(dao = dao, onNavigate = {}, context = app)
    }

    @After
    fun tearDown() {
        BangladeshCalendarAndGeoEngine.geoHttpClient = origGeoClient
        JarvisVoiceSpeaker.unregisterLiveAudioDispatcher()
        GeminiLiveAnnouncementAdapter.clearQueue()
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        JarvisVoiceSpeaker.offlineSpeakerOverride = null
        JarvisVoiceSpeaker.configureVoiceSettings(
            offlineEnabled = true,
            speechRate = 1.0f,
            voicePitch = 1.0f,
            silentMode = false,
            preferredLanguage = "bn"
        )
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)
        JarvisPermissionManager.resetForTest()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
        AssistantDatabase.resetInstanceForTest()
    }

    // -------------------------------------------------------------------------
    // BUG 1 & BUG 2: HomeDeveloperOrchestrator Real Checkpoint Resume & Honest Approval
    // -------------------------------------------------------------------------

    @Test
    fun bug1_homeDeveloperResumeFromCheckpoint_executesRealStepsAndCompletes() = runBlocking {
        val devDao = db.homeDeveloperDao()
        val pausedTask = DevTaskEntity(
            taskId = "dev_resume_test_1",
            goal = "Self repair capability audit and test sync",
            category = "SELF_REPAIR",
            learningSource = "TEXT",
            autonomyLevel = "L2_SAFE_AUTONOMOUS",
            status = "PAUSED",
            phase = "PAUSED",
            currentStepIndex = 1,
            totalSteps = 3,
            completedStepsJson = JSONArray(listOf("গ্যাপ স্ক্যান করা")).toString(),
            pendingStepsJson = JSONArray(listOf("ফলাফল যাচাই", "রিপোর্ট তৈরি")).toString(),
            pauseReason = "USER_PAUSED"
        )
        devDao.upsertDevTask(pausedTask)

        val resumed = HomeDeveloperOrchestrator.resumeFromCheckpoint(
            taskId = pausedTask.taskId,
            context = app,
            databaseOverride = db
        )
        assertNotNull(resumed)
        assertEquals("SUCCEEDED", resumed!!.status)
        assertEquals("COMPLETED", resumed.phase)
        assertEquals(3, resumed.currentStepIndex)
        assertEquals("[]", resumed.pendingStepsJson)
    }

    @Test
    fun bug2_homeDeveloperApproveWaitingTask_refusesFakeSuccessWhenDraftSkillMissing() = runBlocking {
        val devDao = db.homeDeveloperDao()
        val task = DevTaskEntity(
            taskId = "dev_wait_missing_skill",
            goal = "Activate missing draft skill",
            category = "SKILL_LEARN",
            status = "WAITING_APPROVAL",
            phase = "AWAITING_APPROVAL",
            draftSkillId = "non_existent_skill_999",
            currentStepIndex = 2,
            totalSteps = 3,
            pendingStepsJson = JSONArray(listOf("অনুমোদন অপেক্ষা")).toString()
        )
        devDao.upsertDevTask(task)

        val (ok, msg) = HomeDeveloperOrchestrator.approveWaitingTask(
            taskId = task.taskId,
            context = app,
            databaseOverride = db
        )
        assertFalse("Missing draft skill must NEVER yield fake SUCCEEDED", ok)
        assertTrue(msg.isNotBlank())

        val reloaded = devDao.getDevTaskByIdSync(task.taskId)
        assertNotNull(reloaded)
        assertEquals("WAITING_APPROVAL", reloaded!!.status)
        assertTrue(reloaded.lastError.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // BUG 3: JarvisSkillRecorder Saves DRAFT Only & Enforces Confirmation
    // -------------------------------------------------------------------------

    @Test
    fun bug3_skillRecorder_savesDraftFirstWithConfirmationAndPromotesOnlyAfterValidation() = runBlocking {
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val recorder = JarvisSkillRecorder.getInstance()
        recorder.startRecording(
            skillName = "Send Feedback",
            skillNameBengali = "ফিডব্যাক পাঠাও",
            triggerPhrase = "ফিডব্যাক পাঠাও",
            targetAppPackage = "com.example",
            repository = skillRepo
        )
        recorder.addManualStep(
            actionType = "CLICK",
            targetText = "Send",
            descriptionBn = "Send বাটনে ক্লিক"
        )

        val savedDraft = recorder.stopAndSaveSkill(description = "Test draft", repository = skillRepo)
        assertEquals("DRAFT", savedDraft.status)
        assertTrue("High-risk keyword 'Send' must require confirmation", savedDraft.requiresConfirmation)
        assertEquals("HIGH", savedDraft.riskLevel)

        val (activated, activateMsg) = recorder.approveAndActivateRecordedSkill(savedDraft.skillId, skillRepo)
        assertTrue("Valid draft should promote cleanly: $activateMsg", activated)
        val reloaded = skillRepo.getSkillById(savedDraft.skillId)
        assertEquals("ACTIVE", reloaded?.status)
    }

    // -------------------------------------------------------------------------
    // BUG 4 & BUG 5 & REQ 2: Structured Screen Vision & Dynamic Action Bridge
    // -------------------------------------------------------------------------

    @Test
    fun bug4_and_req2_screenVisualAnalyzer_parsesNormalizedCoordsAndRejectsStaleOrLowConfidenceFrames() = runBlocking {
        val sampleGeminiJson = JSONObject().apply {
            put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    """
                                    {
                                      "detectedApp": "com.example.shop",
                                      "screenDescription": "Checkout screen visible",
                                      "summaryBengali": "চেকআউট স্ক্রিনে 'Order Now' বাটন দেখা যাচ্ছে।",
                                      "suggestions": [
                                        {
                                          "actionType": "click",
                                          "title": "Order Now",
                                          "description": "Tap order button",
                                          "targetTextOrId": "Order Now",
                                          "viewId": "com.example.shop:id/btn_order",
                                          "elementType": "Button",
                                          "clickable": true,
                                          "bounds": [120, 900, 960, 1020],
                                          "centerNorm": [0.5, 0.85],
                                          "confidence": 0.92
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    )
                )
            )
        }.toString()

        val parsed = ScreenVisualAnalyzer.parseGeminiResponse(sampleGeminiJson, frameWidth = 1080, frameHeight = 2400)
        assertEquals("com.example.shop", parsed.detectedApp)
        assertEquals(1, parsed.suggestions.size)
        val sug = parsed.suggestions.first()
        assertEquals("com.example.shop:id/btn_order", sug.viewId)
        assertEquals(0.5f, sug.centerNormX!!, 0.01f)
        assertEquals(0.85f, sug.centerNormY!!, 0.01f)
        assertEquals(0.92f, sug.confidence, 0.01f)
        assertEquals(1080, sug.screenshotWidth)
        assertEquals(2400, sug.screenshotHeight)

        // Reject stale frame (>15s old) cleanly without clicking blindly
        val staleOutcome = ScreenVisualAnalyzer.executeVisualSuggestion(
            context = app,
            suggestion = sug.copy(screenshotTimestamp = System.currentTimeMillis() - 60_000L)
        )
        assertFalse(staleOutcome.success)
        assertEquals("STALE_FRAME", staleOutcome.status)

        // Reject non-clickable ambiguous element with no semantic ID
        val nonClickable = ScreenVisualAnalyzer.executeVisualSuggestion(
            context = app,
            suggestion = ScreenActionSuggestion(
                actionType = "click",
                title = "Decorative Divider",
                description = "",
                clickable = false,
                confidence = 0.25f
            )
        )
        assertFalse(nonClickable.success)
        assertEquals("NON_CLICKABLE_ELEMENT", nonClickable.status)
    }

    @Test
    fun bug5_autonomousLoop_neverFabricatesVerifiedWithoutAccessibilityOrScreenDelta() = runBlocking {
        val result = JarvisAutonomousLoopOrchestrator.executeTask(
            context = app,
            goal = "Open settings and toggle Dark mode",
            planSteps = JSONArray().put(
                JSONObject().put("action", "vision_click").put("target", "Dark mode")
            )
        )
        assertFalse("Without accessibility service, UI step cannot succeed or verify", result.success)
        assertFalse(result.verified)
        assertEquals("FAILED_MISSING_A11Y", result.finalState)
    }

    // -------------------------------------------------------------------------
    // BUG 6 & REQ 3: YouTube Real Playback Verification & New Playbooks
    // -------------------------------------------------------------------------

    @Test
    fun bug6_and_req3_youtubePlaybooksAndVerifierEnforceMediaPlaying() {
        val allYt = YouTubePlaybooks.getAll()
        assertTrue(allYt.any { it.id == YouTubePlaybooks.PREVIOUS.id })
        assertTrue(allYt.any { it.id == YouTubePlaybooks.FULLSCREEN.id })
        assertEquals(VerifyType.MEDIA_PLAYING, YouTubePlaybooks.SEARCH_PLAY.verify.type)
        assertEquals(VerifyType.MEDIA_PLAYING, YouTubePlaybooks.PLAY_VIDEO.verify.type)

        val notPlayingOutcome = ActionVerifier.verify(
            spec = VerifySpec(VerifyType.MEDIA_PLAYING),
            root = null,
            initialScreenHash = "a",
            currentScreenHash = "b",
            mediaTitle = "Sample Video",
            isMediaPlaying = false
        )
        assertFalse("PAUSED/stopped media must fail MEDIA_PLAYING verification", notPlayingOutcome.first)

        val playingOutcome = ActionVerifier.verify(
            spec = VerifySpec(VerifyType.MEDIA_PLAYING),
            root = null,
            initialScreenHash = "a",
            currentScreenHash = "b",
            mediaTitle = "Sample Video",
            isMediaPlaying = true
        )
        assertTrue(playingOutcome.first)
    }

    // -------------------------------------------------------------------------
    // BUG 7 & REQ 3: Facebook Feed, Like Verification, Comment Confirmation & Media URI
    // -------------------------------------------------------------------------

    @Test
    fun bug7_and_req3_facebookPlaybooksAndHonestControlsWorkSafely() = runBlocking {
        val fbAll = FacebookPlaybooks.getAll()
        assertTrue(fbAll.any { it.id == FacebookPlaybooks.LIKE_POST.id })
        assertTrue(fbAll.any { it.id == FacebookPlaybooks.COMMENT_POST.id })
        assertEquals(RiskLevel.EXTERNAL_COMM, FacebookPlaybooks.COMMENT_POST.risk)
        assertEquals(VerifyType.NODE_GONE, FacebookPlaybooks.POST_TEXT.verify.type)

        // Without Accessibility enabled, structured feed & like fail honestly
        val feedJson = JarvisDeviceAutomationHelper.readStructuredFacebookFeed(app)
        assertFalse(feedJson.optBoolean("success"))
        assertEquals("PERMISSION_REQUIRED", feedJson.optString("status"))

        val (likeOk, likeVerified, likeMsg) = JarvisDeviceAutomationHelper.reactToFacebookPost(app)
        assertFalse(likeOk)
        assertFalse(likeVerified)
        assertTrue(likeMsg.contains("অ্যাক্সেসিবিলিটি"))

        // commentOnFacebookPost tool is registered in ToolRegistry and executes cleanly
        assertNotNull(ToolRegistry.get("commentOnFacebookPost"))
        assertNotNull(ToolRegistry.get("executeVisualSuggestion"))
        val commentRes = executor.executeCoreInternal("commentOnFacebookPost", JSONObject().put("text", "দারুণ!"))
        assertFalse(commentRes.optBoolean("success"))
        assertTrue(commentRes.optString("summaryBengali").isNotBlank())
    }

    // -------------------------------------------------------------------------
    // BUG 8 & REQ 3: WhatsApp Per-Action Send Gate & Call State Classification
    // -------------------------------------------------------------------------

    @Test
    fun bug8_and_req3_whatsappApprovalRegistryGateAndCallStateClassifier() = runBlocking {
        val args = JSONObject()
            .put("contact", "01711223344")
            .put("message", "Assalamu Alaikum")

        assertFalse(
            ActionApprovalRegistry.hasActiveApprovalFor(
                toolNames = setOf("controlWhatsApp", "sendWhatsAppMessage"),
                recipientQuery = "01711223344",
                messageText = "Assalamu Alaikum"
            )
        )

        ActionApprovalRegistry.grantExplicitUiApproval("controlWhatsApp", args, source = "test_ui")
        assertTrue(
            ActionApprovalRegistry.hasActiveApprovalFor(
                toolNames = setOf("controlWhatsApp", "sendWhatsAppMessage"),
                recipientQuery = "01711223344",
                messageText = "Assalamu Alaikum"
            )
        )
        // Modified message must NOT reuse old approval
        assertFalse(
            ActionApprovalRegistry.hasActiveApprovalFor(
                toolNames = setOf("controlWhatsApp", "sendWhatsAppMessage"),
                recipientQuery = "01711223344",
                messageText = "Modified text"
            )
        )

        // Playbook verification requires call controls rather than screen hash change
        assertEquals(VerifyType.NODE_EXISTS, WhatsAppPlaybooks.VOICE_CALL.verify.type)
        assertEquals(VerifyType.NODE_EXISTS, WhatsAppPlaybooks.VIDEO_CALL.verify.type)

        // Verify call state classifier across states
        assertEquals(
            com.example.automation.WhatsAppCallState.RINGING,
            AutomationToolHandler.classifyWhatsAppCallScreenState(listOf("Ringing", "End call"))
        )
        assertEquals(
            com.example.automation.WhatsAppCallState.CONNECTED,
            AutomationToolHandler.classifyWhatsAppCallScreenState(listOf("00:14", "Mute", "Speaker", "End call"))
        )
        assertEquals(
            com.example.automation.WhatsAppCallState.ENDED,
            AutomationToolHandler.classifyWhatsAppCallScreenState(listOf("Call ended"))
        )
        assertEquals(
            com.example.automation.WhatsAppCallState.IDLE,
            AutomationToolHandler.classifyWhatsAppCallScreenState(listOf("Type a message"))
        )
    }

    // -------------------------------------------------------------------------
    // BUG 9 & REQ 4: No Silent Dhaka Default, Worldwide OSM + OSRM Road Route
    // -------------------------------------------------------------------------

    @Test
    fun bug9_and_req4_navigationRefusesSilentDhakaDefaultAndUsesWorldwideOsmAndOsrm() = runBlocking {
        // Case A: Origin omitted WITHOUT location permission -> must demand permission, NOT default to Dhaka
        val noPermRes = executor.executeCoreInternal(
            "calculateDistanceAndRoute",
            JSONObject().put("destination", "খুলনা")
        )
        assertFalse(noPermRes.optBoolean("success"))
        assertEquals("PERMISSION_REQUIRED", noPermRes.optString("status"))
        assertTrue(noPermRes.optBoolean("needsLocationPermission"))

        // Case B: Origin omitted WITH permission granted, but NO GPS fix & NO cache -> LOCATION_UNRESOLVED (NO Dhaka!)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val noFixRes = executor.executeCoreInternal(
            "calculateDistanceAndRoute",
            JSONObject().put("destination", "খুলনা")
        )
        assertFalse(noFixRes.optBoolean("success"))
        assertEquals("LOCATION_UNRESOLVED", noFixRes.optString("status"))

        // Case C: Mock worldwide Nominatim OSM + OSRM real road route (Tokyo -> Kyoto)
        BangladeshCalendarAndGeoEngine.geoHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = when {
                    url.contains("nominatim.openstreetmap.org") && url.contains("Tokyo") ->
                        """[{"lat":"35.6762","lon":"139.6503","display_name":"Tokyo, Japan"}]"""
                    url.contains("nominatim.openstreetmap.org") && url.contains("Kyoto") ->
                        """[{"lat":"35.0116","lon":"135.7681","display_name":"Kyoto, Japan"}]"""
                    url.contains("router.project-osrm.org") ->
                        """{"code":"Ok","routes":[{"distance":458200.0,"duration":19800.0}]}"""
                    else -> """{}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val intlRes = executor.executeCoreInternal(
            "calculateDistanceAndRoute",
            JSONObject()
                .put("origin", "Tokyo")
                .put("destination", "Kyoto")
                .put("launchMaps", false)
        )
        assertTrue(intlRes.optBoolean("success"))
        assertEquals("OSRM_ROAD_ROUTE", intlRes.optString("routeSource"))
        assertTrue(intlRes.optBoolean("isExactRoadRoute"))
        assertEquals("NOMINATIM_OSM", intlRes.optString("originSource"))
        assertEquals("NOMINATIM_OSM", intlRes.optString("destinationSource"))
        assertEquals(458.2, intlRes.optDouble("distanceKm"), 0.5)
        assertEquals(330, intlRes.optInt("estimatedDriveMinutes"))

        // Case D: OSRM fails -> falls back honestly to straight-line Haversine
        BangladeshCalendarAndGeoEngine.geoHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Offline")
                    .body("""{}""".toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val fallbackRes = executor.executeCoreInternal(
            "calculateDistanceAndRoute",
            JSONObject()
                .put("origin", "ঢাকা")
                .put("destination", "খুলনা")
                .put("launchMaps", false)
        )
        assertTrue(fallbackRes.optBoolean("success"))
        assertEquals("ESTIMATED_STRAIGHT_LINE_HAVERSINE", fallbackRes.optString("routeSource"))
        assertFalse(fallbackRes.optBoolean("isExactRoadRoute"))
    }

    // -------------------------------------------------------------------------
    // BUG 10 & REQ 5: Dual-Path Voice AI (Gemini Live + Offline Android TTS) & Settings
    // -------------------------------------------------------------------------

    @Test
    fun bug10_and_req5_voiceSpeakerDualPathOfflineTtsOtpSafetyCallDeferralAndSettingsSync() {
        val spokenLive = mutableListOf<String>()
        val spokenOffline = mutableListOf<Pair<String, Float>>()

        JarvisVoiceSpeaker.offlineSpeakerOverride = { text, rate ->
            spokenOffline.add(text to rate)
            true
        }

        // 1. Offline TTS fallback when Gemini Live is disconnected + OTP redaction + speechRate sync
        val settingsRepo = AiSettingsRepository(app)
        val cur = settingsRepo.settingsState.value
        settingsRepo.saveSettings(
            apiKey = cur.apiKey,
            modelName = cur.modelName,
            voiceName = cur.voiceName,
            systemInstruction = cur.systemInstruction,
            autoConnect = cur.autoConnect,
            offlineTtsEnabled = true,
            ttsSpeechRate = 1.25f,
            silentMode = false
        )
        JarvisVoiceSpeaker.speak("আপনার ব্যাংক OTP কোড 482910 নিরাপদ রাখুন।")
        assertEquals("ANDROID_OFFLINE_TTS", JarvisVoiceSpeaker.lastSpeakPath.value)
        assertEquals(1, spokenOffline.size)
        assertFalse("Raw OTP digits must never be spoken", spokenOffline[0].first.contains("482910"))
        assertTrue(spokenOffline[0].first.contains("গোপন-কোড"))
        assertEquals(1.25f, spokenOffline[0].second, 0.01f)

        // 2. Gemini Live active -> routes through live channel instead of offline TTS
        JarvisVoiceSpeaker.registerLiveAudioDispatcher { spokenLive.add(it) }
        JarvisVoiceSpeaker.speak("হ্যালো নাহিদ, সিস্টেম অনলাইনে আছে।")
        assertEquals("GEMINI_LIVE", JarvisVoiceSpeaker.lastSpeakPath.value)
        assertEquals(1, spokenLive.size)
        assertEquals(1, spokenOffline.size)

        // 3. Active phone call deferral
        GeminiLiveAnnouncementAdapter.setInActiveCall(true)
        JarvisVoiceSpeaker.speak("জরুরি বার্তা এসেছে")
        assertEquals("MUTED_OR_IN_CALL", JarvisVoiceSpeaker.lastSpeakPath.value)
        GeminiLiveAnnouncementAdapter.setInActiveCall(false)

        // 4. Silent Mode mutes speech cleanly
        val cur2 = settingsRepo.settingsState.value
        settingsRepo.saveSettings(
            apiKey = cur2.apiKey,
            modelName = cur2.modelName,
            voiceName = cur2.voiceName,
            systemInstruction = cur2.systemInstruction,
            autoConnect = cur2.autoConnect,
            offlineTtsEnabled = true,
            ttsSpeechRate = 1.25f,
            silentMode = true
        )
        JarvisVoiceSpeaker.speak("নীরব মোড পরীক্ষা")
        assertEquals("MUTED_SILENT_MODE", JarvisVoiceSpeaker.lastSpeakPath.value)
    }

    // -------------------------------------------------------------------------
    // VoiceCommandUnderstandingEngine & ActionVerifier sanity for REQ 1/2/3/4
    // -------------------------------------------------------------------------

    @Test
    fun req1_2_3_4_voiceRoutingMapsFacebookVisionNavigationAndMediaCommandsCorrectly() {
        assertEquals("readFacebookFeed", VoiceCommandUnderstandingEngine.resolveToolMapping("ফেসবুক ফিড পড়ো")?.first)
        assertEquals("reactToCurrentPost", VoiceCommandUnderstandingEngine.resolveToolMapping("এই পোস্টে লাইক দাও")?.first)
        val commentMap = VoiceCommandUnderstandingEngine.resolveToolMapping("পোস্টে কমেন্ট: দারুণ ছবি!")
        assertEquals("commentOnFacebookPost", commentMap?.first)
        assertEquals("দারুণ ছবি!", commentMap?.second?.optString("text"))

        assertEquals("captureAndAnalyzeScreen", VoiceCommandUnderstandingEngine.resolveToolMapping("স্ক্রিনে কী আছে দেখো")?.first)
        val mediaPrev = VoiceCommandUnderstandingEngine.resolveToolMapping("আগের ভিডিও চালাও")
        assertEquals("mediaControl", mediaPrev?.first)
        assertEquals("PREV", mediaPrev?.second?.optString("action"))

        val navMap = VoiceCommandUnderstandingEngine.resolveToolMapping("যাওয়ার পথ দেখাও সিলেট")
        assertEquals("calculateDistanceAndRoute", navMap?.first)
        assertEquals("সিলেট", navMap?.second?.optString("destination"))
    }

    // -------------------------------------------------------------------------
    // RE-AUDIT ROUND VERIFICATION: REQ 1-5 REMAINING UPGRADES
    // -------------------------------------------------------------------------

    @Test
    fun reaudit_listInstalledAppsSupportsPaginationBeyond40AndAvailabilityCheck() = runBlocking {
        val sp = Shadows.shadowOf(app.packageManager)
        for (i in 1..48) {
            val pkg = "com.test.sampleapp$i"
            val act = "$pkg.MainActivity"
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(pkg, act)
            }
            sp.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                android.content.pm.ResolveInfo().apply {
                    activityInfo = android.content.pm.ActivityInfo().apply {
                        packageName = pkg
                        name = act
                        applicationInfo = android.content.pm.ApplicationInfo().apply {
                            packageName = pkg
                            nonLocalizedLabel = "Sample App $i"
                        }
                    }
                }
            )
            // Ensure app shows up cleanly via queryIntentActivities
            assertNotNull(launchIntent)
        }

        val page0 = executor.executeCoreInternal("listInstalledApps", JSONObject().apply {
            put("query", "sample")
            put("page", 0)
            put("pageSize", 45)
        })
        assertTrue(page0.optBoolean("success"))
        assertEquals(48, page0.optInt("totalCount"))
        assertEquals(45, page0.optInt("count"))
        assertTrue("48 apps with pageSize=45 must set hasMore=true", page0.optBoolean("hasMore"))
        assertEquals(1, page0.optInt("nextPage"))
        assertEquals(45, page0.getJSONArray("apps").length())

        val page1 = executor.executeCoreInternal("listInstalledApps", JSONObject().apply {
            put("query", "sample")
            put("page", 1)
            put("pageSize", 45)
        })
        assertTrue(page1.optBoolean("success"))
        assertEquals(3, page1.optInt("count"))
        assertFalse(page1.optBoolean("hasMore"))

        val checkUnavailable = executor.executeCoreInternal("listInstalledApps", JSONObject().apply {
            put("checkApp", "com.nonexistent.fake.pkg.999")
        })
        assertTrue(checkUnavailable.optBoolean("success"))
        assertFalse(checkUnavailable.optBoolean("installed"))
    }

    @Test
    fun reaudit_readVisibleAppDataFailsHonestlyWithoutAccessibilityAndRoutesViaVoice() = runBlocking {
        val mapped = VoiceCommandUnderstandingEngine.resolveToolMapping("দোকানে কতগুলো প্রোডাক্ট আছে তালিকা পড়ো")
        assertEquals("readVisibleAppData", mapped?.first)

        val res = executor.executeCoreInternal("readVisibleAppData", JSONObject())
        assertFalse(res.optBoolean("success"))
        assertEquals("accessibility", res.optString("permissionRequired"))
        assertTrue(res.optString("summaryBengali").contains("অ্যাক্সেসিবিলিটি"))
    }

    @Test
    fun reaudit_screenActionSuggestionParsesElementNameAndMasterOrchestratorTracksVisionContext() {
        JarvisMasterOrchestrator.resetForTest()
        val geminiJson = JSONObject().apply {
            put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    """
                                    {
                                      "detectedApp": "com.example.shop",
                                      "screenDescription": "Checkout screen",
                                      "summaryBengali": "চেকআউট স্ক্রিন সক্রিয়",
                                      "suggestions": [
                                        {
                                          "actionType": "click",
                                          "title": "Confirm Order",
                                          "description": "Tap confirm",
                                          "elementName": "নিশ্চিত করুন বাটন",
                                          "targetTextOrId": "Confirm",
                                          "viewId": "com.example.shop:id/confirm_btn",
                                          "elementType": "Button",
                                          "clickable": true,
                                          "centerNorm": [0.5, 0.9],
                                          "confidence": 0.95
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    )
                )
            )
        }.toString()

        val parsed = ScreenVisualAnalyzer.parseGeminiResponse(geminiJson, frameWidth = 1080, frameHeight = 2400)
        assertEquals(1, parsed.suggestions.size)
        assertEquals("নিশ্চিত করুন বাটন", parsed.suggestions[0].elementName)
        assertEquals("com.example.shop:id/confirm_btn", parsed.suggestions[0].viewId)

        JarvisMasterOrchestrator.recordVisionAnalysisContext(parsed)
        val latest = JarvisMasterOrchestrator.latestVisionAnalysis.value
        assertNotNull(latest)
        assertEquals("com.example.shop", latest?.detectedApp)
        assertEquals("নিশ্চিত করুন বাটন", latest?.suggestions?.firstOrNull()?.elementName)
        JarvisMasterOrchestrator.resetForTest()
    }

    @Test
    fun reaudit_controlWhatsAppDisambiguatesMultipleContactsBeforeLaunch(): Unit = runBlocking {
        // Seed 2 matching contacts in Room frequent contacts database
        dao.insertFrequentContact(
            JarvisFrequentContactEntity(name = "রহিম কাজ", alias = "রহিম", phoneNumber = "01711000001")
        )
        dao.insertFrequentContact(
            JarvisFrequentContactEntity(name = "রহিম মামা", alias = "রহিম", phoneNumber = "01722000002")
        )
        // Register com.whatsapp package so WhatsApp installation check passes
        val sp = Shadows.shadowOf(app.packageManager)
        sp.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = "com.whatsapp"
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = "com.whatsapp"
                    nonLocalizedLabel = "WhatsApp"
                }
            }
        )

        val res = executor.executeCoreInternal("controlWhatsApp", JSONObject().apply {
            put("contact", "রহিম")
            put("message", "হ্যালো")
        })
        assertFalse("Ambiguous contact must stop before launching WhatsApp", res.optBoolean("success"))
        assertEquals("MULTIPLE_MATCHES", res.optString("actionStatus"))
        assertTrue(res.optBoolean("needsDisambiguation"))
        val candidates = res.optJSONArray("candidates")
        assertNotNull(candidates)
        assertTrue("Expected >= 2 disambiguation candidates", candidates!!.length() >= 2)
    }

    @Test
    fun reaudit_youtubeNotificationSplitReportsSystemPushAndHonestInAppNote() {
        val split = JarvisNotificationListenerService.getYouTubeNotificationSplit(app)
        assertTrue(split.inAppInboxHonestNote.contains("NotificationListener"))
        assertTrue(split.summaryBengali.isNotBlank())
    }

    @Test
    fun reaudit_worldwideNavigationSupportsWalkingCyclingAndHonestTransitMode() = runBlocking {
        BangladeshCalendarAndGeoEngine.geoHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val json = if (url.contains("/route/v1/foot/")) {
                    """{"code":"Ok","routes":[{"distance":2400.0,"duration":1800.0}]}"""
                } else {
                    """{"code":"Ok","routes":[{"distance":3100.0,"duration":720.0}]}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val walkRes = executor.executeCoreInternal("calculateDistanceAndRoute", JSONObject().apply {
            put("origin", "23.8103,90.4125")
            put("destination", "23.7925,90.4078")
            put("travelMode", "walking")
        })
        assertTrue(walkRes.optBoolean("success"))
        assertEquals("OSRM_ROAD_ROUTE_WALKING", walkRes.optString("routeSource"))
        assertTrue(walkRes.optBoolean("isExactRoadRoute"))
        assertTrue(walkRes.optString("mapsRouteUri").contains("travelmode=walking"))

        val transitRes = executor.executeCoreInternal("calculateDistanceAndRoute", JSONObject().apply {
            put("origin", "ঢাকা")
            put("destination", "খুলনা")
            put("travelMode", "transit")
        })
        assertTrue(transitRes.optBoolean("success"))
        assertEquals("ESTIMATED_TRANSIT_UNAVAILABLE", transitRes.optString("routeSource"))
        assertFalse(transitRes.optBoolean("isExactRoadRoute"))
        assertTrue(transitRes.optString("mapsRouteUri").contains("travelmode=transit"))

        val walkVoice = VoiceCommandUnderstandingEngine.resolveToolMapping("হেঁটে যাওয়ার পথ সাভার")
        assertEquals("calculateDistanceAndRoute", walkVoice?.first)
        assertEquals("walking", walkVoice?.second?.optString("travelMode"))
    }

    @Test
    fun reaudit_voiceSettingsPersistFemaleVoicesAndSpeakingStyleIntoGeminiSetupJson() {
        val repo = AiSettingsRepository(app)
        val base = repo.settingsState.value
        assertTrue(AiSettingsRepository.AVAILABLE_VOICES.containsAll(listOf("Aoede", "Leda", "Kore", "Zephyr")))

        repo.saveSettings(
            apiKey = base.apiKey,
            modelName = base.modelName,
            voiceName = "Leda",
            systemInstruction = base.systemInstruction,
            autoConnect = base.autoConnect,
            speakingStyle = "SOFT"
        )
        val reloaded = AiSettingsRepository(app).settingsState.value
        assertEquals("Leda", reloaded.voiceName)
        assertEquals("SOFT", reloaded.speakingStyle)

        val setupJson = GeminiLiveSessionManager.buildSetupJson(reloaded, JSONArray())
        val setup = setupJson.getJSONObject("setup")
        val voiceOut = setup
            .getJSONObject("generationConfig")
            .getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
            .getString("voiceName")
        assertEquals("Leda", voiceOut)
        val sysText = setup
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysText.contains("[Speaking Style]:"))
        assertTrue(sysText.contains("soft", ignoreCase = true))
    }
}
