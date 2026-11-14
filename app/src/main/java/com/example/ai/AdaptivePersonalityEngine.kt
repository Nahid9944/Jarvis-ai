package com.example.ai

/**
 * Adaptive Personality Engine
 * Enforces the deterministic companion response adaptation hierarchy:
 * 1. Explicit Character Selection (highest priority — never silently overridden)
 * 2. Conversation Context (e.g., technical task / system command / casual chat)
 * 3. Emotion Context (adjusts sub-tone/warmth/pacing inside the active character when adaptiveToneEnabled)
 * 4. Default Persona
 */
data class AdaptedCharacterDirective(
    val activeCharacterMode: JarvisPersonalityMode,
    val subToneBn: String,
    val priorityApplied: String,
    val promptBlock: String
)

object AdaptivePersonalityEngine {

    fun computeDirective(
        explicitMode: JarvisPersonalityMode = JarvisPersonalityManager.currentMode.value,
        explicitLocked: Boolean = JarvisPersonalityManager.explicitlySelected.value,
        conversationContextHint: String = "",
        emotionEval: EmotionEvaluation = EmotionContextEngine.currentEmotion.value,
        emotionConfig: EmotionControlConfig = EmotionContextEngine.config.value,
        hasActiveToolTask: Boolean = ActiveTaskInterruptionManager.currentTask.value != null
    ): AdaptedCharacterDirective {
        // Priority 1: Explicit User Selection ALWAYS keeps the selected primary persona intact
        val primaryMode = explicitMode

        // Priority 2: Conversation Context shapes precision/verbosity within the selected character
        val isTaskHeavy = hasActiveToolTask ||
            conversationContextHint.contains("task", ignoreCase = true) ||
            conversationContextHint.contains("tool", ignoreCase = true) ||
            conversationContextHint.contains("অ্যাপ", ignoreCase = true) ||
            conversationContextHint.contains("কল", ignoreCase = true)

        // Priority 3: Emotion Context adjusts sub-tone softly if enabled & reliable
        val useEmotionSubTone = emotionConfig.adaptiveToneEnabled &&
            !emotionEval.isUncertain &&
            emotionEval.confidence >= emotionConfig.confidenceThreshold &&
            emotionEval.emotion != UserEmotion.NEUTRAL &&
            emotionEval.emotion != UserEmotion.UNKNOWN

        val (subToneBn, priorityReason) = when {
            isTaskHeavy -> {
                "কাজের জন্য সুস্পষ্ট, নির্ভরযোগ্য ও সংক্ষিপ্ত (${primaryMode.titleBengali} চরিত্র বজায় রেখে)" to
                    if (explicitLocked) "Priority 1+2 (Explicit Character + Task Context)" else "Priority 2 (Task Context)"
            }
            useEmotionSubTone && emotionEval.emotion in setOf(
                UserEmotion.SAD, UserEmotion.LONELY, UserEmotion.STRESSED, UserEmotion.WORRIED, UserEmotion.TIRED
            ) -> {
                "কোমল, ধৈর্যশীল ও সান্ত্বনাদায়ক (${primaryMode.titleBengali} চরিত্রের ভেতর)" to
                    "Priority 1+3 (Explicit Character + Empathetic Sub-Tone)"
            }
            useEmotionSubTone && emotionEval.emotion in setOf(UserEmotion.HAPPY, UserEmotion.EXCITED) -> {
                "উৎসাহী ও প্রাণবন্ত (${primaryMode.titleBengali} চরিত্রের ভেতর)" to
                    "Priority 1+3 (Explicit Character + Joyful Sub-Tone)"
            }
            emotionEval.isUncertain -> {
                "সংযত ও বিনীতভাবে স্পষ্ট (মিশ্র অনুভূতির সংকেত)" to
                    "Priority 1 (Explicit Character; Uncertain Emotion ignored)"
            }
            else -> {
                "স্বাভাবিক চরিত্রানুগ (${primaryMode.titleBengali})" to
                    if (explicitLocked) "Priority 1 (Explicit Character)" else "Priority 4 (Default)"
            }
        }

        val prompt = """
[ADAPTIVE CHARACTER DIRECTIVE]:
- Active Character (Locked): ${primaryMode.name} (${primaryMode.titleBengali})
- Adaptation Priority: $priorityReason
- Sub-Tone Adjustment: $subToneBn
- Golden Rule: Never abandon your chosen character persona unless the user explicitly asks to switch character.
""".trimIndent()

        return AdaptedCharacterDirective(
            activeCharacterMode = primaryMode,
            subToneBn = subToneBn,
            priorityApplied = priorityReason,
            promptBlock = prompt
        )
    }
}
