package com.example.knowledge

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.example.data.local.KnowledgeCacheEntity
import com.example.data.local.KnowledgeDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asynchronous background storage manager that writes verified knowledge results into Room L2 cache
 * and optionally syncs to the configured Firebase-free L3 Knowledge Server without blocking voice latency.
 */
object AsyncKnowledgeStorageManager {

    private val storageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _storageStates = MutableStateFlow<Map<String, KnowledgeStorageState>>(emptyMap())
    val storageStates: StateFlow<Map<String, KnowledgeStorageState>> = _storageStates.asStateFlow()

    fun enqueuePersist(
        result: KnowledgeQueryResult,
        dao: KnowledgeDao?,
        serverClient: KnowledgeServerClient? = null,
        context: Context? = null,
        onStateChanged: ((KnowledgeStorageState) -> Unit)? = null
    ) {
        if (dao == null) {
            updateState(result.cacheKey, KnowledgeStorageState.FAILED, onStateChanged)
            return
        }
        updateState(result.cacheKey, KnowledgeStorageState.PENDING, onStateChanged)

        storageScope.launch {
            persistInternal(result, dao, serverClient, context, onStateChanged)
        }
    }

    internal suspend fun persistInternal(
        result: KnowledgeQueryResult,
        dao: KnowledgeDao,
        serverClient: KnowledgeServerClient? = null,
        context: Context? = null,
        onStateChanged: ((KnowledgeStorageState) -> Unit)? = null
    ): KnowledgeStorageState {
        return try {
            val claimsArr = JSONArray().apply {
                result.claims.forEach { c ->
                    put(JSONObject().apply {
                        put("claimText", c.claimText)
                        put("status", c.status.name)
                        put("independentDomainCount", c.independentDomainCount)
                        put("supportingDomains", JSONArray(c.supportingDomains))
                        put("conflictingNotes", c.conflictingNotes)
                        put("confidenceScore", c.confidenceScore.toDouble())
                    })
                }
            }
            val citationsArr = JSONArray().apply {
                result.citations.forEach { c ->
                    put(JSONObject().apply {
                        put("title", c.title)
                        put("url", c.url)
                        put("canonicalUrl", c.canonicalUrl)
                        put("domain", c.domain)
                        put("snippet", c.snippet.take(500))
                        put("contentExtracted", c.contentExtracted)
                        put("contentHash", c.contentHash)
                        put("publishedDate", c.publishedDate)
                    })
                }
            }

            val now = System.currentTimeMillis()
            val ttl = KnowledgeFreshnessPolicy.computeTtlMs(result.topicCategory)
            val contentHash = KnowledgeL1Cache.sha256Hex("${result.normalizedQuery}::${result.summaryBengali}").take(24)

            var finalState = KnowledgeStorageState.SAVED
            if (serverClient != null && serverClient.isConfigured()) {
                val ingestRes = serverClient.ingest(
                    idempotencyKey = "${result.cacheKey}_$contentHash",
                    query = result.query,
                    topicCategory = result.topicCategory,
                    summaryBn = result.summaryBengali,
                    verificationStatus = result.verificationStatus,
                    citations = result.citations
                )
                if (!ingestRes.success) {
                    finalState = KnowledgeStorageState.RETRY_SCHEDULED
                    scheduleBatteryAwareSync(context)
                }
            }

            dao.upsertCache(
                KnowledgeCacheEntity(
                    cacheKey = result.cacheKey,
                    normalizedQuery = result.normalizedQuery,
                    topicCategory = result.topicCategory.name,
                    answerSummaryBn = result.summaryBengali,
                    claimsJson = claimsArr.toString(),
                    citationsJson = citationsArr.toString(),
                    verificationStatus = result.verificationStatus.name,
                    storageState = finalState.name,
                    sourceTier = result.cacheTierReached.name,
                    contentHash = contentHash,
                    createdAt = now,
                    expiresAt = now + ttl,
                    lastAccessedAt = now
                )
            )
            updateState(result.cacheKey, finalState, onStateChanged)
            finalState
        } catch (_: Exception) {
            updateState(result.cacheKey, KnowledgeStorageState.FAILED, onStateChanged)
            KnowledgeStorageState.FAILED
        }
    }

    /**
     * Schedules battery- & network-constrained background knowledge sync using Android JobScheduler.
     */
    fun scheduleBatteryAwareSync(context: Context?) {
        if (context == null) return
        try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, KnowledgeSyncJobService::class.java)
            val builder = JobInfo.Builder(8042, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setRequiresStorageNotLow(true)
            }
            scheduler.schedule(builder.build())
        } catch (_: Exception) {
        }
    }

    private fun updateState(
        cacheKey: String,
        state: KnowledgeStorageState,
        onStateChanged: ((KnowledgeStorageState) -> Unit)?
    ) {
        val mutable = _storageStates.value.toMutableMap()
        mutable[cacheKey] = state
        _storageStates.value = mutable
        onStateChanged?.invoke(state)
    }

    fun getState(cacheKey: String): KnowledgeStorageState {
        return _storageStates.value[cacheKey] ?: KnowledgeStorageState.NOT_REQUESTED
    }

    fun resetForTest() {
        _storageStates.value = emptyMap()
    }
}
