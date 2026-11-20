package com.example.knowledge

import android.content.Context
import com.example.ai.AiAgentRegistry
import com.example.ai.AiSettingsRepository
import com.example.ai.CanonicalIntent
import com.example.ai.UnifiedBrainLanguageEngine
import com.example.data.local.AssistantDatabase
import com.example.data.local.KnowledgeCacheEntity
import com.example.data.local.KnowledgeDao
import com.example.research.UniversalBrowserAgent
import com.example.research.WebSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * Fast-First Intelligent Knowledge Router operating under JARVIS_MASTER Brain
 * and delegated to INTELLIGENT_AGENT.
 * Executes L1 (RAM) -> L2 (Room) -> L3 (Optional REST Server) -> L4 (Bounded Parallel Web Research),
 * emits progressive updates for real-time voice, and records monotonic latency/byte telemetry.
 */
object IntelligentKnowledgeRouter {

    private val _liveStatus = MutableStateFlow(KnowledgeSystemLiveStatus())
    val liveStatus: StateFlow<KnowledgeSystemLiveStatus> = _liveStatus.asStateFlow()

    @Volatile
    private var activeResearchJob: Job? = null

    suspend fun query(
        query: String,
        context: Context? = null,
        modeOverride: RetrievalMode? = null,
        knowledgeDaoOverride: KnowledgeDao? = null,
        serverClientOverride: KnowledgeServerClient? = null,
        searchProvidersOverride: List<WebSearchProvider>? = null,
        onProgressiveStage: ((stageLabel: String, partialSummaryBn: String, isFinal: Boolean) -> Unit)? = null
    ): KnowledgeQueryResult = withContext(Dispatchers.IO) {
        val startNs = KnowledgePerformanceTracker.nanoNow()
        val cleanQuery = query.trim()
        val normalized = KnowledgeL1Cache.normalizeQuery(cleanQuery)
        val topicCategory = KnowledgeFreshnessPolicy.classifyTopic(cleanQuery)
        val mode = modeOverride ?: classifyRetrievalMode(cleanQuery)
        val cacheKey = KnowledgeL1Cache.buildCacheKey(normalized, mode)
        val dao = knowledgeDaoOverride ?: context?.let { AssistantDatabase.getDatabase(it).knowledgeDao() }
        val serverClient = serverClientOverride ?: context?.let {
            val repo = AiSettingsRepository(it)
            KnowledgeServerClient(
                baseUrlProvider = { repo.getKnowledgeServerUrl() },
                tokenProvider = { repo.getKnowledgeServerToken() }
            )
        }

        if (cleanQuery.isBlank()) {
            return@withContext KnowledgeQueryResult(
                query = cleanQuery,
                normalizedQuery = normalized,
                cacheKey = cacheKey,
                mode = mode,
                topicCategory = topicCategory,
                cacheTierReached = CacheTier.NONE,
                verificationStatus = ClaimStatus.UNVERIFIED,
                storageState = KnowledgeStorageState.NOT_REQUESTED,
                summaryBengali = "তথ্য অনুসন্ধানের জন্য একটি প্রশ্ন বা বিষয় বলুন।",
                claims = emptyList(),
                citations = emptyList(),
                firstResponseMs = KnowledgePerformanceTracker.elapsedMs(startNs),
                totalLatencyMs = KnowledgePerformanceTracker.elapsedMs(startNs),
                bytesTransferred = 0L
            )
        }

        val jobId = UUID.randomUUID().toString().take(8)
        val parentJob = coroutineContext[Job]
        val researchJob = Job(parentJob)
        activeResearchJob = researchJob
        _liveStatus.value = _liveStatus.value.copy(
            controllerAgentName = AiAgentRegistry.JARVIS_MASTER.name,
            specialistAgentName = AiAgentRegistry.INTELLIGENT_AGENT.name,
            activeMode = mode,
            lastQuery = cleanQuery,
            serverConnectionState = if (serverClient?.isConfigured() == true) "CONFIGURED" else "SERVER_UNCONFIGURED",
            isResearchRunning = mode == RetrievalMode.DEEP_RESEARCH,
            activeResearchJobId = jobId,
            statusNoteBn = "L1 মেমোরি ক্যাশ যাচাই হচ্ছে..."
        )

        var firstResponseMs = -1L

        try {
            withContext(researchJob) {
            // L1: RAM Cache (Skip L1 for explicit DEEP_RESEARCH force-refresh unless cached as DEEP_RESEARCH and fresh)
            val l1Hit = KnowledgeL1Cache.get(cacheKey)
            if (l1Hit != null) {
                val elapsed = KnowledgePerformanceTracker.elapsedMs(startNs).coerceAtLeast(1L)
                val updated = l1Hit.copy(
                    cacheTierReached = CacheTier.L1,
                    firstResponseMs = elapsed,
                    totalLatencyMs = elapsed,
                    bytesTransferred = 0L
                )
                onProgressiveStage?.invoke("L1_FAST_CACHE", updated.summaryBengali, true)
                KnowledgePerformanceTracker.recordSample(
                    LatencySpanSample(cleanQuery, mode, CacheTier.L1, elapsed, elapsed, 0L, updated.citations.size),
                    dao
                )
                publishFinalStatus(updated, dao, "L1 ক্যাশ থেকে দ্রুত উত্তর ($elapsed ms)")
                return@withContext updated
            }

            // L2: Local Room Cache
            val nowMs = System.currentTimeMillis()
            val l2Entity = dao?.getCacheByKey(cacheKey)
                ?: dao?.findCacheByNormalizedQuery(normalized)
            if (l2Entity != null && l2Entity.expiresAt > nowMs) {
                dao?.touchCacheAccess(l2Entity.cacheKey, nowMs)
                val l2Result = mapEntityToResult(l2Entity, mode, startNs)
                firstResponseMs = l2Result.firstResponseMs
                KnowledgeL1Cache.put(cacheKey, l2Result, l2Entity.expiresAt - nowMs, nowMs)

                if (mode != RetrievalMode.DEEP_RESEARCH) {
                    onProgressiveStage?.invoke("L2_LOCAL_CACHE", l2Result.summaryBengali, true)
                    KnowledgePerformanceTracker.recordSample(
                        LatencySpanSample(cleanQuery, mode, CacheTier.L2, firstResponseMs, l2Result.totalLatencyMs, 0L, l2Result.citations.size),
                        dao
                    )
                    publishFinalStatus(l2Result, dao, "L2 লোকাল ডাটাবেস থেকে উত্তর ($firstResponseMs ms)")
                    return@withContext l2Result
                } else {
                    // Progressive Stage 1 for Deep Research: deliver fast L2 summary while continuing live verification
                    onProgressiveStage?.invoke("L2_PROVISIONAL_SUMMARY", l2Result.summaryBengali, false)
                }
            }

            // L3: Optional Firebase-Free Knowledge API Server
            if (serverClient != null && serverClient.isConfigured()) {
                _liveStatus.value = _liveStatus.value.copy(statusNoteBn = "L3 নলেজ সার্ভার যাচাই হচ্ছে...")
                val l3Res = serverClient.search(cleanQuery, mode)
                if (l3Res.reachable && l3Res.hitFound) {
                    val (claimStatus, verifiedClaims) = ClaimVerificationEngine.verifyClaims(cleanQuery, l3Res.citations)
                    val elapsed = KnowledgePerformanceTracker.elapsedMs(startNs).coerceAtLeast(1L)
                    if (firstResponseMs < 0L) firstResponseMs = elapsed
                    val effectiveStatus = if (l3Res.verificationStatus == ClaimStatus.VERIFIED && claimStatus != ClaimStatus.CONFLICTING) {
                        ClaimStatus.VERIFIED
                    } else claimStatus

                    val l3Result = KnowledgeQueryResult(
                        query = cleanQuery,
                        normalizedQuery = normalized,
                        cacheKey = cacheKey,
                        mode = mode,
                        topicCategory = topicCategory,
                        cacheTierReached = CacheTier.L3,
                        verificationStatus = effectiveStatus,
                        storageState = KnowledgeStorageState.SAVED,
                        summaryBengali = l3Res.summaryBn.ifBlank {
                            buildSummaryFromCitations(cleanQuery, effectiveStatus, l3Res.citations)
                        },
                        claims = verifiedClaims,
                        citations = ClaimVerificationEngine.deduplicateCitations(l3Res.citations),
                        firstResponseMs = firstResponseMs,
                        totalLatencyMs = elapsed,
                        bytesTransferred = l3Res.bytesTransferred
                    )
                    KnowledgeL1Cache.put(cacheKey, l3Result, KnowledgeFreshnessPolicy.computeTtlMs(topicCategory))
                    onProgressiveStage?.invoke("L3_SERVER_HIT", l3Result.summaryBengali, true)
                    KnowledgePerformanceTracker.recordSample(
                        LatencySpanSample(cleanQuery, mode, CacheTier.L3, firstResponseMs, elapsed, l3Res.bytesTransferred, l3Result.citations.size),
                        dao
                    )
                    publishFinalStatus(l3Result, dao, "L3 নলেজ সার্ভার থেকে উত্তর ($elapsed ms)", serverState = "CONNECTED")
                    return@withContext l3Result
                } else if (serverClient.isConfigured()) {
                    _liveStatus.value = _liveStatus.value.copy(serverConnectionState = l3Res.errorReason.ifBlank { "UNREACHABLE" })
                }
            }

            // L4: Live Parallel Web Search & Claim Verification
            _liveStatus.value = _liveStatus.value.copy(
                isResearchRunning = true,
                statusNoteBn = if (mode == RetrievalMode.DEEP_RESEARCH) "L4 প্যারালাল ডিপ রিসার্চ চলছে..." else "L4 দ্রুত ওয়েব অনুসন্ধান চলছে..."
            )

            val resultFromL4 = if (mode == RetrievalMode.DEEP_RESEARCH) {
                val deep = UniversalBrowserAgent.deepResearch(
                    query = cleanQuery,
                    context = context,
                    customProviders = searchProvidersOverride,
                    maxPagesToExtract = 4
                )
                val citations = deep.findings.mapIndexed { i, f ->
                    KnowledgeCitation(
                        title = f.title,
                        url = f.url,
                        canonicalUrl = ClaimVerificationEngine.canonicalizeUrl(f.url),
                        domain = f.sourceDomain,
                        snippet = deep.results.getOrNull(i)?.snippet ?: f.wrappedExcerpt.take(360),
                        contentExtracted = f.contentExtracted,
                        publishedDate = f.publishedDate
                    )
                }
                val deduped = ClaimVerificationEngine.deduplicateCitations(citations)
                val (status, claims) = ClaimVerificationEngine.verifyClaims(cleanQuery, deduped)
                val totalMs = KnowledgePerformanceTracker.elapsedMs(startNs).coerceAtLeast(1L)
                if (firstResponseMs < 0L) firstResponseMs = totalMs
                val bytes = deep.findings.sumOf { it.wrappedExcerpt.toByteArray(Charsets.UTF_8).size.toLong() }

                KnowledgeQueryResult(
                    query = cleanQuery,
                    normalizedQuery = normalized,
                    cacheKey = cacheKey,
                    mode = mode,
                    topicCategory = topicCategory,
                    cacheTierReached = CacheTier.L4,
                    verificationStatus = status,
                    storageState = if (deep.success) KnowledgeStorageState.PENDING else KnowledgeStorageState.NOT_REQUESTED,
                    summaryBengali = deep.summaryBengali,
                    claims = claims,
                    citations = deduped,
                    firstResponseMs = firstResponseMs,
                    totalLatencyMs = totalMs,
                    bytesTransferred = bytes,
                    offlineFallback = deep.statusCodeLabel == "OFFLINE_LIMITED"
                )
            } else {
                val fastSearch = UniversalBrowserAgent.searchAndVerify(
                    query = cleanQuery,
                    context = context,
                    customProviders = searchProvidersOverride,
                    maxResults = 4
                )
                val citations = fastSearch.results.map { r ->
                    KnowledgeCitation(
                        title = r.title,
                        url = r.url,
                        canonicalUrl = ClaimVerificationEngine.canonicalizeUrl(r.url),
                        domain = r.sourceDomain,
                        snippet = r.snippet,
                        contentExtracted = r.contentExtracted,
                        publishedDate = r.publishedDate
                    )
                }
                val deduped = ClaimVerificationEngine.deduplicateCitations(citations)
                val (status, claims) = ClaimVerificationEngine.verifyClaims(cleanQuery, deduped)
                val totalMs = KnowledgePerformanceTracker.elapsedMs(startNs).coerceAtLeast(1L)
                if (firstResponseMs < 0L) firstResponseMs = totalMs
                val bytes = fastSearch.results.sumOf { (it.title + it.snippet).toByteArray(Charsets.UTF_8).size.toLong() }

                KnowledgeQueryResult(
                    query = cleanQuery,
                    normalizedQuery = normalized,
                    cacheKey = cacheKey,
                    mode = mode,
                    topicCategory = topicCategory,
                    cacheTierReached = CacheTier.L4,
                    verificationStatus = status,
                    storageState = if (fastSearch.success) KnowledgeStorageState.PENDING else KnowledgeStorageState.NOT_REQUESTED,
                    summaryBengali = fastSearch.summaryBengali,
                    claims = claims,
                    citations = deduped,
                    firstResponseMs = firstResponseMs,
                    totalLatencyMs = totalMs,
                    bytesTransferred = bytes,
                    offlineFallback = fastSearch.statusCodeLabel == "OFFLINE_LIMITED"
                )
            }

            onProgressiveStage?.invoke("L4_VERIFIED_RESULT", resultFromL4.summaryBengali, true)

            if (resultFromL4.citations.isNotEmpty() && !resultFromL4.offlineFallback) {
                KnowledgeL1Cache.put(cacheKey, resultFromL4, KnowledgeFreshnessPolicy.computeTtlMs(topicCategory))
                AsyncKnowledgeStorageManager.enqueuePersist(
                    result = resultFromL4,
                    dao = dao,
                    serverClient = serverClient,
                    context = context
                ) { newStorageState ->
                    _liveStatus.value = _liveStatus.value.copy(storageState = newStorageState)
                }
            }

            KnowledgePerformanceTracker.recordSample(
                LatencySpanSample(
                    query = cleanQuery,
                    mode = mode,
                    cacheTierReached = CacheTier.L4,
                    firstResponseMs = resultFromL4.firstResponseMs,
                    totalLatencyMs = resultFromL4.totalLatencyMs,
                    bytesTransferred = resultFromL4.bytesTransferred,
                    sourceCount = resultFromL4.citations.size
                ),
                dao
            )
            publishFinalStatus(resultFromL4, dao, "L4 রিসার্চ সম্পন্ন (${resultFromL4.verificationStatus.name}, ${resultFromL4.totalLatencyMs} ms)")
            resultFromL4
            }
        } catch (ce: CancellationException) {
            if (parentJob != null && !parentJob.isActive) throw ce
            val totalMs = KnowledgePerformanceTracker.elapsedMs(startNs)
            _liveStatus.value = _liveStatus.value.copy(
                isResearchRunning = false,
                activeResearchJobId = "",
                statusNoteBn = "ব্যবহারকারীর নির্দেশে গবেষণা বাতিল হয়েছে ($totalMs ms)।"
            )
            KnowledgeQueryResult(
                query = cleanQuery,
                normalizedQuery = normalized,
                cacheKey = cacheKey,
                mode = mode,
                topicCategory = topicCategory,
                cacheTierReached = CacheTier.NONE,
                verificationStatus = ClaimStatus.UNVERIFIED,
                storageState = KnowledgeStorageState.NOT_REQUESTED,
                summaryBengali = "গবেষণাটি বাতিল করা হয়েছে।",
                claims = emptyList(),
                citations = emptyList(),
                firstResponseMs = totalMs,
                totalLatencyMs = totalMs,
                bytesTransferred = 0L,
                cancelled = true
            )
        } finally {
            researchJob.cancel()
            activeResearchJob = null
            _liveStatus.value = _liveStatus.value.copy(isResearchRunning = false)
        }
    }

