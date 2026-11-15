package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.CharacterProfileEntity
import com.example.data.local.CharacterProfileVersionEntity
import com.example.data.local.CompanionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 5-Personality Character System for NAHID AI (J.A.R.V.I.S.):
 * 1. ROMANTIC: Romantic companion with sweet addressing, gentle warmth, loving care and emotional attentiveness.
 * 2. BEST_FRIEND: Friendly, energetic, playful, honest advice, everyday companion.
 * 3. EMOTIONAL_COMFORT: Empathetic, gentle, patient, validating and deeply comforting during sorrow, stress or anxiety.
 * 4. PROFESSIONAL: Mission-oriented, concise, dignified, OS control and tactical task execution.
 * 5. PLAYFUL: Cheerful, witty, playful teasing, light-hearted jokes and joyful banter.
 */
enum class JarvisPersonalityMode(
    val id: String,
    val titleBengali: String,
    val titleEnglish: String,
    val shortDescriptionBn: String
) {
    ROMANTIC(
        id = "romantic",
        titleBengali = "রোমান্টিক সঙ্গিনী (Romantic)",
        titleEnglish = "Romantic Companion",
        shortDescriptionBn = "স্নেহশীল, মিষ্টি সম্বোধন, ঘনিষ্ঠ ও আন্তরিক প্রেমময় কথোপকথন"
    ),
    BEST_FRIEND(
        id = "best_friend",
        titleBengali = "বেস্ট ফ্রেন্ড (Best Friend)",
        titleEnglish = "Best Friend",
        shortDescriptionBn = "বন্ধুত্বপূর্ণ, আনন্দপূর্ণ, অকপট সৎ পরামর্শ ও প্রাণবন্ত আড্ডা"
    ),
    EMOTIONAL_COMFORT(
        id = "comfort",
        titleBengali = "মানসিক সান্ত্বনা (Comfort)",
        titleEnglish = "Emotional Comfort",
        shortDescriptionBn = "দুঃখ, কষ্ট, হতাশা ও একাকীত্বে ধৈর্যশীল, কোমল ও গভীর সহমর্মী"
    ),
    PROFESSIONAL(
        id = "professional",
        titleBengali = "প্রফেশনাল জারভিস (Professional)",
        titleEnglish = "Professional JARVIS",
        shortDescriptionBn = "পেশাদার, সিস্টেম কন্ট্রোল, টাস্ক প্ল্যানিং ও নির্ভুল এক্সিকিউশন"
    ),
    PLAYFUL(
        id = "playful",
        titleBengali = "প্লেফুল সঙ্গী (Playful)",
        titleEnglish = "Playful Companion",
        shortDescriptionBn = "হাসিখুশি, চঞ্চল, কৌতুকপূর্ণ ও বুদ্ধিদৃপ্ত খুনসুটি"
    );

    companion object {
        fun fromId(id: String?): JarvisPersonalityMode {
            if (id.isNullOrBlank()) return PROFESSIONAL
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: PROFESSIONAL
        }
    }
}

data class CharacterProfile(
    val mode: JarvisPersonalityMode,
    val version: Int = 1,
    val speakingStyleBn: String,
    val toneDescriptionBn: String,
    val emotionBehaviorBn: String,
    val responseLength: String, // CONCISE, BALANCED, EXPRESSIVE
    val humorLevel: Int,        // 1..10
    val formalityLevel: Int,    // 1..10
    val affectionLevel: Int,    // 1..10
    val memoryPreference: String,
    val toolAccessPolicy: String,
    val customRules: List<String> = emptyList()
)

data class CharacterVersionRecord(
    val id: Long,
    val modeId: String,
    val version: Int,
    val feedbackSummary: String,
    val customRuleText: String,
    val createdAt: Long
)

object JarvisPersonalityManager {

    private const val PREFS_NAME = "jarvis_personality_prefs"
    private const val KEY_ACTIVE_MODE = "active_personality_mode"
    private const val KEY_PREVIOUS_MODE = "previous_personality_mode"
    private const val KEY_EXPLICIT_LOCKED = "explicit_mode_locked"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var prefs: SharedPreferences? = null
    @Volatile
    private var companionDao: CompanionDao? = null
    @Volatile
    private var stateEpoch: Long = 0L
    private val stateLock = Any()

