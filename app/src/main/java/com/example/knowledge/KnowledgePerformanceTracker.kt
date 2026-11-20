package com.example.knowledge

import com.example.data.local.KnowledgeDao
import com.example.data.local.KnowledgeTelemetryEntity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

data class LatencySpanSample(
    val query: String,
    val mode: RetrievalMode,
    val cacheTierReached: CacheTier,
    val firstResponseMs: Long,
    val totalLatencyMs: Long,
    val bytesTransferred: Long,
    val sourceCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Monotonic System.nanoTime() performance telemetry & percentile tracker for the Knowledge Engine.
 */
object KnowledgePerformanceTracker {

    private val recentSamples = CopyOnWriteArrayList<LatencySpanSample>()
    private val totalBytesTransferred = AtomicLong(0L)

    fun nanoNow(): Long = System.nanoTime()

    fun elapsedMs(startNano: Long): Long {
        val diffNs = (System.nanoTime() - startNano).coerceAtLeast(0L)
        return diffNs / 1_000_000L
    }

    suspend fun recordSample(sample: LatencySpanSample, dao: KnowledgeDao? = null) {
        totalBytesTransferred.addAndGet(sample.bytesTransferred.coerceAtLeast(0L))
        recentSamples.add(0, sample)
        while (recentSamples.size > 100) {
            recentSamples.removeAt(recentSamples.lastIndex)
        }
        try {
            dao?.insertTelemetry(
                KnowledgeTelemetryEntity(
                    query = sample.query.take(160),
                    mode = sample.mode.name,
                    cacheTierReached = sample.cacheTierReached.name,
                    firstResponseMs = sample.firstResponseMs,
                    totalLatencyMs = sample.totalLatencyMs,
                    bytesTransferred = sample.bytesTransferred,
                    sourceCount = sample.sourceCount,
                    timestamp = sample.timestamp
                )
            )
        } catch (_: Exception) {
        }
    }

    fun getTotalBytesTransferred(): Long = totalBytesTransferred.get()

    fun computePercentileMs(percentile: Int): Long {
        val values = recentSamples.map { it.totalLatencyMs }.sorted()
        if (values.isEmpty()) return 0L
        val idx = (ceil((percentile / 100.0) * values.size).toInt() - 1).coerceIn(0, values.lastIndex)
        return values[idx]
    }

    fun getRecentSamples(): List<LatencySpanSample> = recentSamples.toList()

    fun resetForTest() {
        recentSamples.clear()
        totalBytesTransferred.set(0L)
    }
}