    fun cancelActiveResearch(): Boolean {
        val job = activeResearchJob ?: return false
        job.cancel(CancellationException("USER_CANCELLED_RESEARCH"))
        _liveStatus.value = _liveStatus.value.copy(
            isResearchRunning = false,
            activeResearchJobId = "",
            statusNoteBn = "সক্রিয় গবেষণা বাতিল করা হয়েছে।"
        )
        return true
    }

    fun classifyRetrievalMode(query: String): RetrievalMode {
        val structured = UnifiedBrainLanguageEngine.analyze(query, updateReferenceContext = false)
        return when (structured.primaryIntent) {
            CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH -> RetrievalMode.DEEP_RESEARCH
            CanonicalIntent.KNOWLEDGE_FAST -> RetrievalMode.FAST_RETRIEVAL
            CanonicalIntent.CONVERSATIONAL -> RetrievalMode.CONVERSATIONAL
            else -> RetrievalMode.ACTION_ONLY
        }
    }

    private fun mapEntityToResult(
        entity: KnowledgeCacheEntity,
        mode: RetrievalMode,
        startNs: Long
    ): KnowledgeQueryResult {
        val elapsed = KnowledgePerformanceTracker.elapsedMs(startNs).coerceAtLeast(1L)
        val status = try { ClaimStatus.valueOf(entity.verificationStatus) } catch (_: Exception) { ClaimStatus.UNVERIFIED }
        val storage = try { KnowledgeStorageState.valueOf(entity.storageState) } catch (_: Exception) { KnowledgeStorageState.SAVED }
        val category = try { TopicCategory.valueOf(entity.topicCategory) } catch (_: Exception) { TopicCategory.GENERAL }

        val citations = mutableListOf<KnowledgeCitation>()
        try {
            val arr = JSONArray(entity.citationsJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                citations.add(
                    KnowledgeCitation(
                        title = o.optString("title", ""),
                        url = o.optString("url", ""),
                        canonicalUrl = o.optString("canonicalUrl", ""),
                        domain = o.optString("domain", ""),
                        snippet = o.optString("snippet", ""),
                        contentExtracted = o.optBoolean("contentExtracted", false),
                        contentHash = o.optString("contentHash", ""),
                        publishedDate = o.optString("publishedDate", "")
                    )
                )
            }
        } catch (_: Exception) {
        }

        val claims = mutableListOf<VerifiedClaim>()
        try {
            val arr = JSONArray(entity.claimsJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val domArr = o.optJSONArray("supportingDomains") ?: JSONArray()
                val doms = (0 until domArr.length()).mapNotNull { domArr.optString(it).takeIf { s -> s.isNotBlank() } }
                val cStat = try { ClaimStatus.valueOf(o.optString("status", "UNVERIFIED")) } catch (_: Exception) { ClaimStatus.UNVERIFIED }
                claims.add(
                    VerifiedClaim(
                        claimText = o.optString("claimText", ""),
                        status = cStat,
                        independentDomainCount = o.optInt("independentDomainCount", doms.size),
                        supportingDomains = doms,
                        conflictingNotes = o.optString("conflictingNotes", ""),
                        confidenceScore = o.optDouble("confidenceScore", 0.5).toFloat()
                    )
                )
            }
        } catch (_: Exception) {
        }

        return KnowledgeQueryResult(
            query = entity.normalizedQuery,
            normalizedQuery = entity.normalizedQuery,
            cacheKey = entity.cacheKey,
            mode = mode,
            topicCategory = category,
            cacheTierReached = CacheTier.L2,
            verificationStatus = status,
            storageState = storage,
            summaryBengali = entity.answerSummaryBn,
            claims = claims,
            citations = citations,
            firstResponseMs = elapsed,
            totalLatencyMs = elapsed,
            bytesTransferred = 0L
        )
    }

