package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(
    tableName = "dev_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["priority"]),
        Index(value = ["updatedAt"])
    ]
)
@JsonClass(generateAdapter = true)
data class DevTaskEntity(
    @PrimaryKey
    val taskId: String,
    val goal: String,
    val category: String = "FEATURE", // BUG_FIX, SKILL_LEARN, CODE_PATCH, SELF_REPAIR, RESEARCH, WEB_APP
    val priority: String = "NORMAL", // URGENT, HIGH, NORMAL, LOW
    val phase: String = "IDLE", // IDLE, PLANNING, LEARNING, BUILDING, TESTING, SECURITY_CHECK, AWAITING_APPROVAL, PAUSED, COMPLETED, FAILED, CANCELLED
    val status: String = "QUEUED", // QUEUED, RUNNING, PAUSED, WAITING_APPROVAL, WAITING_PERMISSION, SUCCEEDED, FAILED, CANCELLED
    val autonomyLevel: String = "L2_SAFE_AUTONOMOUS", // L1_ADVISORY, L2_SAFE_AUTONOMOUS, L3_FEATURE_BUILDER, L4_SCHEDULED_BACKGROUND
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val completedStepsJson: String = "[]",
    val pendingStepsJson: String = "[]",
    val failedStepsJson: String = "[]",
    val currentFileOrTarget: String = "",
    val learningSource: String = "", // YOUTUBE, GITHUB, TEXT, VOICE, LIVE_DEMO, SCREENSHOT, DOCUMENT, GAP_DETECTOR
    val learningSourceUrl: String = "",
    val draftSkillId: String = "",
    val workingArtifactId: Long = 0L,
    val pauseReason: String = "",
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L
) {
    fun computeProgressPercent(): Int {
        if (totalSteps <= 0) return 0
        if (status == "SUCCEEDED") return 100
        return ((currentStepIndex.coerceIn(0, totalSteps)) * 100) / totalSteps
    }
}

@Entity(
    tableName = "dev_activity_logs",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["timestamp"])
    ]
)
@JsonClass(generateAdapter = true)
data class DevActivityLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: String = "",
    val phase: String = "IDLE",
    val eventType: String = "STATUS", // START, STEP_DONE, CHECKPOINT_PAUSED, RESUMED, CANCELLED, SECURITY_BLOCK, SKILL_SAVED, PATCH_GENERATED, ERROR
    val messageBn: String,
    val details: String = "",
    val severity: String = "INFO", // INFO, SUCCESS, WARNING, ERROR
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dev_patch_history",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
@JsonClass(generateAdapter = true)
data class DevPatchHistoryEntity(
    @PrimaryKey
    val patchId: String,
    val taskId: String = "",
    val targetFile: String,
    val language: String = "KOTLIN",
    val summaryBn: String,
    val patchContent: String,
    val previousSnapshot: String = "",
    val securityStatus: String = "PASS", // PASS, WARNING, BLOCKED
    val compileStatus: String = "NOT_COMPILED_REQUIRES_BUILD", // NOT_COMPILED_REQUIRES_BUILD, DECLARATIVE_READY, TEST_STUB_GENERATED
    val status: String = "DRAFT", // DRAFT, APPROVED, ROLLED_BACK, BLOCKED
    val artifactId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "capability_gaps",
    indices = [
        Index(value = ["status"]),
        Index(value = ["severity"])
    ]
)
@JsonClass(generateAdapter = true)
data class CapabilityGapEntity(
    @PrimaryKey
    val gapId: String,
    val titleBn: String,
    val category: String,
    val severity: String = "MEDIUM", // CRITICAL, HIGH, MEDIUM, LOW
    val sourceEvidence: String,
    val resolutionType: String = "SKILL_CREATE", // SKILL_CREATE, WORKFLOW_IMPROVE, NATIVE_CODE_PATCH, UNSUPPORTED_HARDWARE
    val status: String = "OPEN", // OPEN, QUEUED, RESOLVED, UNSUPPORTED, DISMISSED
    val linkedTaskId: String = "",
    val detectedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "skill_evidence",
    indices = [
        Index(value = ["needsReview"]),
        Index(value = ["healthScore"])
    ]
)
@JsonClass(generateAdapter = true)
data class SkillEvidenceEntity(
    @PrimaryKey
    val skillId: String,
    val sourceType: String,
    val sourceUrl: String = "",
    val learnedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val executionCount: Int = 0,
    val verifiedSuccessCount: Int = 0,
    val failureCount: Int = 0,
    val avgExecTimeMs: Long = 0L,
    val lastFailureReason: String = "",
    val needsReview: Boolean = false,
    val healthScore: Int = 100
)
