package com.example.ai

import com.example.data.local.CompanionDao
import com.example.data.local.EmotionHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Categorization of user emotional state for empathetic companion responses
 */
enum class UserEmotion(val labelEnglish: String, val labelBengali: String) {
    HAPPY("Happy", "আনন্দিত / প্রফুল্ল"),
    SAD("Sad", "বিষণ্ণ / মন খারাপ"),
    ANGRY("Angry", "ক্রুদ্ধ / রাগান্বিত"),
    STRESSED("Stressed", "মানসিক চাপে / উদ্বেলিত"),
    WORRIED("Worried", "উদ্বিগ্ন / চিন্তিত"),
    LONELY("Lonely", "একাকী / নিঃসঙ্গ"),
    TIRED("Tired", "ক্লান্ত / পরিশ্রান্ত"),
    EXCITED("Excited", "উত্তেজিত / রোমাঞ্চিত"),
    CONFUSED("Confused", "বিভ্রান্ত / দ্বিধান্বিত"),
    CALM("Calm", "শান্ত / স্বস্তিময়"),
    NEUTRAL("Neutral", "স্বাভাবিক / সাধারণ"),
    UNKNOWN("Unknown", "অনির্ধারিত")
}

data class EmotionEvaluation(
    val emotion: UserEmotion,
    val confidence: Float,
    val source: String,
    val timestamp: Long = System.currentTimeMillis(),
    val contextSummaryBn: String,
    val userCorrected: Boolean = false,
    val isUncertain: Boolean = false,
    val conflictReasonBn: String = ""
)

data class EmotionControlConfig(
    val textAnalysisEnabled: Boolean = true,
    val voiceEnergyAnalysisEnabled: Boolean = true,
    val cameraExpressionEnabled: Boolean = true,
    val adaptiveToneEnabled: Boolean = true,
    val confidenceThreshold: Float = 0.65f
)

object EmotionContextEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var companionDao: CompanionDao? = null

    private val baselineEvaluation = EmotionEvaluation(
        emotion = UserEmotion.NEUTRAL,
        confidence = 1.0f,
        source = "baseline",
        contextSummaryBn = "ব্যবহারকারী স্বাভাবিক শান্ত অবস্থায় আছেন।"
    )

    private val _currentEmotion = MutableStateFlow(baselineEvaluation)
    val currentEmotion: StateFlow<EmotionEvaluation> = _currentEmotion.asStateFlow()

    private val _config = MutableStateFlow(EmotionControlConfig())
    val config: StateFlow<EmotionControlConfig> = _config.asStateFlow()

    private val _history = MutableStateFlow<List<EmotionEvaluation>>(emptyList())
    val history: StateFlow<List<EmotionEvaluation>> = _history.asStateFlow()

    // Read-only acoustic RMS smoothing without allocating any AudioRecord
    @Volatile
    private var smoothedVoiceEnergy: Float = 0f
    @Volatile
    private var highEnergyStreakCount: Int = 0
    @Volatile
    private var lastVoiceUpdateAt: Long = 0L

    fun attachDao(dao: CompanionDao) {
        companionDao = dao
        ioScope.launch {
            try {
                val recent = dao.getRecentEmotionHistorySync(40).map { entity ->
                    EmotionEvaluation(
                        emotion = UserEmotion.entries.find { it.name == entity.emotion } ?: UserEmotion.UNKNOWN,
                        confidence = entity.confidence,
                        source = entity.source,
                        timestamp = entity.timestamp,
                        contextSummaryBn = entity.contextSummaryBn,
                        userCorrected = entity.userCorrected,
                        isUncertain = entity.isUncertain
                    )
                }
                if (recent.isNotEmpty()) {
                    _history.value = recent
                    _currentEmotion.value = recent.first()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun updateConfig(newConfig: EmotionControlConfig) {
        _config.value = newConfig.copy(
            confidenceThreshold = newConfig.confidenceThreshold.coerceIn(0.4f, 0.95f)
        )
    }

    /**
     * Evaluates emotional tone from user's natural Bengali / Banglish / English text.
     */
    fun analyzeText(text: String): EmotionEvaluation {
        if (!_config.value.textAnalysisEnabled) {
            return _currentEmotion.value
        }
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return _currentEmotion.value

        var detectedEmotion = UserEmotion.NEUTRAL
        var confidence = 0.52f
        var summary = "স্বাভাবিক কথোপকথন"

        when {
            lower.contains("মন খারাপ") || lower.contains("কষ্ট হচ্ছে") || lower.contains("কাঁদছি") ||
                lower.contains("ভালো লাগছে না") || lower.contains("হারিয়ে ফেলেছি") || lower.contains("sad") ||
                lower.contains("depressed") || lower.contains("heartbroken") || lower.contains("হতাশ") -> {
                detectedEmotion = UserEmotion.SAD
                confidence = 0.88f
                summary = "ব্যবহারকারী মানসিকভাবে ব্যথিত বা মন খারাপ অবস্থায় আছেন।"
            }

            lower.contains("একা লাগছে") || lower.contains("নিঃসঙ্গ") || lower.contains("কেউ নেই") ||
                lower.contains("lonely") || lower.contains("alone") -> {
                detectedEmotion = UserEmotion.LONELY
                confidence = 0.90f
                summary = "ব্যবহারকারী একাকীত্ব অনুভব করছেন।"
            }

            lower.contains("উদ্বিগ্ন") || lower.contains("ভয় লাগছে") || lower.contains("চিন্তা হচ্ছে") ||
                lower.contains("worried") || lower.contains("anxious") -> {
                detectedEmotion = UserEmotion.WORRIED
                confidence = 0.86f
                summary = "ব্যবহারকারী কোনো বিষয় নিয়ে উদ্বিগ্ন বা চিন্তিত।"
            }

            lower.contains("চাপ লাগছে") || lower.contains("প্রেসার") || lower.contains("টেন্সড") ||
                lower.contains("stress") || lower.contains("overwhelmed") -> {
                detectedEmotion = UserEmotion.STRESSED
                confidence = 0.85f
                summary = "ব্যবহারকারী মানসিক চাপের মধ্যে আছেন।"
            }

            lower.contains("ক্লান্ত") || lower.contains("ঘুমাতে চাই") || lower.contains("মাথা ধরেছে") ||
                lower.contains("অনেক কাজ করেছি") || lower.contains("tired") || lower.contains("exhausted") -> {
                detectedEmotion = UserEmotion.TIRED
                confidence = 0.86f
                summary = "ব্যবহারকারী ক্লান্ত এবং বিশ্রাম প্রয়োজন।"
            }

            lower.contains("রাগ হচ্ছে") || lower.contains("বিরক্ত") || lower.contains("অসহ্য") ||
                lower.contains("angry") || lower.contains("furious") || lower.contains("মেজাজ খারাপ") -> {
                detectedEmotion = UserEmotion.ANGRY
                confidence = 0.87f
                summary = "ব্যবহারকারী ক্ষুব্ধ বা বিরক্ত।"
            }

            lower.contains("বুঝছি না") || lower.contains("কনফিউজড") || lower.contains("বিভ্রান্ত") ||
                lower.contains("confused") -> {
                detectedEmotion = UserEmotion.CONFUSED
                confidence = 0.82f
                summary = "ব্যবহারকারী কিছুটা দ্বিধান্বিত বা স্পষ্ট ধারণা খুঁজছেন।"
            }

            lower.contains("খুব খুশি") || lower.contains("অনেক আনন্দ") || lower.contains("দারুণ") ||
                lower.contains("চমৎকার") || lower.contains("happy") || lower.contains("awesome") ||
                lower.contains("মজা পেয়েছি") -> {
                detectedEmotion = UserEmotion.HAPPY
                confidence = 0.89f
                summary = "ব্যবহারকারী আনন্দিত ও সন্তুষ্ট।"
            }

            lower.contains("উত্তেজিত") || lower.contains("excited") ||
                lower.contains("বিজয়") || lower.contains("জিতে গেছি") -> {
                detectedEmotion = UserEmotion.EXCITED
                confidence = 0.88f
                summary = "ব্যবহারকারী অত্যন্ত রোমাঞ্চিত ও উচ্ছ্বসিত।"
            }

            lower.contains("শান্ত") || lower.contains("স্বস্তি") || lower.contains("রিল্যাক্স") ||
                lower.contains("calm") || lower.contains("peace") -> {
                detectedEmotion = UserEmotion.CALM
                confidence = 0.82f
                summary = "ব্যবহারকারী স্বস্তিময় ও শান্ত মেজাজে আছেন।"
            }
        }

        val eval = EmotionEvaluation(
            emotion = detectedEmotion,
            confidence = confidence,
            source = "text_analysis",
            contextSummaryBn = summary
        )
        if (detectedEmotion != UserEmotion.NEUTRAL && confidence >= _config.value.confidenceThreshold) {
            commitEvaluation(eval)
        }
        return eval
    }

    /**
     * Read-only acoustic RMS cadence observer called by GeminiLiveSessionManager.
     * Does NOT open any microphone or AudioRecord.
     */
    fun observeVoiceEnergySample(micLevel: Float) {
        if (!_config.value.voiceEnergyAnalysisEnabled) return
        val clamped = micLevel.coerceIn(0f, 1f)
        smoothedVoiceEnergy = (smoothedVoiceEnergy * 0.80f) + (clamped * 0.20f)
        val now = System.currentTimeMillis()
        if (smoothedVoiceEnergy > 0.58f) {
            highEnergyStreakCount++
            if (highEnergyStreakCount >= 18 && now - lastVoiceUpdateAt > 6000L) {
                lastVoiceUpdateAt = now
                highEnergyStreakCount = 0
                val curr = _currentEmotion.value
                // Only add acoustic energy cue if no explicit strong emotion contradicts it
                if (curr.emotion == UserEmotion.NEUTRAL || curr.emotion == UserEmotion.CALM) {
                    commitEvaluation(
                        EmotionEvaluation(
                            emotion = UserEmotion.EXCITED,
                            confidence = 0.68f,
                            source = "voice_energy_cadence",
                            contextSummaryBn = "ভয়েস এনার্জি তুলনামূলকভাবে বেশি ও প্রাণবন্ত শোনাচ্ছে।"
                        )
                    )
                }
            }
        } else {
            highEnergyStreakCount = (highEnergyStreakCount - 1).coerceAtLeast(0)
        }
    }

    /**
     * Synthesizes text, voice acoustics, and camera visual cues with explicit conflict / uncertainty reporting.
     */
    fun updateMultimodalEmotion(
        textEval: EmotionEvaluation? = null,
        micLevel: Float = 0f,
        faceDetected: Boolean = false,
        smileLikelihood: Float = 0f,
        visualEmotion: UserEmotion? = null,
        visualConfidence: Float = 0f
    ): EmotionEvaluation {
        val cfg = _config.value
        val validText = if (cfg.textAnalysisEnabled && textEval != null && textEval.emotion != UserEmotion.NEUTRAL) {
            textEval
        } else null

        val inferredVisual: EmotionEvaluation? = if (cfg.cameraExpressionEnabled && faceDetected) {
            when {
                visualEmotion != null && visualEmotion != UserEmotion.NEUTRAL && visualEmotion != UserEmotion.UNKNOWN ->
                    EmotionEvaluation(
                        emotion = visualEmotion,
                        confidence = visualConfidence.coerceIn(0.5f, 0.95f),
                        source = "camera_vision",
                        contextSummaryBn = "ক্যামেরার দৃষ্টিভঙ্গিতে ব্যবহারকারীর মুখের ভাব: ${visualEmotion.labelBengali}"
                    )
                smileLikelihood > 0.68f ->
                    EmotionEvaluation(
                        emotion = UserEmotion.HAPPY,
                        confidence = 0.78f,
                        source = "camera_smile",
                        contextSummaryBn = "ক্যামেরার ফ্রেমে হাসির অভিব্যক্তি দেখা যাচ্ছে।"
                    )
                else -> null
            }
        } else null

        // Explicit Conflict Detection (e.g., sad/stressed text vs happy camera face)
        if (validText != null && inferredVisual != null && isOppositeValence(validText.emotion, inferredVisual.emotion)) {
            val uncertainEval = EmotionEvaluation(
                emotion = UserEmotion.UNKNOWN,
                confidence = 0.45f,
                source = "multimodal_conflict",
                contextSummaryBn = "কথার ধরন (${validText.emotion.labelBengali}) এবং মুখের অভিব্যক্তি (${inferredVisual.emotion.labelBengali}) ভিন্ন — মেজাজ অনিশ্চিত।",
                isUncertain = true,
                conflictReasonBn = "টেক্সট ও ক্যামেরার সংকেত পরস্পরবিরোধী হওয়ায় নিশ্চিত অনুমান এড়ানো হয়েছে।"
            )
            commitEvaluation(uncertainEval)
            return uncertainEval
        }

        if (validText != null && validText.confidence >= cfg.confidenceThreshold) {
            commitEvaluation(validText)
            return validText
        }

        if (inferredVisual != null && inferredVisual.confidence >= cfg.confidenceThreshold) {
            commitEvaluation(inferredVisual)
            return inferredVisual
        }

        if (cfg.voiceEnergyAnalysisEnabled && micLevel > 0.55f) {
            val energyEval = EmotionEvaluation(
                emotion = UserEmotion.EXCITED,
                confidence = 0.67f,
                source = "voice_loudness",
                contextSummaryBn = "উচ্চ ভয়েস এনার্জি শনাক্ত হয়েছে।"
            )
            if (energyEval.confidence >= cfg.confidenceThreshold) {
                commitEvaluation(energyEval)
                return energyEval
            }
        }

        return _currentEmotion.value
    }

    fun applyUserEmotionCorrection(correctedEmotion: UserEmotion) {
        val eval = EmotionEvaluation(
            emotion = correctedEmotion,
            confidence = 1.0f,
            source = "user_direct_correction",
            contextSummaryBn = "ব্যবহারকারী সরাসরি তার অনুভূতি নিশ্চিত করেছেন: ${correctedEmotion.labelBengali}",
            userCorrected = true,
            isUncertain = false
        )
        commitEvaluation(eval)
    }

    fun resetBaseline() {
        smoothedVoiceEnergy = 0f
        highEnergyStreakCount = 0
        _currentEmotion.value = baselineEvaluation.copy(timestamp = System.currentTimeMillis())
    }

    fun clearHistory() {
        _history.value = emptyList()
        resetBaseline()
        ioScope.launch {
            try {
                companionDao?.clearEmotionHistory()
            } catch (_: Exception) {
            }
        }
    }

    private fun isOppositeValence(a: UserEmotion, b: UserEmotion): Boolean {
        val negative = setOf(UserEmotion.SAD, UserEmotion.ANGRY, UserEmotion.STRESSED, UserEmotion.WORRIED, UserEmotion.LONELY)
        val positive = setOf(UserEmotion.HAPPY, UserEmotion.EXCITED)
        return (a in negative && b in positive) || (a in positive && b in negative)
    }

    private fun commitEvaluation(eval: EmotionEvaluation) {
        _currentEmotion.value = eval
        _history.value = (listOf(eval) + _history.value).take(50)
        ioScope.launch {
            try {
                companionDao?.insertEmotionHistory(
                    EmotionHistoryEntity(
                        emotion = eval.emotion.name,
                        confidence = eval.confidence,
                        source = eval.source,
                        isUncertain = eval.isUncertain,
                        contextSummaryBn = eval.contextSummaryBn,
                        userCorrected = eval.userCorrected,
                        timestamp = eval.timestamp
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    fun getPromptContext(): String {
        val curr = _currentEmotion.value
        val uncertaintyLine = if (curr.isUncertain) {
            "- Uncertainty Notice: Signal is uncertain (${curr.conflictReasonBn}). Ask gently without making assumptions."
        } else {
            "- Guidance: Adapt your warmth, empathy and pacing naturally according to this emotion. Never exaggerate."
        }
        return """
[OBSERVED USER EMOTIONAL STATE]:
- Category: ${curr.emotion.name} (${curr.emotion.labelBengali})
- Source: ${curr.source}
- Confidence: ${(curr.confidence * 100).toInt()}%
- Summary: ${curr.contextSummaryBn}
$uncertaintyLine
""".trimIndent()
    }

    fun resetForTest() {
        smoothedVoiceEnergy = 0f
        highEnergyStreakCount = 0
        lastVoiceUpdateAt = 0L
        _config.value = EmotionControlConfig()
        _history.value = emptyList()
        _currentEmotion.value = baselineEvaluation
    }
}
