package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistantDao {
    // Notes / Quick Logs
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    suspend fun getAllNotesSync(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)

    @Query("DELETE FROM notes")
    suspend fun clearAllNotes()

    // Personal Owner Profile & Assistant Configuration
    @Query("SELECT * FROM jarvis_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<JarvisProfileEntity?>

    @Query("SELECT * FROM jarvis_profile WHERE id = 1 LIMIT 1")
    suspend fun getProfileSync(): JarvisProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: JarvisProfileEntity)

    @Query("DELETE FROM jarvis_profile")
    suspend fun clearProfile()

    // System Notifications & Alerts
    @Query("SELECT * FROM app_notifications WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getActiveNotifications(): Flow<List<AppNotificationEntity>>

    @Query("SELECT * FROM app_notifications WHERE isDeleted = 0 ORDER BY timestamp DESC")
    suspend fun getActiveNotificationsSync(): List<AppNotificationEntity>

    @Query("SELECT COUNT(*) FROM app_notifications WHERE isDeleted = 0 AND isRead = 0")
    fun getUnreadNotificationsCount(): Flow<Int>

    @Query("SELECT * FROM app_notifications WHERE eventKey = :eventKey LIMIT 1")
    suspend fun getNotificationByEventKey(eventKey: String): AppNotificationEntity?

    @Query("SELECT eventKey FROM app_notifications")
    suspend fun getAllExistingEventKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(notification: AppNotificationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotification(notification: AppNotificationEntity): Long

    @Query("SELECT * FROM app_notifications WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentActiveNotificationsSync(limit: Int = 50): List<AppNotificationEntity>

    @Query(
        "SELECT * FROM app_notifications WHERE isDeleted = 0 AND (" +
        "LOWER(appName) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(packageName) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(sender) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(title) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(message) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(targetId) LIKE '%' || LOWER(:query) || '%' OR " +
        "LOWER(targetName) LIKE '%' || LOWER(:query) || '%'" +
        ") ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun searchNotificationsSync(query: String, limit: Int = 50): List<AppNotificationEntity>

    @Query(
        "SELECT * FROM app_notifications WHERE isDeleted = 0 AND (" +
        "LOWER(packageName) LIKE '%' || LOWER(:appFilter) || '%' OR " +
        "LOWER(appName) LIKE '%' || LOWER(:appFilter) || '%' OR " +
        "LOWER(targetId) LIKE '%' || LOWER(:appFilter) || '%'" +
        ") ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getNotificationsByAppSync(appFilter: String, limit: Int = 30): List<AppNotificationEntity>

    @Query(
        "SELECT * FROM app_notifications WHERE isDeleted = 0 AND (" +
        "LOWER(sender) LIKE '%' || LOWER(:senderFilter) || '%' OR " +
        "LOWER(targetName) LIKE '%' || LOWER(:senderFilter) || '%' OR " +
        "LOWER(title) LIKE '%' || LOWER(:senderFilter) || '%'" +
        ") ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getNotificationsBySenderSync(senderFilter: String, limit: Int = 30): List<AppNotificationEntity>

    @Query("SELECT * FROM app_notifications WHERE isDeleted = 0 AND timestamp >= :sinceMillis ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getNotificationsSinceSync(sinceMillis: Long, limit: Int = 50): List<AppNotificationEntity>

    @Query("SELECT * FROM app_notifications WHERE isDeleted = 0 AND type = 'SECURITY_OTP' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getOtpNotificationsSync(limit: Int = 20): List<AppNotificationEntity>

    @Query("UPDATE app_notifications SET isAnnounced = 1, announcedAt = :announcedAt WHERE eventKey = :eventKey")
    suspend fun markNotificationAnnounced(eventKey: String, announcedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_notifications SET isRead = 1 WHERE eventKey = :eventKey")
    suspend fun markNotificationReadByKey(eventKey: String)

    @Query("UPDATE app_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationAsRead(id: Long)

    @Query("UPDATE app_notifications SET isRead = 1 WHERE isDeleted = 0")
    suspend fun markAllNotificationsAsRead()

    @Query("UPDATE app_notifications SET isDeleted = 1 WHERE id = :id")
    suspend fun deleteNotificationById(id: Long)

    @Query("UPDATE app_notifications SET isDeleted = 1 WHERE isDeleted = 0")
    suspend fun clearAllNotifications()

    // AI Memories
    @Query("SELECT * FROM ai_memories WHERE isActive = 1 ORDER BY isPinned DESC, importance DESC, updatedAt DESC")
    fun getAllActiveMemories(): Flow<List<AiMemoryEntity>>

    @Query("SELECT * FROM ai_memories WHERE isActive = 1 ORDER BY isPinned DESC, importance DESC, updatedAt DESC")
    suspend fun getAllActiveMemoriesSync(): List<AiMemoryEntity>

    @Query("SELECT * FROM ai_memories ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllMemories(): Flow<List<AiMemoryEntity>>

    @Query("SELECT * FROM ai_memories WHERE `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): AiMemoryEntity?

    @Query("SELECT * FROM ai_memories WHERE isActive = 1 AND (content LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%') ORDER BY isPinned DESC, importance DESC, updatedAt DESC")
    suspend fun searchMemoriesSync(query: String): List<AiMemoryEntity>

    @Query("SELECT * FROM ai_memories WHERE isActive = 1 AND (content LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%') ORDER BY isPinned DESC, updatedAt DESC")
    fun searchMemories(query: String): Flow<List<AiMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: AiMemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: AiMemoryEntity)

    @Query("UPDATE ai_memories SET isActive = 0 WHERE id = :id")
    suspend fun deactivateMemoryById(id: Long)

    @Query("DELETE FROM ai_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM ai_memories WHERE `key` = :key")
    suspend fun deleteMemoryByKey(key: String)

    @Query("DELETE FROM ai_memories WHERE isPinned = 0")
    suspend fun clearUnpinnedMemories()

    // AI Tasks & Reminders
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiTask(task: AiTaskEntity): Long

    @Update
    suspend fun updateAiTask(task: AiTaskEntity)

    @Query("DELETE FROM ai_tasks WHERE id = :id")
    suspend fun deleteAiTask(id: Long)

    @Query("SELECT * FROM ai_tasks ORDER BY isExecuted ASC, scheduledTime ASC, id DESC")
    fun getAllAiTasks(): Flow<List<AiTaskEntity>>

    @Query("SELECT * FROM ai_tasks ORDER BY isExecuted ASC, scheduledTime ASC, id DESC")
    suspend fun getAllAiTasksSync(): List<AiTaskEntity>

    @Query("SELECT * FROM ai_tasks WHERE isExecuted = 0 AND scheduledTime <= :now")
    suspend fun getDueAiTasksSync(now: Long): List<AiTaskEntity>

    @Query("SELECT * FROM ai_tasks WHERE isExecuted = 0 AND status = 'PENDING' AND scheduledTime > :afterTime ORDER BY scheduledTime ASC")
    suspend fun getPendingFutureTasksSync(afterTime: Long): List<AiTaskEntity>

    @Query("UPDATE ai_tasks SET isExecuted = 1, status = 'COMPLETED' WHERE id = :id")
    suspend fun markAiTaskCompleted(id: Long)

    // Owner Device & Session Nodes
    @Query("SELECT * FROM app_users ORDER BY isOnline DESC, lastSeenAt DESC")
    fun getAllUsers(): Flow<List<AppUserEntity>>

    @Query("SELECT * FROM app_users ORDER BY isOnline DESC, lastSeenAt DESC")
    suspend fun getAllUsersSync(): List<AppUserEntity>

    @Query("SELECT * FROM app_users WHERE userId = :userId LIMIT 1")
    suspend fun getUserById(userId: String): AppUserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: AppUserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<AppUserEntity>)

    @Update
    suspend fun updateUser(user: AppUserEntity)

    @Query("UPDATE app_users SET isBlocked = :isBlocked WHERE userId = :userId")
    suspend fun updateUserBlockStatus(userId: String, isBlocked: Boolean)

    @Query("UPDATE app_users SET isBlocked = :isBlocked, blockedBy = :blockedBy, blockedAt = :blockedAt WHERE userId = :userId")
    suspend fun updateUserBlockDetails(userId: String, isBlocked: Boolean, blockedBy: String, blockedAt: Long)

    @Query("UPDATE app_users SET isOnline = :isOnline, lastSeenAt = :lastSeenAt WHERE userId = :userId")
    suspend fun updateUserPresence(userId: String, isOnline: Boolean, lastSeenAt: Long)

    @Query("UPDATE app_users SET latitude = :lat, longitude = :lng, locationAddress = :address, locationUpdatedAt = :updatedAt WHERE userId = :userId")
    suspend fun updateUserLocation(userId: String, lat: Double?, lng: Double?, address: String, updatedAt: Long)

    @Query("UPDATE app_users SET latitude = :lat, longitude = :lng, accuracy = :accuracy, locationAddress = :address, locationUpdatedAt = :updatedAt WHERE userId = :userId")
    suspend fun updateUserLocationDetailed(userId: String, lat: Double?, lng: Double?, accuracy: Float?, address: String, updatedAt: Long)

    @Query("DELETE FROM app_users WHERE userId NOT IN (:keptIds)")
    suspend fun removeStaleUsers(keptIds: List<String>)

    @Query("DELETE FROM app_users")
    suspend fun clearAllUsers()

    // Holographic Chat History
    @Query("SELECT * FROM jarvis_chat_history ORDER BY timestamp DESC")
    fun getAllJarvisChatHistory(): Flow<List<JarvisChatHistoryEntity>>

    @Query("SELECT * FROM jarvis_chat_history ORDER BY timestamp ASC")
    fun getAllJarvisChatHistoryAsc(): Flow<List<JarvisChatHistoryEntity>>

    @Query("SELECT * FROM jarvis_chat_history ORDER BY timestamp ASC")
    suspend fun getAllJarvisChatHistorySync(): List<JarvisChatHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJarvisChat(chat: JarvisChatHistoryEntity): Long

    @Query("DELETE FROM jarvis_chat_history WHERE id = :id")
    suspend fun deleteJarvisChatById(id: Long)

    @Query("DELETE FROM jarvis_chat_history")
    suspend fun clearAllJarvisChatHistory()

    // User Preferences
    @Query("SELECT * FROM jarvis_user_preferences ORDER BY category ASC, key ASC")
    fun getAllPreferences(): Flow<List<JarvisUserPreferenceEntity>>

    @Query("SELECT * FROM jarvis_user_preferences WHERE category = :category ORDER BY key ASC")
    fun getPreferencesByCategory(category: String): Flow<List<JarvisUserPreferenceEntity>>

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

    // Frequently Used Contacts
    @Query("SELECT * FROM jarvis_frequent_contacts ORDER BY isFavorite DESC, interactionCount DESC, lastContactedAt DESC")
    fun getAllFrequentContacts(): Flow<List<JarvisFrequentContactEntity>>

    @Query("SELECT * FROM jarvis_frequent_contacts ORDER BY isFavorite DESC, interactionCount DESC, lastContactedAt DESC")
    suspend fun getAllFrequentContactsSync(): List<JarvisFrequentContactEntity>

    @Query("SELECT * FROM jarvis_frequent_contacts ORDER BY isFavorite DESC, interactionCount DESC LIMIT :limit")
    fun getTopFrequentContacts(limit: Int = 10): Flow<List<JarvisFrequentContactEntity>>

    @Query("SELECT * FROM jarvis_frequent_contacts WHERE LOWER(name) = LOWER(:query) OR LOWER(alias) = LOWER(:query) OR phoneNumber LIKE '%' || :query || '%' LIMIT 1")
    suspend fun getFrequentContactByAliasOrNameSync(query: String): JarvisFrequentContactEntity?

    @Query("SELECT * FROM jarvis_frequent_contacts WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%' OR LOWER(alias) LIKE '%' || LOWER(:query) || '%' OR phoneNumber LIKE '%' || :query || '%'")
    fun searchFrequentContacts(query: String): Flow<List<JarvisFrequentContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrequentContact(contact: JarvisFrequentContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrequentContacts(contacts: List<JarvisFrequentContactEntity>)

    @Update
    suspend fun updateFrequentContact(contact: JarvisFrequentContactEntity)

    @Query("UPDATE jarvis_frequent_contacts SET interactionCount = interactionCount + 1, lastContactedAt = :now WHERE id = :id")
    suspend fun incrementContactInteraction(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM jarvis_frequent_contacts WHERE id = :id")
    suspend fun deleteFrequentContact(id: Long)

    // Task & Context Interaction History
    @Query("SELECT * FROM jarvis_task_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTaskHistory(limit: Int = 50): Flow<List<JarvisTaskHistoryEntity>>

    @Query("SELECT * FROM jarvis_task_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTaskHistorySync(limit: Int = 20): List<JarvisTaskHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskHistory(history: JarvisTaskHistoryEntity): Long

    @Query("DELETE FROM jarvis_task_history")
    suspend fun clearTaskHistory()

    // Persistent Artifact Workspace
    @Query("SELECT * FROM artifacts ORDER BY updatedAt DESC")
    fun getAllArtifacts(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts ORDER BY updatedAt DESC")
    suspend fun getAllArtifactsSync(): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun getArtifactByIdSync(id: Long): ArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifact(artifact: ArtifactEntity): Long

    @Update
    suspend fun updateArtifact(artifact: ArtifactEntity)

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteArtifact(id: Long)
}

