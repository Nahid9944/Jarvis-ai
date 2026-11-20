package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAgentRegistry
import com.example.ai.AiSettingsRepository
import com.example.ai.AiSettingsState
import com.example.ai.AssistantToolExecutor
import com.example.ai.GeminiLiveSessionManager
import com.example.ai.LiveConversationContextBridge
import com.example.ai.LiveSessionState
import com.example.data.local.AiMemoryEntity
import com.example.data.local.AssistantDatabase
import com.example.knowledge.AsyncKnowledgeStorageManager
import com.example.knowledge.CacheTier
import com.example.knowledge.ClaimStatus
import com.example.knowledge.ClaimVerificationEngine
import com.example.knowledge.IntelligentKnowledgeRouter
import com.example.knowledge.KnowledgeCitation
import com.example.knowledge.KnowledgeL1Cache
import com.example.knowledge.KnowledgePerformanceTracker
import com.example.knowledge.KnowledgeServerClient
import com.example.knowledge.KnowledgeSourceRegistry
import com.example.knowledge.KnowledgeStorageState
import com.example.knowledge.RetrievalMode
import com.example.research.SearchResultItem
import com.example.research.UniversalBrowserAgent
import com.example.research.WebPageContentExtractor
import com.example.research.WebSearchProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntelligentKnowledgeEngineTest {

    private lateinit var app: Application
    private lateinit var db: AssistantDatabase
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AssistantDatabase.resetInstanceForTest()
        IntelligentKnowledgeRouter.resetForTest()
        LiveConversationContextBridge.resetForTest()
        WebPageContentExtractor.httpClientOverride = null
        db = Room.inMemoryDatabaseBuilder(app, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        try {
            server?.stop(0)
        } catch (_: Exception) {
        }
        server = null
        WebPageContentExtractor.httpClientOverride = null
        IntelligentKnowledgeRouter.resetForTest()
        LiveConversationContextBridge.resetForTest()
        db.close()
        AssistantDatabase.resetInstanceForTest()
    }

    private fun startLocalHttpServer(handler: (path: String, query: String?, method: String, headers: Map<String, String>) -> Triple<Int, String, Long>): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.executor = Executors.newCachedThreadPool()
        srv.createContext("/") { ex ->
            val hdrs = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val (code, body, delayMs) = handler(ex.requestURI.path, ex.requestURI.rawQuery, ex.requestMethod, hdrs)
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                }
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", if (body.trimStart().startsWith("{")) "application/json; charset=utf-8" else "text/html; charset=utf-8")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    @Test
    fun fourTierCascade_l4ToAsyncL2ToL1_andL3ServerFallback() = runBlocking {
        val kDao = db.knowledgeDao()

        // Step 1: L1/L2/L3 empty -> hits L4 mock search + real local HTTP content extraction
        val pageHits = AtomicInteger(0)
        val baseUrl = startLocalHttpServer { path, _, _, _ ->
            pageHits.incrementAndGet()
            when {
                path.contains("gov-source") -> Triple(
                    200,
                    "<html><head><title>Bangladesh Digital Policy 2026</title></head><body><p>Bangladesh digital commerce guideline 2026 ensures consumer verification and digital escrow protection across nationwide platforms.</p></body></html>",
                    0L
                )
                path.contains("science-source") -> Triple(
                    200,
                    "<html><head><title>Independent Policy Review</title></head><body><p>Bangladesh digital commerce guideline 2026 mandates consumer verification, transparent disclosures, and digital escrow protection.</p></body></html>",
                    0L
                )
                path.contains("/v1/knowledge/search") -> {
                    val json = JSONObject().apply {
                        put("answerSummaryBn", "L3 সার্ভার উত্তর: ঢাকায় বর্তমানে তাপমাত্রা ২৯ ডিগ্রি সেলসিয়াস।")
                        put("verificationStatus", "VERIFIED")
                        put("results", JSONArray().apply {
                            put(JSONObject().apply {
                                put("title", "Weather Portal 1")
                                put("url", "https://bmd.gov.bd/weather/dhaka")
                                put("domain", "bmd.gov.bd")
                                put("snippet", "Dhaka weather temperature is 29 degrees Celsius with light breeze across Dhaka city.")
                                put("contentExtracted", true)
                            })
                            put(JSONObject().apply {
                                put("title", "Weather Portal 2")
                                put("url", "https://bdnews.example.org/dhaka-weather")
                                put("domain", "bdnews.example.org")
                                put("snippet", "Dhaka weather temperature reported at 29 degrees Celsius today with light breeze.")
                                put("contentExtracted", true)
                            })
                        })
                    }.toString()
                    Triple(200, json, 0L)
                }
                else -> Triple(200, """{"ok":true}""", 0L)
            }
        }

        // Bypass public-IP SSRF filter strictly for local loop test via WebPageContentExtractor.httpClientOverride
        WebPageContentExtractor.httpClientOverride = okhttp3.OkHttpClient()

        val l4Providers = listOf(object : WebSearchProvider {
            override val providerId = "TestDualSourceProvider"
            override val requiresApiKey = false
            override fun isConfigured() = true
            override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> {
                return Result.success(
                    listOf(
                        SearchResultItem(
                            title = "Bangladesh Gov Source",
                            url = "$baseUrl/gov-source?utm_source=test",
                            snippet = "Digital commerce guideline 2026.",
                            sourceDomain = "commerce.gov.bd",
                            providerName = providerId
                        ),
                        SearchResultItem(
                            title = "Independent Portal",
                            url = "$baseUrl/science-source?fbclid=xyz123",
                            snippet = "Independent policy review 2026.",
                            sourceDomain = "policyreview.org",
                            providerName = providerId
                        )
                    )
                )
            }
        })

        val stages = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        val l4Result = IntelligentKnowledgeRouter.query(
            query = "Bangladesh digital commerce guideline 2026 গভীর গবেষণা",
            context = app,
            modeOverride = RetrievalMode.DEEP_RESEARCH,
            knowledgeDaoOverride = kDao,
            serverClientOverride = KnowledgeServerClient({ "" }),
            searchProvidersOverride = l4Providers,
            onProgressiveStage = { stage, summary, isFinal ->
                stages.add(Triple(stage, summary, isFinal))
            }
        )

        assertEquals(CacheTier.L4, l4Result.cacheTierReached)
        assertEquals(ClaimStatus.VERIFIED, l4Result.verificationStatus)
        assertEquals(AiAgentRegistry.JARVIS_MASTER.id, l4Result.assignedController)
        assertEquals(AiAgentRegistry.INTELLIGENT_AGENT.id, l4Result.assignedSpecialist)
        assertTrue(stages.any { it.third && it.first == "L4_VERIFIED_RESULT" })

        // Wait briefly for AsyncKnowledgeStorageManager Room L2 write to settle
        val deadline = System.currentTimeMillis() + 3000L
        while (kDao.countCacheEntries() == 0 && System.currentTimeMillis() < deadline) {
            delay(40)
        }
        assertTrue("Async L2 persistence must save to Room", kDao.countCacheEntries() >= 1)
        assertEquals(KnowledgeStorageState.SAVED, AsyncKnowledgeStorageManager.getState(l4Result.cacheKey))

        // Step 2: Same query immediately hits L1 RAM cache with 0 bytes transferred
        val l1Result = IntelligentKnowledgeRouter.query(
            query = "Bangladesh digital commerce guideline 2026 গভীর গবেষণা",
            context = app,
            modeOverride = RetrievalMode.DEEP_RESEARCH,
            knowledgeDaoOverride = kDao,
            serverClientOverride = KnowledgeServerClient({ "" }),
            searchProvidersOverride = emptyList()
        )
        assertEquals(CacheTier.L1, l1Result.cacheTierReached)
        assertEquals(0L, l1Result.bytesTransferred)

        // Step 3: Clear L1 -> same query hits L2 Room cache directly (FAST_RETRIEVAL)
        KnowledgeL1Cache.clear()
        val l2Result = IntelligentKnowledgeRouter.query(
            query = "Bangladesh digital commerce guideline 2026 গভীর গবেষণা",
            context = app,
            modeOverride = RetrievalMode.FAST_RETRIEVAL,
            knowledgeDaoOverride = kDao,
            serverClientOverride = KnowledgeServerClient({ "" }),
            searchProvidersOverride = emptyList()
        )
        assertEquals(CacheTier.L2, l2Result.cacheTierReached)
        assertEquals(0L, l2Result.bytesTransferred)

        // Step 4: Fresh query hits L3 Firebase-free REST Server
        val l3Client = KnowledgeServerClient({ baseUrl }, { "test-secret" })
        val l3Result = IntelligentKnowledgeRouter.query(
            query = "ঢাকায় আজকের তাপমাত্রা কত",
            context = app,
            modeOverride = RetrievalMode.FAST_RETRIEVAL,
            knowledgeDaoOverride = kDao,
            serverClientOverride = l3Client,
            searchProvidersOverride = emptyList()
        )
        assertEquals(CacheTier.L3, l3Result.cacheTierReached)
        assertEquals(ClaimStatus.VERIFIED, l3Result.verificationStatus)
        assertTrue(l3Result.bytesTransferred > 0L)
    }

    @Test
    fun claimVerification_canonicalizesUrlsDetectsSyndicationAndNeverFakesMultiSource() = runBlocking {
        // 1. Canonicalization strips tracking params
        val u1 = ClaimVerificationEngine.canonicalizeUrl("https://www.Example.com/news/post/?utm_source=fb&fbclid=999&id=5")
        val u2 = ClaimVerificationEngine.canonicalizeUrl("https://example.com/news/post?id=5")
        assertEquals(u2, u1)

        // 2. Syndicated duplicate detection (different domains, identical text >= 40 chars -> NOT 2 independent domains)
        val syndicatedText = "Bangladesh Bank announced a unified digital payment framework starting next quarter."
        val syndicatedList = listOf(
            KnowledgeCitation("Daily A", "https://dailya.com/1", "", "dailya.com", syndicatedText, contentExtracted = true),
            KnowledgeCitation("Daily B", "https://dailyb.com/1", "", "dailyb.com", syndicatedText, contentExtracted = true)
        )
        val (synStatus, _) = ClaimVerificationEngine.verifyClaims("Bangladesh Bank digital payment", syndicatedList)
        assertNotEquals("Syndicated copies must NEVER yield VERIFIED", ClaimStatus.VERIFIED, synStatus)

        // 3. Contradiction detection across two sources
        val conflicting = listOf(
            KnowledgeCitation("Portal A", "https://portala.org/1", "", "portala.org", "Metro rail weekend service confirmed and launched at 20 percent discount.", contentExtracted = true),
            KnowledgeCitation("Portal B", "https://portalb.org/1", "", "portalb.org", "Metro rail weekend service denied and cancelled as false rumor.", contentExtracted = true)
        )
        val (conflictStatus, claims) = ClaimVerificationEngine.verifyClaims("Metro rail weekend service", conflicting)
        assertEquals(ClaimStatus.CONFLICTING, conflictStatus)
        assertTrue(claims.any { it.status == ClaimStatus.CONFLICTING })

        // 4. UniversalBrowserAgent single-source bug regression check: 1 domain MUST return SINGLE_SOURCE_ONLY (NOT VERIFIED_DEEP_RESEARCH)
        val singleUrl = startLocalHttpServer { _, _, _, _ ->
            Triple(200, "<html><head><title>Single Doc</title></head><body><p>Single domain research article about river transport.</p></body></html>", 0L)
        }
        WebPageContentExtractor.httpClientOverride = okhttp3.OkHttpClient()
        val singleProvider = object : WebSearchProvider {
            override val providerId = "SingleProvider"
            override val requiresApiKey = false
            override fun isConfigured() = true
            override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> {
                return Result.success(
                    listOf(
                        SearchResultItem(
                            title = "Single Doc",
                            url = "$singleUrl/single",
                            snippet = "River transport article.",
                            sourceDomain = "singlesource.org",
                            providerName = providerId
                        )
                    )
                )
            }
        }
        val singleDeep = UniversalBrowserAgent.deepResearch(
            query = "river transport",
            customProviders = listOf(singleProvider),
            maxPagesToExtract = 2
        )
        assertEquals("SINGLE_SOURCE_ONLY", singleDeep.statusCodeLabel)
        assertEquals("SINGLE_SOURCE_EXTRACTED", singleDeep.crossCheckStatus)
    }

    @Test
    fun cancellableResearch_propagatesToOkHttpAndLiveContextBridgeDoesNotDropPackets() = runBlocking {
        val slowUrl = startLocalHttpServer { _, _, _, _ ->
            Triple(200, """{"results":[]}""", 3000L)
        }
        val slowClient = KnowledgeServerClient({ slowUrl })
        val startNs = KnowledgePerformanceTracker.nanoNow()
        val job = async {
            IntelligentKnowledgeRouter.query(
                query = "অতি দীর্ঘ গবেষণা টেস্ট",
                modeOverride = RetrievalMode.DEEP_RESEARCH,
                knowledgeDaoOverride = db.knowledgeDao(),
                serverClientOverride = slowClient,
                searchProvidersOverride = emptyList()
            )
        }
        delay(120)
        assertTrue("Active job should be cancellable", IntelligentKnowledgeRouter.cancelActiveResearch())
        val res = job.await()
        val elapsedMs = KnowledgePerformanceTracker.elapsedMs(startNs)
        assertTrue("Cancelled query must return cancelled=true", res.cancelled)
        assertTrue("OkHttp call cancellation must finish well before 3000ms (took ${elapsedMs}ms)", elapsedMs < 1500L)

        // Verify LiveConversationContextBridge does NOT drop queued packets when no flushListener is attached
        LiveConversationContextBridge.resetForTest()
        assertTrue(LiveConversationContextBridge.enqueueContextUpdate("vision_1", "স্ক্রিনে Pay Now বাটন আছে"))
        assertEquals(1, LiveConversationContextBridge.peekPendingCount())
        LiveConversationContextBridge.onConnectionStateChanged(LiveSessionState.CONNECTED)
        // Listener is null -> packet MUST remain queued!
        assertEquals("Packets must NOT be dropped without listener", 1, LiveConversationContextBridge.peekPendingCount())

        // Attach listener -> packets flush cleanly
        val delivered = mutableListOf<String>()
        LiveConversationContextBridge.setFlushListener { pkts -> delivered.addAll(pkts.map { it.content }) }
        val flushed = LiveConversationContextBridge.flushIfReady()
        assertEquals(1, flushed.size)
        assertEquals(0, LiveConversationContextBridge.peekPendingCount())
        assertEquals("স্ক্রিনে Pay Now বাটন আছে", delivered.first())

        // Verify enriched context helper + buildSetupJson enforce JARVIS_MASTER Central Authority
        db.assistantDao().insertMemory(AiMemoryEntity(key = "user_name", content = "Nahid", category = "FACT", importance = 3))
        val executor = AssistantToolExecutor(db.assistantDao(), onNavigate = {}, context = app)
        val enriched = executor.getEnrichedContextForSession("Nahid")
        assertTrue(enriched.contains("Nahid"))
        val setup = GeminiLiveSessionManager.buildSetupJson(
            AiSettingsState(apiKey = "AIzaTest12345678901234567890", modelName = AiSettingsRepository.DEFAULT_MODEL, voiceName = "Aoede", systemInstruction = "Test", autoConnect = false),
            JSONArray(),
            enriched
        )
        val sysText = setup.getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(sysText.contains("[Central Master Authority]"))
        assertTrue(sysText.contains("Nahid"))
    }

    @Test
    fun performanceTrackerAndSourceRegistry_computeMonotonicSpansAndEnforceSsrfProtection() = runBlocking {
        KnowledgePerformanceTracker.resetForTest()
        val t0 = KnowledgePerformanceTracker.nanoNow()
        delay(25)
        val elapsed = KnowledgePerformanceTracker.elapsedMs(t0)
        assertTrue("Monotonic elapsedMs must be >= 15ms (was $elapsed)", elapsed >= 15L)

        KnowledgePerformanceTracker.recordSample(
            com.example.knowledge.LatencySpanSample("q1", RetrievalMode.FAST_RETRIEVAL, CacheTier.L1, 2, 3, 0, 2)
        )
        KnowledgePerformanceTracker.recordSample(
            com.example.knowledge.LatencySpanSample("q2", RetrievalMode.FAST_RETRIEVAL, CacheTier.L2, 10, 15, 0, 2)
        )
        KnowledgePerformanceTracker.recordSample(
            com.example.knowledge.LatencySpanSample("q3", RetrievalMode.DEEP_RESEARCH, CacheTier.L4, 120, 950, 4096, 4)
        )
        assertEquals(4096L, KnowledgePerformanceTracker.getTotalBytesTransferred())
        assertTrue(KnowledgePerformanceTracker.computePercentileMs(50) >= 3L)
        assertTrue(KnowledgePerformanceTracker.computePercentileMs(95) >= 15L)

        // SSRF protection checks
        assertFalse(KnowledgeSourceRegistry.isPublicSafeHttpUrl("http://127.0.0.1:8080/secret"))
        assertFalse(KnowledgeSourceRegistry.isPublicSafeHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(KnowledgeSourceRegistry.isPublicSafeHttpUrl("http://192.168.1.1/admin"))
        assertFalse(KnowledgeSourceRegistry.isPublicSafeHttpUrl("file:///etc/passwd"))
        assertTrue(KnowledgeSourceRegistry.isPublicSafeHttpUrl("https://bangladesh.gov.bd/site/page/123"))
        assertTrue(KnowledgeSourceRegistry.CURATED_SOURCES.isNotEmpty())
    }
}
