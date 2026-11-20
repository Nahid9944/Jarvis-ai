package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.OfflineWakeWordEngine
import com.example.audio.WakeWordModelManager
import com.example.automation.AutomationToolHandler
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.HttpFetchResult
import com.example.automation.TextLearningCategory
import com.example.automation.TextLearningEngine
import com.example.automation.YouTubeAnalyzedStep
import com.example.automation.YouTubeMultimodalAnalysis
import com.example.automation.YouTubeTutorialImporter
import com.example.data.local.AssistantDatabase
import com.example.data.repository.JarvisSkillRepository
import com.example.research.ExtractionStatus
import com.example.research.SearchResultItem
import com.example.research.UniversalBrowserAgent
import com.example.research.WebPageContentExtractor
import com.example.research.WebSearchProvider
import com.example.tools.ToolRegistry
import com.example.util.BangladeshCalendarAndGeoEngine
import com.example.util.JarvisDeviceAutomationHelper
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2UpgradesVerificationTest {

    private lateinit var app: Application
    private lateinit var inMemoryDb: AssistantDatabase

    private lateinit var origYtFetcher: (String) -> HttpFetchResult
    private lateinit var origYtApiKeyProvider: (Context?) -> String
    private var origYtMultimodal: ((String, String, String) -> YouTubeMultimodalAnalysis)? = null

    private lateinit var origGhFetcher: (String) -> HttpFetchResult
    private var origGhHeaderFetcher: ((String, Map<String, String>) -> HttpFetchResult)? = null
    private lateinit var origGhAuthProvider: (Context?) -> String
    private lateinit var origGhNetCheck: (Context?) -> Boolean

    private var origPageClient: OkHttpClient? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(app, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        origYtFetcher = YouTubeTutorialImporter.httpFetcher
        origYtApiKeyProvider = YouTubeTutorialImporter.apiKeyProvider
        origYtMultimodal = YouTubeTutorialImporter.videoMultimodalAnalyzer

        origGhFetcher = GitHubRepositoryLearningEngine.httpFetcher
        origGhHeaderFetcher = GitHubRepositoryLearningEngine.httpFetcherWithHeaders
        origGhAuthProvider = GitHubRepositoryLearningEngine.authTokenProvider
        origGhNetCheck = GitHubRepositoryLearningEngine.networkAvailabilityCheck

        origPageClient = WebPageContentExtractor.httpClientOverride
    }

    @After
    fun tearDown() {
        YouTubeTutorialImporter.httpFetcher = origYtFetcher
        YouTubeTutorialImporter.apiKeyProvider = origYtApiKeyProvider
        YouTubeTutorialImporter.videoMultimodalAnalyzer = origYtMultimodal

        GitHubRepositoryLearningEngine.httpFetcher = origGhFetcher
        GitHubRepositoryLearningEngine.httpFetcherWithHeaders = origGhHeaderFetcher
        GitHubRepositoryLearningEngine.authTokenProvider = origGhAuthProvider
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = origGhNetCheck

        WebPageContentExtractor.httpClientOverride = origPageClient
        inMemoryDb.close()
    }

    // -----------------------------------------------------------------
    // PRIORITY 1: YouTube Timed Transcripts, Multimodal Video & Drafts
    // -----------------------------------------------------------------

    @Test
    fun youtubeImporter_parsesTimedCaptionsPreservesTimestampsAndSupportsDraftMode() = runBlocking {
        val videoId = "abc123XYZ09"
        val captionXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <transcript>
              <text start="5.2" dur="3.0">Open WhatsApp app</text>
              <text start="65.0" dur="4.0">Click Search button</text>
              <text start="125.7" dur="3.5">Type Rahim</text>
            </transcript>
        """.trimIndent()

        YouTubeTutorialImporter.httpFetcher = { url ->
            when {
                url.contains("oembed") -> HttpFetchResult(200, """{"title":"Android WhatsApp Automation Guide"}""")
                url.contains("youtube.com/watch") -> HttpFetchResult(
                    200,
                    """{"captionTracks":[{"baseUrl":"https://www.youtube.com/api/timedtext?v=$videoId"}]}"""
                )
                url.contains("timedtext") -> HttpFetchResult(200, captionXml)
                else -> HttpFetchResult(404)
            }
        }

        val repo = JarvisSkillRepository(inMemoryDb.jarvisSkillDao())
        val res = YouTubeTutorialImporter.importFromTutorial(
            context = app,
            url = "https://www.youtube.com/watch?v=$videoId",
            skillRepo = repo,
            draftMode = true
        )

        assertTrue(res.optBoolean("success"))
        assertEquals("PUBLIC_CAPTIONS", res.optString("extractionMode"))
        assertEquals("DRAFT", res.optString("skillStatus"))
        assertEquals("vnd.youtube:$videoId", res.optString("playbackUri"))
        val stepsArr = res.getJSONArray("steps")
        assertEquals(3, stepsArr.length())
        assertEquals("00:05", stepsArr.getJSONObject(0).optString("timestamp"))
        assertEquals("01:05", stepsArr.getJSONObject(1).optString("timestamp"))
        assertEquals("02:05", stepsArr.getJSONObject(2).optString("timestamp"))

        val intent = YouTubeTutorialImporter.createPlaybackIntent("https://youtu.be/$videoId")
        assertNotNull(intent)
        assertEquals("vnd.youtube:$videoId", intent?.data.toString())
    }

    @Test
    fun youtubeImporter_usesGeminiMultimodalWhenCaptionsMissingAndNeverFakesOffline() = runBlocking {
        val videoId = "xyz987LMN12"
        YouTubeTutorialImporter.httpFetcher = { url ->
            if (url.contains("oembed")) HttpFetchResult(200, """{"title":"Android Settings Shortcut"}""")
            else HttpFetchResult(200, "<html>No captions</html>")
        }
        YouTubeTutorialImporter.apiKeyProvider = { "test-valid-key" }
        YouTubeTutorialImporter.videoMultimodalAnalyzer = { _, _, _ ->
            YouTubeMultimodalAnalysis(
                success = true,
                videoSummaryBn = "সেটিংসে ডিসপ্লে ওপেন করার প্রক্রিয়া।",
                topicRelevantToAndroid = true,
                steps = listOf(
                    YouTubeAnalyzedStep(
                        timestampLabel = "00:12",
                        actionType = "OPEN_APP",
                        targetViewId = "com.android.settings",
                        targetText = "Settings",
                        descriptionBn = "সেটিংস অ্যাপ খোলা"
                    ),
                    YouTubeAnalyzedStep(
                        timestampLabel = "00:42",
                        actionType = "CLICK",
                        targetText = "Display",
                        descriptionBn = "Display অপশনে ক্লিক করা"
                    )
                )
            )
        }

        val mmRes = YouTubeTutorialImporter.importFromTutorial(
            context = app,
            url = "https://youtu.be/$videoId"
        )
        assertTrue(mmRes.optBoolean("success"))
        assertEquals("GEMINI_MULTIMODAL_VIDEO", mmRes.optString("extractionMode"))
        assertEquals("00:42", mmRes.getJSONArray("steps").getJSONObject(1).optString("timestamp"))

        // Now turn off multimodal + captions: MUST truthfully return TRANSCRIPT_REQUIRED
        YouTubeTutorialImporter.videoMultimodalAnalyzer = null
        YouTubeTutorialImporter.apiKeyProvider = { "" }
        val noCapRes = YouTubeTutorialImporter.importFromTutorial(
            context = app,
            url = "https://youtu.be/$videoId"
        )
        assertFalse(noCapRes.optBoolean("success"))
        assertEquals("TRANSCRIPT_REQUIRED", noCapRes.optString("status"))
    }

    // -----------------------------------------------------------------
    // PRIORITY 2: GitHub Deep Inspection, Security Policy & Capability Diff
    // -----------------------------------------------------------------

    @Test
    fun githubLearningEngine_blocksDclAndExtractsFullProjectIntelligenceForSafeRepo() = runBlocking {
        GitHubRepositoryLearningEngine.networkAvailabilityCheck = { true }
        GitHubRepositoryLearningEngine.authTokenProvider = { "" }

        // 1. Unsafe repo containing DexClassLoader -> BLOCKED
        GitHubRepositoryLearningEngine.httpFetcher = { url ->
            when {
                url.endsWith("/repos/unsafe/dcl-plugin") -> HttpFetchResult(
                    200,
                    """{"default_branch":"main","language":"Kotlin","description":"Plugin loader"}"""
                )
                url.contains("/git/trees/main") -> HttpFetchResult(
                    200,
                    """{"tree":[{"path":"AndroidManifest.xml"},{"path":"app/src/Loader.kt"}]}"""
                )
                url.endsWith("Loader.kt") -> HttpFetchResult(
                    200,
                    """import dalvik.system.DexClassLoader
                       class Loader { fun load() = DexClassLoader("/tmp/a.dex", null, null, null) }"""
                )
                else -> HttpFetchResult(404)
            }
        }
        val blocked = GitHubRepositoryLearningEngine.importFromRepository(
            context = app,
            repoUrl = "https://github.com/unsafe/dcl-plugin"
        )
        assertFalse(blocked.optBoolean("success"))
        assertEquals("BLOCKED", blocked.optString("status"))
        assertEquals("SECURITY_POLICY_BLOCKED", blocked.optString("error"))
        assertTrue(blocked.getJSONObject("securityAudit").optBoolean("blocked"))

        // 2. Safe repo -> deep inspection + LICENSE + build deps + ToolRegistry capability diff + skill import
        GitHubRepositoryLearningEngine.httpFetcher = { url ->
            when {
                url.endsWith("/repos/nahid/voice-assistant") -> HttpFetchResult(
                    200,
                    """{"default_branch":"main","language":"Kotlin","description":"Android voice assistant using AccessibilityService"}"""
                )
                url.contains("/git/trees/main") -> HttpFetchResult(
                    200,
                    """{"tree":[
                        {"path":"LICENSE"},
                        {"path":"app/AndroidManifest.xml"},
                        {"path":"app/build.gradle.kts"},
                        {"path":"app/src/VoiceService.kt"},
                        {"path":"jarvis-skill.json"},
                        {"path":"README.md"}
                    ]}"""
                )
                url.endsWith("LICENSE") -> HttpFetchResult(200, "MIT License\nPermission is hereby granted...")
                url.endsWith("AndroidManifest.xml") -> HttpFetchResult(200, """<uses-permission android:name="android.permission.RECORD_AUDIO"/>""")
                url.endsWith("build.gradle.kts") -> HttpFetchResult(200, """dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }""")
                url.endsWith("VoiceService.kt") -> HttpFetchResult(200, """class VoiceService { val sr = "SpeechRecognizer" }""")
                url.endsWith("jarvis-skill.json") -> HttpFetchResult(
                    200,
                    """{"name":"Quick Settings","nameBengali":"কুইক সেটিংস","targetPackageName":"com.android.settings","steps":[{"actionType":"OPEN_APP","targetViewId":"com.android.settings","descriptionBengali":"সেটিংস খোলা"}]}"""
                )
                url.endsWith("README.md") -> HttpFetchResult(200, "# Voice Assistant\nAndroid AccessibilityService automation.")
                else -> HttpFetchResult(404)
            }
        }

        val repo = JarvisSkillRepository(inMemoryDb.jarvisSkillDao())
        val safe = GitHubRepositoryLearningEngine.importFromRepository(
            context = app,
            repoUrl = "https://github.com/nahid/voice-assistant",
            skillRepo = repo
        )
        assertTrue(safe.optBoolean("success"))
        assertEquals("MIT", safe.optString("license"))
        assertTrue(safe.getJSONArray("inspectedFiles").toString().contains("VoiceService.kt"))
        val capDiff = safe.getJSONObject("capabilityDiff")
        assertEquals(ToolRegistry.getAll().size, capDiff.optInt("nahidToolCountCompared"))
        assertTrue(capDiff.getJSONArray("discoveredCapabilities").toString().contains("voice_recognition"))
        val proposal = safe.getJSONObject("integrationProposal")
        assertTrue(proposal.getJSONArray("detectedDependencies").toString().contains("com.squareup.okhttp3:okhttp:4.12.0"))
        assertTrue(proposal.optString("proposalSummaryBn").isNotBlank())
    }

    // -----------------------------------------------------------------
    // PRIORITY 3: Text-Based Learning Workspace Engine
    // -----------------------------------------------------------------

    @Test
    fun textLearningEngine_classifiesDryRunsAndPersistsIntoRoom() = runBlocking {
        val dao = inMemoryDb.assistantDao()
        val skillRepo = JarvisSkillRepository(inMemoryDb.jarvisSkillDao())

        val memCls = TextLearningEngine.classifyInstruction("মনে রাখো আমার নাম নাহিদ ও প্রিয় রঙ সবুজ")
        assertEquals(TextLearningCategory.MEMORY, memCls.category)

        val routineCls = TextLearningEngine.classifyInstruction("প্রতিদিন সকাল ৮টায় আমাকে পানি খাওয়ার কথা মনে করিয়ে দিও")
        assertEquals(TextLearningCategory.ROUTINE, routineCls.category)
        assertTrue(routineCls.scheduleHint.contains("প্রতিদিন"))

        val skillCls = TextLearningEngine.classifyInstruction("Open WhatsApp\nClick Search\nType Rahim")
        assertEquals(TextLearningCategory.EXECUTABLE_SKILL_DRAFT, skillCls.category)
        assertEquals(3, skillCls.extractedSteps.size)

        // Dry Run does not persist to DB
        val dryRes = TextLearningEngine.learnFromText(
            instructionText = "Open Settings\nClick Display",
            title = "Display Shortcut",
            dryRunOnly = true,
            daoOverride = dao,
            skillRepoOverride = skillRepo
        )
        assertEquals("DRY_RUN_READY", dryRes.optString("status"))
        assertTrue(skillRepo.getSkillById(dryRes.optString("skillId")) == null)

        // Persist Memory + Skill
        val memRes = TextLearningEngine.learnFromText(
            instructionText = "মনে রাখো আমার অফিস মতিঝিলে",
            title = "অফিসের ঠিকানা",
            dryRunOnly = false,
            daoOverride = dao,
            skillRepoOverride = skillRepo
        )
        assertTrue(memRes.optBoolean("success"))
        assertTrue(dao.getAllActiveMemoriesSync().any { it.content.contains("মতিঝিল") })

        val skillRes = TextLearningEngine.learnFromText(
            instructionText = "Open Settings\nClick Battery",
            title = "Battery Check",
            dryRunOnly = false,
            saveAsActiveSkillIfValid = true,
            daoOverride = dao,
            skillRepoOverride = skillRepo
        )
        assertTrue(skillRes.optBoolean("success"))
        assertEquals("ACTIVE", skillRes.optString("skillStatus"))
        val savedSkill = skillRepo.getSkillById(skillRes.optString("skillId"))
        assertNotNull(savedSkill)
        assertEquals(2, savedSkill?.stepCount)
    }

    // -----------------------------------------------------------------
    // PRIORITY 4: Offline Wake-Word Engine & Truthful Capability Audit
    // -----------------------------------------------------------------

    @Test
    fun offlineWakeWordEngine_matchesMultilingualAndReportsCapabilitiesTruthfully() {
        val bnMatch = OfflineWakeWordEngine.matchWakePhrase("এই জারভিস এখন সময় কয়টা")
        assertTrue(bnMatch.matched)
        assertEquals("এখন সময় কয়টা", bnMatch.remainderCommand)

        val engMatch = OfflineWakeWordEngine.matchWakePhrase("Hey Jarvis, বাবাকে কল দাও")
        assertTrue(engMatch.matched)
        assertEquals("বাবাকে কল দাও", engMatch.remainderCommand)

        val cap = WakeWordModelManager.inspectCapabilities(app)
        assertFalse(cap.isBundledNeuralKeywordModelPresent)
        assertTrue(cap.detailBengali.isNotBlank())
    }

    // -----------------------------------------------------------------
    // PRIORITY 5: Voice App Aliases, Discovery & WhatsApp Tick Verification
    // -----------------------------------------------------------------

    @Test
    fun deviceAutomationHelper_resolvesBanglaAppAliasesAndClassifiesWhatsAppTicks() {
        assertEquals("com.bKash.customerapp", JarvisDeviceAutomationHelper.resolveAppPackageAlias("বিকাশ অ্যাপ"))
        assertEquals("com.konasl.nagad", JarvisDeviceAutomationHelper.resolveAppPackageAlias("নগদ"))
        assertEquals("com.whatsapp", JarvisDeviceAutomationHelper.resolveAppPackageAlias("হোয়াটসঅ্যাপ"))
        assertEquals("com.imo.android.imoim", JarvisDeviceAutomationHelper.resolveAppPackageAlias("ইমো"))
        assertEquals("com.spotify.music", JarvisDeviceAutomationHelper.resolveAppPackageAlias("স্পটিফাই"))

        val discovered = JarvisDeviceAutomationHelper.discoverInstalledApps(app)
        assertNotNull(discovered)

        assertEquals("READ", AutomationToolHandler.classifyWhatsAppTickStatus(listOf("10:30 AM", "Read")))
        assertEquals("READ", AutomationToolHandler.classifyWhatsAppTickStatus(listOf("বার্তা পড়া হয়েছে")))
        assertEquals("DELIVERED", AutomationToolHandler.classifyWhatsAppTickStatus(listOf("Delivered")))
        assertEquals("SENT", AutomationToolHandler.classifyWhatsAppTickStatus(listOf("Sent")))
        assertEquals("PENDING", AutomationToolHandler.classifyWhatsAppTickStatus(listOf("Waiting for network")))
        assertEquals("UNKNOWN", AutomationToolHandler.classifyWhatsAppTickStatus(emptyList()))
    }

    // -----------------------------------------------------------------
    // PRIORITY 6: Deep Web Research + Cross-Source Corroboration
    // -----------------------------------------------------------------

    @Test
    fun universalBrowserAgent_deepResearchExtractsPagesDistinguishesBlockedAndCorroborates() = runBlocking {
        val mockProvider = object : WebSearchProvider {
            override val providerId: String = "MockResearchProvider"
            override val requiresApiKey: Boolean = false
            override fun isConfigured(): Boolean = true
            override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> {
                return Result.success(
                    listOf(
                        SearchResultItem(
                            title = "Kotlin Docs",
                            url = "https://kotlinlang.org/coroutines",
                            snippet = "Coroutines overview.",
                            sourceDomain = "kotlinlang.org",
                            providerName = "MockResearchProvider",
                            verified = true
                        ),
                        SearchResultItem(
                            title = "Android Developers",
                            url = "https://developer.android.com/kotlin/coroutines",
                            snippet = "Best practices for coroutines on Android.",
                            sourceDomain = "developer.android.com",
                            providerName = "MockResearchProvider",
                            verified = true
                        ),
                        SearchResultItem(
                            title = "Private Portal",
                            url = "https://private.example.com/article",
                            snippet = "Login gated snippet only.",
                            sourceDomain = "private.example.com",
                            providerName = "MockResearchProvider",
                            verified = true
                        )
                    )
                )
            }
        }

        WebPageContentExtractor.httpClientOverride = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("private.example.com") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                        .build()
                    else -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            "<html><head><title>Verified Coroutines Guide</title></head><body><p>Kotlin coroutines structured concurrency documentation.</p></body></html>"
                                .toResponseBody("text/html; charset=utf-8".toMediaType())
                        )
                        .build()
                }
            }
            .build()

        val deepRes = UniversalBrowserAgent.deepResearch(
            query = "Kotlin coroutines",
            customProviders = listOf(mockProvider),
            maxPagesToExtract = 3
        )

        assertTrue(deepRes.success)
        assertEquals("VERIFIED_DEEP_RESEARCH", deepRes.statusCodeLabel)
        assertEquals("MULTI_SOURCE_CORROBORATED", deepRes.crossCheckStatus)
        assertEquals(2, deepRes.extractedSourceCount)
        assertEquals(1, deepRes.blockedSourceCount)

        assertTrue(deepRes.findings[0].contentExtracted)
        assertFalse(deepRes.findings[0].snippetOnly)
        assertEquals(ExtractionStatus.EXTRACTED, deepRes.findings[0].extractionStatus)
        assertTrue(deepRes.findings[0].wrappedExcerpt.contains("[BEGIN_UNTRUSTED_EXTERNAL_CONTENT"))

        assertFalse(deepRes.findings[2].contentExtracted)
        assertTrue(deepRes.findings[2].snippetOnly)
        assertEquals(ExtractionStatus.BLOCKED_LOGIN_WALL, deepRes.findings[2].extractionStatus)
    }

    // -----------------------------------------------------------------
    // PRIORITY 7: Revised Bangladesh Calendar (2019), Clock & Haversine
    // -----------------------------------------------------------------

    @Test
    fun bangladeshCalendarAndGeoEngine_followsBanglaAcademy2019RulesAndHaversineMath() {
        // 1. Pohela Boishakh = 14 April
        val boishakh1 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2026, 4, 14))
        assertEquals(1, boishakh1.day)
        assertEquals(1, boishakh1.monthIndex)
        assertEquals("বৈশাখ", boishakh1.monthNameBn)
        assertEquals(1433, boishakh1.banglaYear)

        // 2. First 6 months (Boishakh..Ashwin) = 31 days each -> 16 Oct 2026 = 31 Ashwin 1433
        val ashwin31 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2026, 10, 16))
        assertEquals(31, ashwin31.day)
        assertEquals(6, ashwin31.monthIndex)
        assertEquals("আশ্বিন", ashwin31.monthNameBn)

        val kartik1 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2026, 10, 17))
        assertEquals(1, kartik1.day)
        assertEquals(7, kartik1.monthIndex)
        assertEquals("কার্তিক", kartik1.monthNameBn)

        // 3. Falgun starts on 14 Feb under revised calendar
        val falgun1 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2026, 2, 14))
        assertEquals(1, falgun1.day)
        assertEquals(11, falgun1.monthIndex)
        assertEquals("ফাল্গুন", falgun1.monthNameBn)
        assertEquals(1432, falgun1.banglaYear)

        // 4. Non-leap Falgun = 29 days (2025 -> 14 Mar is 29 Falgun; 15 Mar is 1 Chaitra)
        val nonLeapEnd = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2025, 3, 14))
        assertEquals(29, nonLeapEnd.day)
        assertEquals(11, nonLeapEnd.monthIndex)
        assertFalse(nonLeapEnd.isLeapFalgun)
        val nonLeapChaitra1 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2025, 3, 15))
        assertEquals(1, nonLeapChaitra1.day)
        assertEquals(12, nonLeapChaitra1.monthIndex)

        // 5. Leap year Falgun = 30 days (2024 -> 14 Mar is 30 Falgun; 15 Mar is 1 Chaitra)
        val leapEnd = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2024, 3, 14))
        assertEquals(30, leapEnd.day)
        assertEquals(11, leapEnd.monthIndex)
        assertTrue(leapEnd.isLeapFalgun)
        val leapChaitra1 = BangladeshCalendarAndGeoEngine.toRevisedBangladeshBanglaDate(LocalDate.of(2024, 3, 15))
        assertEquals(1, leapChaitra1.day)
        assertEquals(12, leapChaitra1.monthIndex)

        // 6. Dhaka clock + Haversine Dhaka -> Khulna distance & ETA
        val clock = BangladeshCalendarAndGeoEngine.getCurrentDhakaDateTimeReport()
        assertEquals("Asia/Dhaka", clock.optString("timezone"))
        assertTrue(clock.optString("banglaFormatted").isNotBlank())

        val route = BangladeshCalendarAndGeoEngine.calculateDistanceAndEta(
            originLat = 23.8103,
            originLng = 90.4125,
            destLat = 22.8456,
            destLng = 89.5403,
            originLabel = "ঢাকা",
            destLabel = "খুলনা"
        )
        assertTrue("Expected Dhaka-Khulna ~135-145 km, got ${route.distanceKm}", route.distanceKm in 130.0..150.0)
        assertTrue(route.estimatedDriveMinutes > 0)
        assertTrue(route.estimatedWalkMinutes > route.estimatedDriveMinutes)
        assertTrue(route.mapsRouteUri.startsWith("https://www.google.com/maps/dir/"))
    }
}
