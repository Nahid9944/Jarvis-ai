package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {

    @Query("SELECT * FROM knowledge_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCacheByKey(cacheKey: String): KnowledgeCacheEntity?

    @Query("SELECT * FROM knowledge_cache WHERE normalizedQuery = :normalizedQuery ORDER BY createdAt DESC LIMIT 1")
    suspend fun findCacheByNormalizedQuery(normalizedQuery: String): KnowledgeCacheEntity?

    @Query(
        "SELECT * FROM knowledge_cache WHERE normalizedQuery LIKE '%' || :query || '%' OR answerSummaryBn LIKE '%' || :query || '%' ORDER BY lastAccessedAt DESC LIMIT :limit"
    )
    suspend fun searchCache(query: String, limit: Int = 10): List<KnowledgeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(entry: KnowledgeCacheEntity)

    @Query("UPDATE knowledge_cache SET lastAccessedAt = :now WHERE cacheKey = :cacheKey")
    suspend fun touchCacheAccess(cacheKey: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM knowledge_cache WHERE storageState IN ('PENDING', 'FAILED', 'RETRY_SCHEDULED') ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingSyncEntries(limit: Int = 20): List<KnowledgeCacheEntity>

    @Query("UPDATE knowledge_cache SET storageState = :storageState WHERE cacheKey = :cacheKey")
    suspend fun updateStorageState(cacheKey: String, storageState: String)

    @Query("DELETE FROM knowledge_cache WHERE expiresAt <= :now")
    suspend fun purgeExpiredCache(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM knowledge_cache")
    suspend fun countCacheEntries(): Int

    @Query("SELECT * FROM knowledge_cache ORDER BY lastAccessedAt DESC LIMIT :limit")
    fun observeRecentCache(limit: Int = 20): Flow<List<KnowledgeCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(entry: KnowledgeTelemetryEntity): Long

    @Query("SELECT * FROM knowledge_telemetry ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTelemetrySync(limit: Int = 50): List<KnowledgeTelemetryEntity>

    @Query("SELECT * FROM knowledge_telemetry ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentTelemetry(limit: Int = 30): Flow<List<KnowledgeTelemetryEntity>>
}
