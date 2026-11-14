package com.example.automation

import android.content.Context
import com.example.automation.apps.DemoPlaybooks
import com.example.automation.apps.FacebookPlaybooks
import com.example.automation.apps.WhatsAppPlaybooks
import com.example.automation.apps.YouTubePlaybooks


data class PlaybookHealth(
    val playbookId: String,
    val totalRuns: Int,
    val successRuns: Int,
    val failedRuns: Int,
    val successRatePercent: Int,
    val lastFailedStep: Int? = null,
    val lastError: String? = null,
    val isHealthy: Boolean = true
)

data class PlaybookRunLog(
    val id: String,
    val playbookId: String,
    val timestamp: Long,
    val paramsJson: String,
    val success: Boolean,
    val code: String,
    val messageBn: String,
    val durationMs: Long
)

object PlaybookStore {

    private val playbooks = mutableMapOf<String, Playbook>()
    private val runLogs = mutableListOf<PlaybookRunLog>()

    init {
        // Register default playbooks
        WhatsAppPlaybooks.getAll().forEach { registerPlaybook(it) }
        YouTubePlaybooks.getAll().forEach { registerPlaybook(it) }
        FacebookPlaybooks.getAll().forEach { registerPlaybook(it) }
        DemoPlaybooks.getAll().forEach { registerPlaybook(it) }
    }

    fun registerPlaybook(playbook: Playbook) {
        playbooks[playbook.id] = playbook
    }

    fun get(id: String): Playbook? = playbooks[id]

    fun getAll(): List<Playbook> = playbooks.values.toList()

    fun getByApp(pkg: String): List<Playbook> = playbooks.values.filter { it.appPackage == pkg }

    @Synchronized
    fun logRun(
        playbookId: String,
        params: Map<String, String>,
        result: PlaybookRunResult
    ) {
        val log = PlaybookRunLog(
            id = java.util.UUID.randomUUID().toString(),
            playbookId = playbookId,
            timestamp = System.currentTimeMillis(),
            paramsJson = params.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" },
            success = result.success,
            code = result.code,
            messageBn = result.messageBn,
            durationMs = result.executionTimeMs
        )
        runLogs.add(log)
        // Keep max 200 logs in memory
        if (runLogs.size > 200) {
            runLogs.removeAt(0)
        }
    }

    @Synchronized
    fun getRunLogs(playbookId: String? = null, limit: Int = 20): List<PlaybookRunLog> {
        val list = if (playbookId != null) runLogs.filter { it.playbookId == playbookId } else runLogs
        return list.takeLast(limit).reversed()
    }

    @Synchronized
    fun getHealth(playbookId: String): PlaybookHealth {
        val runs = runLogs.filter { it.playbookId == playbookId }
        val total = runs.size
        if (total == 0) {
            return PlaybookHealth(playbookId, 0, 0, 0, 100, isHealthy = true)
        }
        val successes = runs.count { it.success }
        val failures = total - successes
        val rate = (successes * 100) / total
        val lastFail = runs.lastOrNull { !it.success }

        return PlaybookHealth(
            playbookId = playbookId,
            totalRuns = total,
            successRuns = successes,
            failedRuns = failures,
            successRatePercent = rate,
            lastError = lastFail?.messageBn,
            isHealthy = rate >= 70
        )
    }

    fun getAllHealth(): List<PlaybookHealth> {
        return playbooks.keys.map { getHealth(it) }
    }
}
