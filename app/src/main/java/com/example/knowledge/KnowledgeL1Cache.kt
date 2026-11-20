package com.example.knowledge

import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe bounded L1 in-memory LRU cache for ultra-fast knowledge retrieval.
 */
object KnowledgeL1Cache {

    private const val MAX_ENTRIES = 128

    private data class L1Record(
        val result: KnowledgeQueryResult,
        val expiresAt: Long,
        val storedAt: Long = System.currentTimeMillis()
    )

    private val cacheMap = object : LinkedHashMap<String, L1Record>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, L1Record>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    fun normalizeQuery(raw: String): String {
        return raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[।?!.,:;]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun buildCacheKey(normalizedQuery: String, mode: RetrievalMode): String {
        val input = "${mode.name}::${normalizeQuery(normalizedQuery)}"
        return sha256Hex(input).take(32)
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(cacheKey: String, nowMs: Long = System.currentTimeMillis()): KnowledgeQueryResult? {
        val entry = cacheMap[cacheKey]
        if (entry == null) {
            misses.incrementAndGet()
            return null
        }
        if (entry.expiresAt <= nowMs) {
            cacheMap.remove(cacheKey)
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        return entry.result.copy(cacheTierReached = CacheTier.L1)
    }

    @Synchronized
    fun put(cacheKey: String, result: KnowledgeQueryResult, ttlMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val effectiveTtl = ttlMs.coerceAtLeast(30_000L)
        cacheMap[cacheKey] = L1Record(
            result = result,
            expiresAt = nowMs + effectiveTtl,
            storedAt = nowMs
        )
    }

    @Synchronized
    fun invalidate(cacheKey: String) {
        cacheMap.remove(cacheKey)
    }

    @Synchronized
    fun clear() {
        cacheMap.clear()
        hits.set(0)
        misses.set(0)
    }

    fun hitCount(): Int = hits.get()
    fun missCount(): Int = misses.get()
    @Synchronized
    fun size(): Int = cacheMap.size
}
