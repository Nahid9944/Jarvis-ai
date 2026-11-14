package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "character_profiles")
data class CharacterProfileEntity(
    @PrimaryKey val modeId: String,
    val profileVersion: Int = 1,
    val speakingStyleBn: String,
    val toneDescriptionBn: String,
    val emotionBehaviorBn: String,
    val responseLength: String,
    val humorLevel: Int,
    val formalityLevel: Int,
    val affectionLevel: Int,
    val memoryPreference: String,
    val toolAccessPolicy: String,
    val customInstructionsJson: String = "[]",
    val isActive: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "character_profile_versions")
data class CharacterProfileVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val modeId: String,
    val version: Int,
    val feedbackSummary: String,
    val customRuleText: String,
    val snapshotJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "emotion_history")
data class EmotionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val emotion: String,
    val confidence: Float,
    val source: String,
    val isUncertain: Boolean = false,
    val contextSummaryBn: String,
    val userCorrected: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "action_approval_audits")
data class ActionApprovalAuditEntity(
    @PrimaryKey val approvalId: String,
    val executionId: String,
    val actionType: String,
    val toolName: String,
    val targetApp: String,
    val recipientName: String,
    val paramsHash: String,
    val messageContentHash: String,
    val approvalStatus: String,
    val dispatchStatus: String,
    val source: String,
    val createdAt: Long,
    val confirmationTime: Long,
    val expiryTime: Long,
    val completedAt: Long
)

@Entity(tableName = "trusted_low_risk_rules")
data class TrustedLowRiskRuleEntity(
    @PrimaryKey val ruleId: String,
    val toolName: String,
    val scopeDescriptionBn: String,
    val grantedAt: Long,
    val expiresAt: Long,
    val isEnabled: Boolean = true
)
