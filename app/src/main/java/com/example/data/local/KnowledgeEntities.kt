package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(
    tableName = "knowledge_cache",
    indices = [
        Index(value = ["normalizedQuery"]),
        Index(value = ["expiresAt"]),
        Index(value = ["storageState"])
    ]
)
@JsonClass(generateAdapter = true)
data class KnowledgeCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val normalizedQuery: String,
    val topicCategory: String = "GENERAL", // NEWS, WEATHER, RATE, POLICY, SCIENCE, GENERAL
    val answerSummaryBn: String,
    val claimsJson: String = "[]",
    val citationsJson: String = "[]",
    val verificationStatus: String = "UNVERIFIED", // VERIFIED, PARTIALLY_SUPPORTED, CONFLICTING, SINGLE_SOURCE_ONLY, UNVERIFIED, OUTDATED, REJECTED
    val storageState: String = "PENDING", // NOT_REQUESTED, PENDING, SAVED, FAILED, RETRY_SCHEDULED
    val sourceTier: String = "L4_WEB", // L1_RAM, L2_ROOM, L3_SERVER, L4_WEB
    val etag: String = "",
    val lastModified: String = "",
    val contentHash: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86_400_000L,
    val lastAccessedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "knowledge_telemetry",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["cacheTierReached"])
    ]
)
@JsonClass(generateAdapter = true)
data class KnowledgeTelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val query: String,
    val mode: String, // FAST_RETRIEVAL, DEEP_RESEARCH, CONVERSATIONAL
    val cacheTierReached: String, // L1, L2, L3, L4
    val firstResponseMs: Long,
    val totalLatencyMs: Long,
    val bytesTransferred: Long = 0L,
    val sourceCount: Int = 0,
    val verifiedClaimCount: Int = 0,
    val storageState: String = "NOT_REQUESTED",
    val timestamp: Long = System.currentTimeMillis()
)
