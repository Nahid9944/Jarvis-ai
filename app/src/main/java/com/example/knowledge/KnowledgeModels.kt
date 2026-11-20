package com.example.knowledge

import com.example.ai.AiAgentRegistry

enum class RetrievalMode {
    FAST_RETRIEVAL,
    DEEP_RESEARCH,
    CONVERSATIONAL,
    ACTION_ONLY
}

enum class CacheTier {
    L1,
    L2,
    L3,
    L4,
    NONE
}

enum class ClaimStatus {
    VERIFIED,
    PARTIALLY_SUPPORTED,
    CONFLICTING,
    SINGLE_SOURCE_ONLY,
    UNVERIFIED,
    OUTDATED,
    REJECTED
}

enum class KnowledgeStorageState {
    NOT_REQUESTED,
    PENDING,
    SAVED,
    FAILED,
    RETRY_SCHEDULED
}

enum class TopicCategory(val defaultTtlMs: Long) {
    BREAKING_NEWS(5 * 60_000L),
    WEATHER(10 * 60_000L),
    RATES_PRICES(30 * 60_000L),
    POLICY_LAW(12 * 3_600_000L),
    SCIENCE_EVERGREEN(7 * 24 * 3_600_000L),
    GENERAL(6 * 3_600_000L)
}

data class KnowledgeSourceSpec(
    val sourceId: String,
    val displayName: String,
    val domain: String,
    val category: String,
    val authorityWeight: Float,
    val minIntervalMs: Long = 1_500L,
    val respectRobotsTxt: Boolean = true,
    val enabled: Boolean = true
)

data class KnowledgeCitation(
    val title: String,
    val url: String,
    val canonicalUrl: String,
    val domain: String,
    val snippet: String,
    val contentExtracted: Boolean,
    val contentHash: String = "",
    val publishedDate: String = "",
    val authorityWeight: Float = 0.5f,
    val isSyndicatedDuplicate: Boolean = false
)

data class VerifiedClaim(
    val claimText: String,
    val status: ClaimStatus,
    val independentDomainCount: Int,
    val supportingDomains: List<String>,
    val conflictingNotes: String = "",
    val confidenceScore: Float = 0.5f
)

data class KnowledgeQueryResult(
    val query: String,
    val normalizedQuery: String,
    val cacheKey: String,
    val mode: RetrievalMode,
    val topicCategory: TopicCategory,
    val cacheTierReached: CacheTier,
    val verificationStatus: ClaimStatus,
    val storageState: KnowledgeStorageState,
    val summaryBengali: String,
    val claims: List<VerifiedClaim>,
    val citations: List<KnowledgeCitation>,
    val firstResponseMs: Long,
    val totalLatencyMs: Long,
    val bytesTransferred: Long,
    val cancelled: Boolean = false,
    val offlineFallback: Boolean = false,
    val assignedController: String = AiAgentRegistry.JARVIS_MASTER.id,
    val assignedSpecialist: String = AiAgentRegistry.INTELLIGENT_AGENT.id
)

data class KnowledgeSystemLiveStatus(
    val controllerAgentName: String = AiAgentRegistry.JARVIS_MASTER.name,
    val specialistAgentName: String = AiAgentRegistry.INTELLIGENT_AGENT.name,
    val activeMode: RetrievalMode = RetrievalMode.FAST_RETRIEVAL,
    val lastQuery: String = "",
    val cacheTierReached: CacheTier = CacheTier.NONE,
    val evidenceStatus: ClaimStatus = ClaimStatus.UNVERIFIED,
    val storageState: KnowledgeStorageState = KnowledgeStorageState.NOT_REQUESTED,
    val serverConnectionState: String = "SERVER_UNCONFIGURED",
    val l1HitCount: Int = 0,
    val l2EntryCount: Int = 0,
    val lastLatencyMs: Long = 0L,
    val lastBytesTransferred: Long = 0L,
    val isResearchRunning: Boolean = false,
    val activeResearchJobId: String = "",
    val statusNoteBn: String = "প্রস্তুত (Fast-First L1→L2→L3→L4)"
)

object KnowledgeFreshnessPolicy {
    fun classifyTopic(query: String): TopicCategory {
        val lower = query.lowercase()
        return when {
            lower.contains("আবহাওয়া") || lower.contains("আবহাওয়া") || lower.contains("weather") ||
                lower.contains("তাপমাত্রা") || lower.contains("বৃষ্টি") -> TopicCategory.WEATHER
            lower.contains("তাজা খবর") || lower.contains("আজকের খবর") || lower.contains("breaking") ||
                lower.contains("news") || lower.contains("সংবাদ") || lower.contains("খবর") -> TopicCategory.BREAKING_NEWS
            lower.contains("দাম") || lower.contains("রেট") || lower.contains("ডলার") || lower.contains("gold") ||
                lower.contains("price") || lower.contains("rate") -> TopicCategory.RATES_PRICES
            lower.contains("আইন") || lower.contains("নীতিমালা") || lower.contains("সরকারি") || lower.contains("গেজেট") ||
                lower.contains("law") || lower.contains("policy") || lower.contains("rule") -> TopicCategory.POLICY_LAW
            lower.contains("বিজ্ঞান") || lower.contains("ইতিহাস") || lower.contains("সংজ্ঞা") || lower.contains("কিভাবে") ||
                lower.contains("wikipedia") || lower.contains("science") || lower.contains("define") -> TopicCategory.SCIENCE_EVERGREEN
            else -> TopicCategory.GENERAL
        }
    }

    fun computeTtlMs(category: TopicCategory): Long = category.defaultTtlMs
}
