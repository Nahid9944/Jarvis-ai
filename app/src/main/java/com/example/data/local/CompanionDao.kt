package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanionDao {

    // ── Character Profiles ──────────────────────────────────────────────────
    @Query("SELECT * FROM character_profiles ORDER BY updatedAt DESC")
    fun observeCharacterProfiles(): Flow<List<CharacterProfileEntity>>

    @Query("SELECT * FROM character_profiles")
    suspend fun getAllCharacterProfilesSync(): List<CharacterProfileEntity>

    @Query("SELECT * FROM character_profiles WHERE modeId = :modeId LIMIT 1")
    suspend fun getCharacterProfileSync(modeId: String): CharacterProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterProfile(profile: CharacterProfileEntity)

    @Query("UPDATE character_profiles SET isActive = CASE WHEN modeId = :activeModeId THEN 1 ELSE 0 END")
    suspend fun markActiveCharacter(activeModeId: String)

    // ── Character Profile Versions ──────────────────────────────────────────
    @Query("SELECT * FROM character_profile_versions WHERE modeId = :modeId ORDER BY version DESC")
    fun observeCharacterVersions(modeId: String): Flow<List<CharacterProfileVersionEntity>>

    @Query("SELECT * FROM character_profile_versions ORDER BY createdAt DESC LIMIT 50")
    fun observeAllCharacterVersions(): Flow<List<CharacterProfileVersionEntity>>

    @Query("SELECT * FROM character_profile_versions WHERE modeId = :modeId ORDER BY version DESC")
    suspend fun getCharacterVersionsSync(modeId: String): List<CharacterProfileVersionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterVersion(version: CharacterProfileVersionEntity): Long

    // ── Emotion History ─────────────────────────────────────────────────────
    @Query("SELECT * FROM emotion_history ORDER BY timestamp DESC LIMIT 100")
    fun observeEmotionHistory(): Flow<List<EmotionHistoryEntity>>

    @Query("SELECT * FROM emotion_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEmotionHistorySync(limit: Int = 50): List<EmotionHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmotionHistory(entry: EmotionHistoryEntity): Long

    @Query("DELETE FROM emotion_history")
    suspend fun clearEmotionHistory()

    // ── Action Approval Audits ──────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApprovalAudit(audit: ActionApprovalAuditEntity)

    @Query("SELECT * FROM action_approval_audits ORDER BY createdAt DESC LIMIT 100")
    fun getRecentApprovalAudits(): Flow<List<ActionApprovalAuditEntity>>

    @Query("SELECT * FROM action_approval_audits ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentApprovalAuditsSync(limit: Int = 50): List<ActionApprovalAuditEntity>

    @Query("DELETE FROM action_approval_audits")
    suspend fun clearApprovalAudits()

    // ── Trusted Low-Risk Rules ──────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrustedRule(rule: TrustedLowRiskRuleEntity)

    @Query("SELECT * FROM trusted_low_risk_rules WHERE isEnabled = 1")
    suspend fun getAllTrustedRulesSync(): List<TrustedLowRiskRuleEntity>

    @Query("SELECT * FROM trusted_low_risk_rules ORDER BY grantedAt DESC")
    fun observeTrustedRules(): Flow<List<TrustedLowRiskRuleEntity>>

    @Query("DELETE FROM trusted_low_risk_rules WHERE toolName = :toolName")
    suspend fun deleteTrustedRulesForTool(toolName: String)

    @Query("DELETE FROM trusted_low_risk_rules")
    suspend fun clearAllTrustedRules()
}
