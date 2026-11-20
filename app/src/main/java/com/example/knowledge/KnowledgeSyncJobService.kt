package com.example.knowledge

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.ai.AiSettingsRepository
import com.example.data.local.AssistantDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Battery-aware JobScheduler service that retries RETRY_SCHEDULED / PENDING knowledge entries
 * to the configured Firebase-free L3 Knowledge Server when battery is not low and network is available.
 */
class KnowledgeSyncJobService : JobService() {

    private var syncJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AssistantDatabase.getDatabase(applicationContext)
                val dao = db.knowledgeDao()
                val settings = AiSettingsRepository(applicationContext)
                val client = KnowledgeServerClient(
                    baseUrlProvider = { settings.getKnowledgeServerUrl() },
                    tokenProvider = { settings.getKnowledgeServerToken() }
                )
                if (client.isConfigured()) {
                    val pending = dao.getPendingSyncEntries(limit = 15)
                    for (entry in pending) {
                        val cat = try {
                            TopicCategory.valueOf(entry.topicCategory)
                        } catch (_: Exception) {
                            TopicCategory.GENERAL
                        }
                        val status = try {
                            ClaimStatus.valueOf(entry.verificationStatus)
                        } catch (_: Exception) {
                            ClaimStatus.UNVERIFIED
                        }
                        val res = client.ingest(
                            idempotencyKey = "${entry.cacheKey}_${entry.contentHash}",
                            query = entry.normalizedQuery,
                            topicCategory = cat,
                            summaryBn = entry.answerSummaryBn,
                            verificationStatus = status,
                            citations = emptyList()
                        )
                        if (res.success) {
                            dao.updateStorageState(entry.cacheKey, KnowledgeStorageState.SAVED.name)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        syncJob?.cancel()
        return true
    }
}
