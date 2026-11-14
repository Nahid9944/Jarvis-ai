package com.example.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ExecutionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    UNVERIFIED,
    FAILED,
    PERMISSION_REQUIRED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_REJECTED,
    RATE_LIMITED,
    IDEMPOTENT_SKIPPED
}

data class ToolAuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val category: ToolCategory,
    val paramsJson: String,
    val caller: String = "voice", // "voice", "text", "automation", "ui"
    val status: ExecutionStatus,
    val durationMs: Long = 0,
    val resultPreview: String? = null,
    val errorMessage: String? = null,
    val permissionId: String? = null,
    val confirmationRequired: Boolean = false
)

object ToolAuditLogger {
    private const val MAX_LOGS = 500
    private val records = mutableListOf<ToolAuditRecord>()
    private val _auditFlow = MutableStateFlow<List<ToolAuditRecord>>(emptyList())
    val auditFlow: StateFlow<List<ToolAuditRecord>> = _auditFlow.asStateFlow()

    @Synchronized
    fun log(record: ToolAuditRecord) {
        records.add(record)
        if (records.size > MAX_LOGS) {
            records.removeAt(0)
        }
        _auditFlow.value = records.takeLast(100).reversed()
    }

    @Synchronized
    fun getRecent(limit: Int = 50): List<ToolAuditRecord> = records.takeLast(limit).reversed()

    @Synchronized
    fun getByTool(toolName: String): List<ToolAuditRecord> = records.filter { it.toolName == toolName }

    @Synchronized
    fun getFailures(): List<ToolAuditRecord> = records.filter { it.status == ExecutionStatus.FAILED }

    @Synchronized
    fun getCount(): Int = records.size

    @Synchronized
    fun clear() {
        records.clear()
        _auditFlow.value = emptyList()
    }
}