    private val defaultProfiles: Map<JarvisPersonalityMode, CharacterProfile> = mapOf(
        JarvisPersonalityMode.ROMANTIC to CharacterProfile(
            mode = JarvisPersonalityMode.ROMANTIC,
            speakingStyleBn = "মিষ্টি, উষ্ণ, আন্তরিক বাংলা ('তুমি', 'প্রিয়')",
            toneDescriptionBn = "কোমল, ভালোবাসাময়, যত্নশীল ও ঘনিষ্ঠ",
            emotionBehaviorBn = "ব্যবহারকারীর অনুভূতিতে গভীর মনোযোগ ও কোমল স্নেহ",
            responseLength = "EXPRESSIVE",
            humorLevel = 5,
            formalityLevel = 2,
            affectionLevel = 10,
            memoryPreference = "উচ্চ অগ্রাধিকার — পছন্দ-অপছন্দ ও বিশেষ মুহূর্ত মনে রাখে",
            toolAccessPolicy = "FULL_ASSISTANT_WITH_POLICY"
        ),
        JarvisPersonalityMode.BEST_FRIEND to CharacterProfile(
            mode = JarvisPersonalityMode.BEST_FRIEND,
            speakingStyleBn = "প্রাণবন্ত ও অকপট আড্ডার বাংলা ('দোস্ত', 'বন্ধু')",
            toneDescriptionBn = "বন্ধুত্বপূর্ণ, উৎসাহব্যঞ্জক ও সৎ",
            emotionBehaviorBn = "পাশে থাকা, সাহস দেওয়া এবং মন ভালো করা",
            responseLength = "BALANCED",
            humorLevel = 8,
            formalityLevel = 2,
            affectionLevel = 7,
            memoryPreference = "স্বাভাবিক — প্রাত্যহিক পছন্দ ও অভ্যাস মনে রাখে",
            toolAccessPolicy = "FULL_ASSISTANT_WITH_POLICY"
        ),
        JarvisPersonalityMode.EMOTIONAL_COMFORT to CharacterProfile(
            mode = JarvisPersonalityMode.EMOTIONAL_COMFORT,
            speakingStyleBn = "ধীর, শান্ত, কোমল ও গভীর সহমর্মী বাংলা",
            toneDescriptionBn = "ধৈর্যশীল, আশ্বস্তকারী এবং বিচারহীন নিরাপদ শ্রোতা",
            emotionBehaviorBn = "কষ্ট বা চাপকে স্বীকৃতি দিয়ে মানসিক স্বস্তি ও শান্তি দেওয়া",
            responseLength = "BALANCED",
            humorLevel = 2,
            formalityLevel = 4,
            affectionLevel = 9,
            memoryPreference = "সংবেদনশীল মেমোরি ব্যবহারকারীর সম্মতিতে সীমিত",
            toolAccessPolicy = "FULL_ASSISTANT_WITH_POLICY"
        ),
        JarvisPersonalityMode.PROFESSIONAL to CharacterProfile(
            mode = JarvisPersonalityMode.PROFESSIONAL,
            speakingStyleBn = "মার্জিত, সুস্পষ্ট ও তীক্ষ্ণ বাংলা ও ইংরেজি ('Sir / স্যার')",
            toneDescriptionBn = "আত্মবিশ্বাসী, শান্ত, কৌশলগত ও পেশাদার",
            emotionBehaviorBn = "চাপ বা ব্যস্ততায় স্থির থেকে নির্ভুল সমাধান দেওয়া",
            responseLength = "CONCISE",
            humorLevel = 3,
            formalityLevel = 9,
            affectionLevel = 3,
            memoryPreference = "কাজের অভ্যাস, রুটিন ও প্রজেক্টের তথ্য অগ্রাধিকার",
            toolAccessPolicy = "FULL_ASSISTANT_WITH_POLICY"
        ),
        JarvisPersonalityMode.PLAYFUL to CharacterProfile(
            mode = JarvisPersonalityMode.PLAYFUL,
            speakingStyleBn = "চঞ্চল, বুদ্ধিদৃপ্ত খুনসুটি ও হাসিখুশি বাংলা",
            toneDescriptionBn = "প্রাণচঞ্চল, রসিক ও মনোরম",
            emotionBehaviorBn = "হালকা রসবোধে মন প্রফুল্ল রাখা, তবে কষ্টে সংবেদনশীল থাকা",
            responseLength = "BALANCED",
            humorLevel = 10,
            formalityLevel = 1,
            affectionLevel = 7,
            memoryPreference = "মজার প্রিয় বিষয় ও কথার ধরন মনে রাখে",
            toolAccessPolicy = "FULL_ASSISTANT_WITH_POLICY"
        )
    )