    private fun buildSummaryFromCitations(
        query: String,
        status: ClaimStatus,
        citations: List<KnowledgeCitation>
    ): String {
        val top = citations.take(3).joinToString("\n") { c ->
            "• ${c.title} (${c.domain})\n  সূত্র: ${c.canonicalUrl.ifBlank { c.url }}\n  ${c.snippet.take(180)}"
        }
        return "‘$query’ বিষয়ে তথ্য (${status.name}):\n$top"
    }

    private suspend fun publishFinalStatus(
        result: KnowledgeQueryResult,
        dao: KnowledgeDao?,
        noteBn: String,
        serverState: String? = null
    ) {
        val l2Count = try { dao?.countCacheEntries() ?: 0 } catch (_: Exception) { 0 }
        _liveStatus.value = _liveStatus.value.copy(
            activeMode = result.mode,
            lastQuery = result.query,
            cacheTierReached = result.cacheTierReached,
            evidenceStatus = result.verificationStatus,
            storageState = result.storageState,
            serverConnectionState = serverState ?: _liveStatus.value.serverConnectionState,
            l1HitCount = KnowledgeL1Cache.hitCount(),
            l2EntryCount = l2Count,
            lastLatencyMs = result.totalLatencyMs,
            lastBytesTransferred = result.bytesTransferred,
            isResearchRunning = false,
            activeResearchJobId = "",
            statusNoteBn = noteBn
        )
    }

    fun resetForTest() {
        activeResearchJob?.cancel()
        activeResearchJob = null
        KnowledgeL1Cache.clear()
        KnowledgeSourceRegistry.resetRateLimiterForTest()
        AsyncKnowledgeStorageManager.resetForTest()
        KnowledgePerformanceTracker.resetForTest()
        _liveStatus.value = KnowledgeSystemLiveStatus()
    }
}
