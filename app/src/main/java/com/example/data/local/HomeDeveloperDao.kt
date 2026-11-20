package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeDeveloperDao {

    // Tasks & Checkpoints
    @Query("SELECT * FROM dev_tasks ORDER BY updatedAt DESC")
    fun getAllDevTasks(): Flow<List<DevTaskEntity>>

    @Query("SELECT * FROM dev_tasks ORDER BY updatedAt DESC")
    suspend fun getAllDevTasksSync(): List<DevTaskEntity>

    @Query("SELECT * FROM dev_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getDevTaskByIdSync(taskId: String): DevTaskEntity?

    @Query("SELECT * FROM dev_tasks WHERE status IN ('RUNNING', 'PAUSED', 'QUEUED', 'WAITING_APPROVAL', 'WAITING_PERMISSION') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestResumableOrActiveTaskSync(): DevTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevTask(task: DevTaskEntity)

    @Query("DELETE FROM dev_tasks WHERE taskId = :taskId")
    suspend fun deleteDevTask(taskId: String)

    // Activity Logs
    @Query("SELECT * FROM dev_activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentActivityLogs(limit: Int = 100): Flow<List<DevActivityLogEntity>>

    @Query("SELECT * FROM dev_activity_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentActivityLogsSync(limit: Int = 100): List<DevActivityLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityLog(log: DevActivityLogEntity): Long

    @Query("DELETE FROM dev_activity_logs")
    suspend fun clearActivityLogs()

    // Code Patch & Version History
    @Query("SELECT * FROM dev_patch_history ORDER BY updatedAt DESC")
    fun getAllPatches(): Flow<List<DevPatchHistoryEntity>>

    @Query("SELECT * FROM dev_patch_history ORDER BY updatedAt DESC")
    suspend fun getAllPatchesSync(): List<DevPatchHistoryEntity>

    @Query("SELECT * FROM dev_patch_history WHERE patchId = :patchId LIMIT 1")
    suspend fun getPatchByIdSync(patchId: String): DevPatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPatch(patch: DevPatchHistoryEntity)

    // Capability Gaps
    @Query("SELECT * FROM capability_gaps ORDER BY updatedAt DESC")
    fun getAllCapabilityGaps(): Flow<List<CapabilityGapEntity>>

    @Query("SELECT * FROM capability_gaps ORDER BY updatedAt DESC")
    suspend fun getAllCapabilityGapsSync(): List<CapabilityGapEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapabilityGap(gap: CapabilityGapEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapabilityGaps(gaps: List<CapabilityGapEntity>)

    // Skill Evidence & Health Metrics
    @Query("SELECT * FROM skill_evidence ORDER BY learnedAt DESC")
    fun getAllSkillEvidence(): Flow<List<SkillEvidenceEntity>>

    @Query("SELECT * FROM skill_evidence ORDER BY learnedAt DESC")
    suspend fun getAllSkillEvidenceSync(): List<SkillEvidenceEntity>

    @Query("SELECT * FROM skill_evidence WHERE skillId = :skillId LIMIT 1")
    suspend fun getSkillEvidenceByIdSync(skillId: String): SkillEvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkillEvidence(evidence: SkillEvidenceEntity)
}
