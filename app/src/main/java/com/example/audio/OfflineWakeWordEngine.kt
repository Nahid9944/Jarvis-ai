package com.example.audio

import com.example.service.WakeWordSensitivity
import java.util.Locale
import kotlin.math.sqrt

data class WakePhraseMatch(
    val matched: Boolean,
    val matchedPhrase: String = "",
    val remainderCommand: String = "",
    val confidence: Float = 0f
)

/**
 * Pure Kotlin Wake-Word Acoustic VAD + Multilingual (Bengali / Banglish / English)
 * Phrase Matching & Cooldown Gate.
 */
object OfflineWakeWordEngine {

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    fun resetCooldown() {
        lastTriggerAtMs = 0L
    }

    /**
     * Computes 16-bit PCM RMS amplitude from raw little-endian PCM frames (16 kHz mono).
     */
    fun calculateRmsEnergy(pcm16Samples: ShortArray, sampleCount: Int = pcm16Samples.size): Float {
        if (sampleCount <= 0 || pcm16Samples.isEmpty()) return 0f
        val count = sampleCount.coerceAtMost(pcm16Samples.size)
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = pcm16Samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count).toFloat()
    }

    fun calculateRmsEnergyFromBytes(pcmBytes: ByteArray, length: Int = pcmBytes.size): Float {
        if (length < 2 || pcmBytes.size < 2) return 0f
        val pairCount = length.coerceAtMost(pcmBytes.size) / 2
        if (pairCount <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until pairCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSq / pairCount).toFloat()
    }

    fun detectSpeechActivity(rmsEnergy: Float, threshold: Float = 280f): Boolean {
        return rmsEnergy >= threshold.coerceAtLeast(50f)
    }

    fun canTriggerNow(nowMs: Long = System.currentTimeMillis(), cooldownMs: Long = 2500L): Boolean {
        val elapsed = nowMs - lastTriggerAtMs
        return elapsed >= cooldownMs
    }

    fun markTriggered(nowMs: Long = System.currentTimeMillis()) {
        lastTriggerAtMs = nowMs
    }

    private val HIGH_SENSITIVITY_VARIANTS = listOf(
        "javis",
        "charvis",
        "jarv",
        "jarvi",
        "jar vis",
        "সারভিস",
        "জার্ভিস",
        "জারবিস",
        "হে জারভিস",
        "জার্ভ"
    )

    private val LOW_SENSITIVITY_ONLY = setOf(
        "jarvis",
        "hey jarvis",
        "জারভিস",
        "এই জারভিস"
    )

    /**
     * Evaluates spoken utterance against configured wake phrases + sensitivity level
     * and extracts any trailing command (e.g. "Hey Jarvis কল করো বাবাকে" -> "কল করো বাবাকে").
     */
    fun matchWakePhrase(
        spokenText: String,
        configuredPhrases: List<String> = WakeWordModelManager.DEFAULT_WAKE_PHRASES,
        sensitivity: WakeWordSensitivity = WakeWordSensitivity.MEDIUM,
        isPartial: Boolean = false
    ): WakePhraseMatch {
        if (spokenText.isBlank()) return WakePhraseMatch(false)
        if (sensitivity == WakeWordSensitivity.LOW && isPartial) return WakePhraseMatch(false)

        val normalized = spokenText.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase(Locale.ROOT)

        val candidates: List<String> = when (sensitivity) {
            WakeWordSensitivity.LOW -> configuredPhrases.filter { phrase ->
                val pLower = phrase.lowercase(Locale.ROOT)
                pLower in LOW_SENSITIVITY_ONLY || phrase !in WakeWordModelManager.DEFAULT_WAKE_PHRASES
            }
            WakeWordSensitivity.MEDIUM -> configuredPhrases
            WakeWordSensitivity.HIGH -> (configuredPhrases + HIGH_SENSITIVITY_VARIANTS).distinct()
        }.sortedByDescending { it.length }

        for (phrase in candidates) {
            val pLower = phrase.trim().lowercase(Locale.ROOT)
            if (pLower.isBlank()) continue
            val idx = lower.indexOf(pLower)
            if (idx >= 0) {
                val remainder = normalized.removeRange(idx, (idx + pLower.length).coerceAtMost(normalized.length))
                    .trimStart(',', '.', '!', '?', ' ', ':', ';', '।')
                    .trimEnd(',', ' ', ':', ';')
                val confidence = when {
                    !isPartial && remainder.isNotBlank() -> 0.96f
                    !isPartial -> 0.92f
                    sensitivity == WakeWordSensitivity.HIGH -> 0.80f
                    else -> 0.86f
                }
                return WakePhraseMatch(
                    matched = true,
                    matchedPhrase = phrase,
                    remainderCommand = remainder,
                    confidence = confidence
                )
            }
        }
        return WakePhraseMatch(false)
    }
}
