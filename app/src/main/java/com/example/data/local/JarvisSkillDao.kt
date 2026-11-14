package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface JarvisSkillDao {

    // =========================================================================
    // SKILLS CRUD
    // =========================================================================

    @Query("SELECT * FROM jarvis_skills ORDER BY updatedAt DESC")
    fun getAllSkills(): Flow<List<JarvisSkillEntity>>

    @Query("SELECT * FROM jarvis_skills WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    fun getAllActiveSkills(): Flow<List<JarvisSkillEntity>>

    @Query("SELECT * FROM jarvis_skills ORDER BY updatedAt DESC")
    suspend fun getAllSkillsSync(): List<JarvisSkillEntity>

    @Query("SELECT * FROM jarvis_skills WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getAllActiveSkillsSync(): List<JarvisSkillEntity>

    @Query("SELECT * FROM jarvis_skills WHERE skillId = :skillId LIMIT 1")
    fun getSkillById(skillId: String): Flow<JarvisSkillEntity?>

    @Query("SELECT * FROM jarvis_skills WHERE skillId = :skillId LIMIT 1")
    suspend fun getSkillByIdSync(skillId: String): JarvisSkillEntity?

    @Query("SELECT * FROM jarvis_skills WHERE targetPackageName = :packageName AND status = 'ACTIVE'")
    suspend fun getSkillsForPackage(packageName: String): List<JarvisSkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: JarvisSkillEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkills(skills: List<JarvisSkillEntity>)

    @Update
    suspend fun updateSkill(skill: JarvisSkillEntity)

    @Delete
    suspend fun deleteSkill(skill: JarvisSkillEntity)

    @Query("DELETE FROM jarvis_skills WHERE skillId = :skillId")
    suspend fun deleteSkillById(skillId: String)

    @Query("UPDATE jarvis_skills SET successCount = successCount + 1, lastExecutedAt = :timestamp WHERE skillId = :skillId")
    suspend fun recordSkillSuccess(skillId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE jarvis_skills SET failureCount = failureCount + 1, lastExecutedAt = :timestamp WHERE skillId = :skillId")
    suspend fun recordSkillFailure(skillId: String, timestamp: Long = System.currentTimeMillis())

    // =========================================================================
    // SKILL STEPS
    // =========================================================================

    @Query("SELECT * FROM jarvis_skill_steps WHERE skillId = :skillId ORDER BY stepIndex ASC")
    fun getStepsForSkill(skillId: String): Flow<List<JarvisSkillStepEntity>>

    @Query("SELECT * FROM jarvis_skill_steps WHERE skillId = :skillId ORDER BY stepIndex ASC")
    suspend fun getStepsForSkillSync(skillId: String): List<JarvisSkillStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: JarvisSkillStepEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<JarvisSkillStepEntity>)

    @Query("DELETE FROM jarvis_skill_steps WHERE skillId = :skillId")
    suspend fun deleteStepsForSkill(skillId: String)

    // =========================================================================
    // LEARNING SESSIONS
    // =========================================================================

    @Query("SELECT * FROM jarvis_learning_sessions ORDER BY startedAt DESC")
    fun getAllLearningSessions(): Flow<List<JarvisLearningSessionEntity>>

    @Query("SELECT * FROM jarvis_learning_sessions WHERE status = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    fun getActiveRecordingSession(): Flow<JarvisLearningSessionEntity?>

    @Query("SELECT * FROM jarvis_learning_sessions WHERE status = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveRecordingSessionSync(): JarvisLearningSessionEntity?

    @Query("SELECT * FROM jarvis_learning_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getLearningSessionById(sessionId: String): JarvisLearningSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLearningSession(session: JarvisLearningSessionEntity): Long

    @Update
    suspend fun updateLearningSession(session: JarvisLearningSessionEntity)

    @Query("UPDATE jarvis_learning_sessions SET status = :status, completedAt = :completedAt, recordedStepCount = :stepCount WHERE sessionId = :sessionId")
    suspend fun updateSessionStatus(sessionId: String, status: String, completedAt: Long?, stepCount: Int)

    @Query("DELETE FROM jarvis_learning_sessions WHERE sessionId = :sessionId")
    suspend fun deleteLearningSession(sessionId: String)
}
