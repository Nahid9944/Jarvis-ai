package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Dedicated Room DAO for J.A.R.V.I.S. Memory:
 * - User Preferences
 * - Conversation Snippets (for Long-Term AI Context)
 * - Task History (Execution Telemetry & Recalls)
 */
@Dao
interface JarvisMemoryDao {

    // -------------------------------------------------------------
    // USER PREFERENCES
    // -------------------------------------------------------------
    @Query("SELECT * FROM jarvis_user_preferences ORDER BY category ASC, key ASC")
    fun getAllPreferences(): Flow<List<JarvisUserPreferenceEntity>>

    @Query("SELECT * FROM jarvis_user_preferences WHERE key = :key LIMIT 1")
    fun getPreference(key: String): Flow<JarvisUserPreferenceEntity?>

    @Query("SELECT * FROM jarvis_user_preferences WHERE key = :key LIMIT 1")
    suspend fun getPreferenceSync(key: String): JarvisUserPreferenceEntity?

    @Query("SELECT * FROM jarvis_user_preferences")
    suspend fun getAllPreferencesSync(): List<JarvisUserPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreference(pref: JarvisUserPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreferences(prefs: List<JarvisUserPreferenceEntity>)

    @Query("DELETE FROM jarvis_user_preferences WHERE key = :key")
    suspend fun deletePreference(key: String)

    // -------------------------------------------------------------
    // CONVERSATION SNIPPETS (LONG-TERM AI CONTEXT)
    // -------------------------------------------------------------
    @Query("SELECT * FROM jarvis_conversation_snippets ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSnippets(limit: Int = 30): Flow<List<JarvisConversationSnippetEntity>>

    @Query("SELECT * FROM jarvis_conversation_snippets ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnippetsSync(limit: Int = 10): List<JarvisConversationSnippetEntity>

    @Query("SELECT * FROM jarvis_conversation_snippets WHERE importanceScore >= 2 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getHighImportanceSnippetsSync(limit: Int = 10): List<JarvisConversationSnippetEntity>

    @Query("SELECT * FROM jarvis_conversation_snippets WHERE userPrompt LIKE '%' || :query || '%' OR aiResponse LIKE '%' || :query || '%' OR keywords LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchSnippetsSync(query: String, limit: Int = 10): List<JarvisConversationSnippetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnippet(snippet: JarvisConversationSnippetEntity): Long

    @Query("DELETE FROM jarvis_conversation_snippets WHERE id = :id")
    suspend fun deleteSnippetById(id: Long)

    @Query("DELETE FROM jarvis_conversation_snippets")
    suspend fun clearAllSnippets()

    // -------------------------------------------------------------
    // TASK HISTORY (EXECUTION LOGS & CONTEXT)
    // -------------------------------------------------------------
    @Query("SELECT * FROM jarvis_task_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTaskHistory(limit: Int = 50): Flow<List<JarvisTaskHistoryEntity>>

    @Query("SELECT * FROM jarvis_task_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTaskHistorySync(limit: Int = 20): List<JarvisTaskHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskHistory(history: JarvisTaskHistoryEntity): Long

    @Query("DELETE FROM jarvis_task_history")
    suspend fun clearTaskHistory()
}