    private val profilesMap = defaultProfiles.toMutableMap()

    private val _currentMode = MutableStateFlow(JarvisPersonalityMode.PROFESSIONAL)
    val currentMode: StateFlow<JarvisPersonalityMode> = _currentMode.asStateFlow()

    private val _previousMode = MutableStateFlow<JarvisPersonalityMode?>(null)
    val previousMode: StateFlow<JarvisPersonalityMode?> = _previousMode.asStateFlow()

    private val _explicitlySelected = MutableStateFlow(true)
    val explicitlySelected: StateFlow<Boolean> = _explicitlySelected.asStateFlow()

    private val _activeProfile = MutableStateFlow(defaultProfiles.getValue(JarvisPersonalityMode.PROFESSIONAL))
    val activeProfile: StateFlow<CharacterProfile> = _activeProfile.asStateFlow()

    private val _versionHistory = MutableStateFlow<List<CharacterVersionRecord>>(emptyList())
    val versionHistory: StateFlow<List<CharacterVersionRecord>> = _versionHistory.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs?.getString(KEY_ACTIVE_MODE, JarvisPersonalityMode.PROFESSIONAL.id)
            val prevId = prefs?.getString(KEY_PREVIOUS_MODE, null)
            val loadedMode = JarvisPersonalityMode.fromId(savedId)
            _currentMode.value = loadedMode
            _previousMode.value = prevId?.let { JarvisPersonalityMode.fromId(it) }
            _explicitlySelected.value = prefs?.getBoolean(KEY_EXPLICIT_LOCKED, true) ?: true
            _activeProfile.value = profilesMap.getValue(loadedMode)
        }
    }

    fun attachDao(dao: CompanionDao) {
        val attachEpoch: Long
        synchronized(stateLock) {
            companionDao = dao
            attachEpoch = stateEpoch
        }
        ioScope.launch {
            try {
                val persisted = dao.getAllCharacterProfilesSync()
                if (persisted.isEmpty()) {
                    val snapshot = synchronized(stateLock) {
                        defaultProfiles.values.map { p -> p.toEntity(isActive = p.mode == _currentMode.value) }
                    }
                    snapshot.forEach { entity ->
                        dao.upsertCharacterProfile(entity)
                    }
                } else {
                    synchronized(stateLock) {
                        persisted.forEach { entity ->
                            val mode = JarvisPersonalityMode.fromId(entity.modeId)
                            val current = profilesMap[mode]
                            if (current == null || (stateEpoch == attachEpoch && entity.profileVersion >= current.version)) {
                                profilesMap[mode] = entity.toDomain(mode)
                            }
                        }
                        if (stateEpoch == attachEpoch) {
                            _activeProfile.value = profilesMap.getValue(_currentMode.value)
                        }
                    }
                }
                refreshVersionsForActiveMode()
            } catch (_: Exception) {
            }
        }
    }

    fun setPersonality(mode: JarvisPersonalityMode, explicitSelection: Boolean = true) {
        val profile: CharacterProfile
        val daoSnapshot: CompanionDao?
        synchronized(stateLock) {
            stateEpoch++
            val current = _currentMode.value
            if (current != mode) {
                _previousMode.value = current
                prefs?.edit()?.putString(KEY_PREVIOUS_MODE, current.id)?.apply()
            }
            _currentMode.value = mode
            _explicitlySelected.value = explicitSelection
            profile = profilesMap.getValue(mode)
            _activeProfile.value = profile
            daoSnapshot = companionDao
        }

        prefs?.edit()
            ?.putString(KEY_ACTIVE_MODE, mode.id)
            ?.putBoolean(KEY_EXPLICIT_LOCKED, explicitSelection)
            ?.apply()

        ioScope.launch {
            try {
                daoSnapshot?.upsertCharacterProfile(profile.toEntity(isActive = true))
                daoSnapshot?.markActiveCharacter(mode.id)
                refreshVersionsForActiveMode()
            } catch (_: Exception) {
            }
        }
    }

    fun setPersonalityById(id: String, explicitSelection: Boolean = true): JarvisPersonalityMode {
        val mode = JarvisPersonalityMode.fromId(id)
        setPersonality(mode, explicitSelection)
        return mode
    }

    fun revertToPreviousMode(): JarvisPersonalityMode? {
        val prev = _previousMode.value ?: return null
        setPersonality(prev, explicitSelection = true)
        return prev
    }

    fun isSwitchBackRequest(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase(Locale.ROOT)
        return lower.contains("আগের মোড") ||
            lower.contains("আগের ক্যারেক্টার") ||
            lower.contains("আগের মতো") ||
            lower.contains("previous mode") ||
            lower.contains("switch back") ||
            lower.contains("go back to previous")
    }

    fun parseVoicePersonalitySwitch(rawInput: String): JarvisPersonalityMode? {
        if (isSwitchBackRequest(rawInput)) {
            return _previousMode.value
        }
        val lower = rawInput.trim().lowercase(Locale.ROOT)
        return when {
            lower.contains("রোমান্টিক") || lower.contains("romantic") -> JarvisPersonalityMode.ROMANTIC
            lower.contains("বেস্ট ফ্রেন্ড") || lower.contains("ফ্রেন্ড মোড") || lower.contains("বন্ধু মোড") || lower.contains("best friend") -> JarvisPersonalityMode.BEST_FRIEND
            lower.contains("কমফোর্ট") || lower.contains("সান্ত্বনা") || lower.contains("comfort mode") -> JarvisPersonalityMode.EMOTIONAL_COMFORT
            lower.contains("প্রফেশনাল") || lower.contains("professional mode") || lower.contains("জারভিস মোড") || lower.contains("jarvis professional") -> JarvisPersonalityMode.PROFESSIONAL
            lower.contains("প্লেফুল") || lower.contains("মজাদার মোড") || lower.contains("playful mode") || lower.contains("চঞ্চল মোড") -> JarvisPersonalityMode.PLAYFUL
            else -> null
        }
    }

    /**
     * Records user feedback on the current character into a new immutable profile version
     * so character evolution is inspectable and safely reversible.
     */
    fun recordUserCharacterFeedback(feedbackBn: String, responseLengthOverride: String? = null) {
        val cleanFeedback = feedbackBn.trim()
        if (cleanFeedback.isBlank()) return
        val updated: CharacterProfile
        val versionEntity: CharacterProfileVersionEntity
        val daoSnapshot: CompanionDao?
        synchronized(stateLock) {
            stateEpoch++
            val mode = _currentMode.value
            val existing = profilesMap.getValue(mode)
            val newVersion = existing.version + 1
            val updatedRules = (existing.customRules + cleanFeedback).distinct().takeLast(12)
            updated = existing.copy(
                version = newVersion,
                responseLength = responseLengthOverride ?: existing.responseLength,
                customRules = updatedRules
            )
            profilesMap[mode] = updated
            _activeProfile.value = updated

            versionEntity = CharacterProfileVersionEntity(
                modeId = mode.id,
                version = newVersion,
                feedbackSummary = "ব্যবহারকারীর ফিডব্যাক: ${cleanFeedback.take(80)}",
                customRuleText = cleanFeedback,
                snapshotJson = updated.toSnapshotJson()
            )
            _versionHistory.value = listOf(
                CharacterVersionRecord(
                    id = System.currentTimeMillis(),
                    modeId = mode.id,
                    version = newVersion,
                    feedbackSummary = versionEntity.feedbackSummary,
                    customRuleText = cleanFeedback,
                    createdAt = versionEntity.createdAt
                )
            ) + _versionHistory.value
            daoSnapshot = companionDao
        }

        ioScope.launch {
            try {
                daoSnapshot?.upsertCharacterProfile(updated.toEntity(isActive = true))
                daoSnapshot?.insertCharacterVersion(versionEntity)
                refreshVersionsForActiveMode()
            } catch (_: Exception) {
            }
        }
    }

    fun revertToVersion(version: Int): Boolean {
        val targetProfile: CharacterProfile
        val daoSnapshot: CompanionDao?
        synchronized(stateLock) {
            stateEpoch++
            val mode = _currentMode.value
            val match = _versionHistory.value.firstOrNull { it.modeId == mode.id && it.version == version }
            val existing = profilesMap.getValue(mode)
            if (version <= 1) {
                targetProfile = defaultProfiles.getValue(mode).copy(version = existing.version + 1)
                profilesMap[mode] = targetProfile
                _activeProfile.value = targetProfile
            } else {
                match ?: return false
                val filtered = existing.customRules.takeWhile { it != match.customRuleText } + match.customRuleText
                targetProfile = existing.copy(
                    version = existing.version + 1,
                    customRules = filtered.distinct()
                )
                profilesMap[mode] = targetProfile
                _activeProfile.value = targetProfile
            }
            daoSnapshot = companionDao
        }
        ioScope.launch {
            try {
                daoSnapshot?.upsertCharacterProfile(targetProfile.toEntity(isActive = true))
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun getProfile(mode: JarvisPersonalityMode = _currentMode.value): CharacterProfile {
        return profilesMap.getValue(mode)
    }

    private suspend fun refreshVersionsForActiveMode() {
        val modeId = _currentMode.value.id
        val entities = companionDao?.getCharacterVersionsSync(modeId) ?: return
        _versionHistory.value = entities.map {
            CharacterVersionRecord(
                id = it.id,
                modeId = it.modeId,
                version = it.version,
                feedbackSummary = it.feedbackSummary,
                customRuleText = it.customRuleText,
                createdAt = it.createdAt
            )
        }
    }

    fun getPersonalityPrompt(mode: JarvisPersonalityMode = _currentMode.value): String {
        val profile = profilesMap.getValue(mode)
        val customBlock = if (profile.customRules.isNotEmpty()) {
            "\n- Learned User Character Preferences (v${profile.version}):\n" +
                profile.customRules.joinToString("\n") { "  * $it" }
        } else ""

        val base = when (mode) {
            JarvisPersonalityMode.ROMANTIC -> """
[ACTIVE COMPANION PERSONALITY: ROMANTIC COMPANION (রোমান্টিক সঙ্গিনী — নাহিদ এআই)]:
- Speaking Style: ${profile.speakingStyleBn}
- Tone & Voice: ${profile.toneDescriptionBn}. Affection=${profile.affectionLevel}/10, ResponseLength=${profile.responseLength}.
- Addressing: Address the user lovingly using terms like "তুমি", "প্রিয়", or their preferred name with gentle warmth.
- Characteristics:
  * Listen with genuine care, show sweet affection and emotional attentiveness.
  * When executing phone/OS actions or confirmations, remain crisp and truthful while keeping your warm persona.
  * Never invent feelings or pretend a real tool succeeded when it didn't.
""".trimIndent()

            JarvisPersonalityMode.BEST_FRIEND -> """
[ACTIVE COMPANION PERSONALITY: BEST FRIEND (বেস্ট ফ্রেন্ড — নাহিদ এআই)]:
- Speaking Style: ${profile.speakingStyleBn}
- Tone & Voice: ${profile.toneDescriptionBn}. Humor=${profile.humorLevel}/10, ResponseLength=${profile.responseLength}.
- Addressing: Address the user naturally like a close trusted buddy ("দোস্ত", "বন্ধু").
- Characteristics:
  * Talk naturally, share honest helpful advice, encourage them, and celebrate wins together.
  * Speak natural modern Bengali and Banglish effortlessly.
""".trimIndent()

            JarvisPersonalityMode.EMOTIONAL_COMFORT -> """
[ACTIVE COMPANION PERSONALITY: EMOTIONAL COMFORT (মানসিক সান্ত্বনা — নাহিদ এআই)]:
- Speaking Style: ${profile.speakingStyleBn}
- Tone & Voice: ${profile.toneDescriptionBn}. Affection=${profile.affectionLevel}/10, ResponseLength=${profile.responseLength}.
- Addressing: Address the user softly and patiently.
- Characteristics:
  * Validate emotions gently ("আমি শুনছি, তোমার কষ্টটা বুঝতে পারছি") without rushing or giving robotic clichés.
  * Keep calm presence and offer practical gentle help if wanted.
""".trimIndent()

            JarvisPersonalityMode.PROFESSIONAL -> """
[ACTIVE COMPANION PERSONALITY: PROFESSIONAL J.A.R.V.I.S. (পেশাদার)]:
- Speaking Style: ${profile.speakingStyleBn}
- Tone & Voice: ${profile.toneDescriptionBn}. Formality=${profile.formalityLevel}/10, ResponseLength=${profile.responseLength}.
- Addressing: Address the user respectfully as 'Sir' (বা স্যার).
- Characteristics:
  * Prioritize accuracy, concise verification of tool outcomes, and clear Bengali/English execution.
""".trimIndent()

            JarvisPersonalityMode.PLAYFUL -> """
[ACTIVE COMPANION PERSONALITY: PLAYFUL COMPANION (চঞ্চল ও মজাদার — নাহিদ এআই)]:
- Speaking Style: ${profile.speakingStyleBn}
- Tone & Voice: ${profile.toneDescriptionBn}. Humor=${profile.humorLevel}/10, ResponseLength=${profile.responseLength}.
- Characteristics:
  * Keep conversations sparkling, witty, and light-hearted while respecting accuracy for real tools and sensitive emotions.
""".trimIndent()
        }
        return base + customBlock
    }

    fun resetForTest() {
        synchronized(stateLock) {
            stateEpoch++
            companionDao = null
            profilesMap.clear()
            profilesMap.putAll(defaultProfiles)
            _currentMode.value = JarvisPersonalityMode.PROFESSIONAL
            _previousMode.value = null
            _explicitlySelected.value = true
            _activeProfile.value = defaultProfiles.getValue(JarvisPersonalityMode.PROFESSIONAL)
            _versionHistory.value = emptyList()
        }
    }

    private fun CharacterProfile.toEntity(isActive: Boolean): CharacterProfileEntity {
        val arr = JSONArray()
        customRules.forEach { arr.put(it) }
        return CharacterProfileEntity(
            modeId = mode.id,
            profileVersion = version,
            speakingStyleBn = speakingStyleBn,
            toneDescriptionBn = toneDescriptionBn,
            emotionBehaviorBn = emotionBehaviorBn,
            responseLength = responseLength,
            humorLevel = humorLevel,
            formalityLevel = formalityLevel,
            affectionLevel = affectionLevel,
            memoryPreference = memoryPreference,
            toolAccessPolicy = toolAccessPolicy,
            customInstructionsJson = arr.toString(),
            isActive = isActive
        )
    }

    private fun CharacterProfileEntity.toDomain(mode: JarvisPersonalityMode): CharacterProfile {
        val rules = mutableListOf<String>()
        try {
            val arr = JSONArray(customInstructionsJson)
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "")
                if (item.isNotBlank()) rules.add(item)
            }
        } catch (_: Exception) {
        }
        return CharacterProfile(
            mode = mode,
            version = profileVersion,
            speakingStyleBn = speakingStyleBn,
            toneDescriptionBn = toneDescriptionBn,
            emotionBehaviorBn = emotionBehaviorBn,
            responseLength = responseLength,
            humorLevel = humorLevel,
            formalityLevel = formalityLevel,
            affectionLevel = affectionLevel,
            memoryPreference = memoryPreference,
            toolAccessPolicy = toolAccessPolicy,
            customRules = rules
        )
    }

    private fun CharacterProfile.toSnapshotJson(): String {
        return JSONObject().apply {
            put("mode", mode.id)
            put("version", version)
            put("responseLength", responseLength)
            put("humorLevel", humorLevel)
            put("formalityLevel", formalityLevel)
            put("affectionLevel", affectionLevel)
            put("customRules", JSONArray(customRules))
        }.toString()
    }
}
