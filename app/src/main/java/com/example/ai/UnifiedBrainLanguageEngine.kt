package com.example.ai

import java.text.Normalizer
import java.util.Locale

enum class CanonicalIntent {
    CONVERSATIONAL,
    KNOWLEDGE_FAST,
    KNOWLEDGE_DEEP_RESEARCH,
    DEVICE_CONTROL,
    COMMUNICATION_CALL,
    COMMUNICATION_SMS,
    COMMUNICATION_WHATSAPP,
    MEDIA_PLAYBACK,
    NAVIGATION_ROUTE,
    APP_LAUNCH,
    SCREEN_VISION,
    AUTOMATION_UI,
    NOTIFICATION_INTEL,
    MEMORY_MANAGE,
    TASK_SCHEDULE,
    HOME_DEV,
    SECURITY_INTERRUPT,
    CLARIFICATION_NEEDED
}

data class ConversationReferenceContext(
    val lastTopic: String = "",
    val lastContact: String = "",
    val lastApp: String = "",
    val lastLocation: String = "",
    val lastIntent: CanonicalIntent = CanonicalIntent.CONVERSATIONAL,
    val updatedAt: Long = System.currentTimeMillis()
)

data class StructuredUtterance(
    val rawText: String,
    val normalizedText: String,
    val detectedLanguage: String, // "bn", "banglish", "en", "mixed"
    val primaryIntent: CanonicalIntent,
    val subIntents: List<CanonicalIntent> = emptyList(),
    val isCompound: Boolean = false,
    val compoundSegments: List<String> = emptyList(),
    val resolvedQuery: String,
    val entities: Map<String, String> = emptyMap(),
    val requiresToolExecution: Boolean = false,
    val antiOpenAppHijackApplied: Boolean = false,
    val resolvedFollowUpContext: String? = null,
    val confidence: Float = 0.8f
)

/**
 * Unified Central Language, Intent, Banglish/Bengali Normalization & Multi-Turn Context Engine
 * operating inside JARVIS_MASTER Brain.
 */
object UnifiedBrainLanguageEngine {

    private val APP_NAMES_SET = setOf(
        "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ",
        "facebook", "ফেসবুক", "fb",
        "youtube", "ইউটিউব",
        "chrome", "ক্রোম",
        "instagram", "ইনস্টাগ্রাম",
        "telegram", "টেলিগ্রাম",
        "gmail", "জিমেইল",
        "messenger", "মেসেঞ্জার",
        "maps", "ম্যাপস",
        "settings", "সেটিংস"
    )

    private val PRONOUN_MARKERS = listOf(
        "তার ", "তাকে ", "ওকে ", "ওর ", "উনাকে ", "তাদের ",
        "সেটা", "এটা", "ওটা", "বিষয়টা", "ব্যাপারটা", "ওখানে", "সেখানে",
        "call him", "call her", "message him", "message her", "tell him", "tell her",
        "more about it", "about that", "about this", "verify it", "verify this",
        "আরও বলো", "বিস্তারিত বলো", "সূত্র দাও", "ক্রস চেক করো", "আবার দেখাও"
    )

    private val BANGLISH_SYNONYMS: List<Pair<Regex, String>> = listOf(
        Regex("""\b(abohawa|abhawa|abohaoya)\b""", RegexOption.IGNORE_CASE) to "weather",
        Regex("""\b(ajker|ajke)\b""", RegexOption.IGNORE_CASE) to "আজকের",
        Regex("""\b(khobor|khabar|songbad)\b""", RegexOption.IGNORE_CASE) to "news",
        Regex("""\b(koll\s*dao|call\s*dao|phone\s*dao|phone\s*koro|call\s*koro)\b""", RegexOption.IGNORE_CASE) to "call",
        Regex("""\b(torch\s*jalao|torch\s*on\s*koro|flashlight\s*jalao)\b""", RegexOption.IGNORE_CASE) to "flashlight on",
        Regex("""\b(torch\s*bondho|flashlight\s*bondho|torch\s*off\s*koro)\b""", RegexOption.IGNORE_CASE) to "flashlight off",
        Regex("""\b(volume\s*barao|awaj\s*barao)\b""", RegexOption.IGNORE_CASE) to "volume up",
        Regex("""\b(volume\s*komao|awaj\s*komao)\b""", RegexOption.IGNORE_CASE) to "volume down",
        Regex("""\b(brightness\s*barao)\b""", RegexOption.IGNORE_CASE) to "brightness up",
        Regex("""\b(brightness\s*komao)\b""", RegexOption.IGNORE_CASE) to "brightness down",
        Regex("""\b(battery\s*koto|charge\s*koto)\b""", RegexOption.IGNORE_CASE) to "battery level",
        Regex("""\b(kivabe|kemne)\b""", RegexOption.IGNORE_CASE) to "কিভাবে",
        Regex("""\b(keno)\b""", RegexOption.IGNORE_CASE) to "কেন",
        Regex("""\b(kothay)\b""", RegexOption.IGNORE_CASE) to "কোথায়",
        Regex("""\b(koto)\b""", RegexOption.IGNORE_CASE) to "কত",
        Regex("""\b(khulo|kholo|open\s*koro|chalu\s*koro)\b""", RegexOption.IGNORE_CASE) to "খোলো",
        Regex("""\b(bondho\s*koro|close\s*koro)\b""", RegexOption.IGNORE_CASE) to "বন্ধ করো",
        Regex("""\b(niche\s*scroll|scroll\s*niche)\b""", RegexOption.IGNORE_CASE) to "scroll down",
        Regex("""\b(upore\s*scroll|scroll\s*upore)\b""", RegexOption.IGNORE_CASE) to "scroll up"
    )

    @Volatile
    private var contextState: ConversationReferenceContext = ConversationReferenceContext()

    fun getCurrentReferenceContext(): ConversationReferenceContext = contextState

    fun resetForTest() {
        contextState = ConversationReferenceContext()
    }

    fun recordTurnContext(
        topic: String? = null,
        contact: String? = null,
        app: String? = null,
        location: String? = null,
        intent: CanonicalIntent? = null
    ) {
        val prev = contextState
        contextState = ConversationReferenceContext(
            lastTopic = topic?.trim()?.takeIf { it.isNotBlank() } ?: prev.lastTopic,
            lastContact = contact?.trim()?.takeIf { it.isNotBlank() } ?: prev.lastContact,
            lastApp = app?.trim()?.takeIf { it.isNotBlank() } ?: prev.lastApp,
            lastLocation = location?.trim()?.takeIf { it.isNotBlank() } ?: prev.lastLocation,
            lastIntent = intent ?: prev.lastIntent,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun detectLanguageScript(text: String): String {
        val hasBengali = text.any { it in '\u0980'..'\u09FF' }
        val hasLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            hasBengali && hasLatin -> "mixed"
            hasBengali -> "bn"
            hasLatin -> {
                val lower = text.lowercase(Locale.ROOT)
                val banglishMarkers = listOf("dao", "koro", "bolo", "koto", "kivabe", "keno", "amake", "amar", "tumi", "baba", "maa", "abohawa", "khobor", "khulo")
                if (banglishMarkers.any { Regex("""\b$it\b""").containsMatchIn(lower) }) "banglish" else "en"
            }
            else -> "bn"
        }
    }

    /**
     * Normalizes Bengali Unicode + Banglish keywords without mangling quoted payloads,
     * contact names, or case-sensitive URLs.
     */
    fun normalizePreservingEntities(rawText: String): String {
        val nfc = Normalizer.normalize(rawText.trim(), Normalizer.Form.NFC)
            .replace(Regex("""\s+"""), " ")
            .replace("হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ")
            .replace("কোথায়", "কোথায়")
            .trim()
        if (nfc.isBlank()) return ""

        // Do not alter text inside quotes
        val quoteSplit = Regex(""""([^"]*)"""")
        val quotedSegments = mutableListOf<String>()
        var masked = quoteSplit.replace(nfc) { mr ->
            val idx = quotedSegments.size
            quotedSegments.add(mr.value)
            "__QUOTED_${idx}__"
        }

        for ((pattern, replacement) in BANGLISH_SYNONYMS) {
            masked = pattern.replace(masked, replacement)
        }

        quotedSegments.forEachIndexed { idx, original ->
            masked = masked.replace("__QUOTED_${idx}__", original)
        }
        return masked.trim()
    }

    /**
     * Prevents OpenApp from hijacking informational, message-meaning, security, or explanation queries
     * that merely mention an app name like "WhatsApp" or "Facebook".
     */
    fun isQuestionOrAnalysisAboutApp(text: String): Boolean {
        val lower = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()
        val mentionsApp = APP_NAMES_SET.any { lower.contains(it) }
        if (!mentionsApp) return false

        val hasExplicitOpenVerb = Regex(
            """\b(?:open|launch|start|switch\s+to)\b|(?:খোলো|খুলুন|ওপেন\s*করো|চালু\s*করো)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)

        val isQuestionOrAnalysis = lower.contains("?") ||
            lower.contains("কী") || lower.contains("কিভাবে") || lower.contains("কীভাবে") ||
            lower.contains("কেন") || lower.contains("মানে") || lower.contains("সিকিউরিটি") ||
            lower.contains("নিরাপত্তা") || lower.contains("সমস্যা") || lower.contains("ডাউন") ||
            lower.contains("কি হয়ে") || lower.contains("কাজ করে") || lower.contains("কোন মেসেজ") ||
            lower.contains("শেষ মেসেজ") || lower.contains("বিশ্লেষণ") || lower.contains("ব্যাখ্যা") ||
            Regex("""\b(?:how|why|what|meaning|security|safe|privacy|down|error|work|last\s+message|explain|compare)\b""").containsMatchIn(lower)

        return isQuestionOrAnalysis && !hasExplicitOpenVerb
    }

    /**
     * Splits compound multi-step commands without breaking single-intent message payloads.
     */
    fun splitCompoundCommands(text: String): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        // If utterance is a single direct message/note payload ("বলো যে...", "লিখো যে..."), avoid splitting inside the payload
        val lower = clean.lowercase(Locale.ROOT)
        if ((lower.contains("বলো যে") || lower.contains("মেসেজ পাঠাও") || lower.contains("message saying")) &&
            !lower.contains("তারপর") && !lower.contains("and then")
        ) {
            return listOf(clean)
        }

        val splitRegex = Regex("""\s*(?:;|তারপর|এরপর|এবং তারপর|and then|then)\s*""", RegexOption.IGNORE_CASE)
        val rawParts = clean
            .replace(Regex("""^(আগে|প্রথমে)\s+""", RegexOption.IGNORE_CASE), "")
            .split(splitRegex)
            .map { it.trim().trim(',') }
            .filter { it.isNotBlank() }

        return if (rawParts.size > 1) rawParts.take(6) else listOf(clean)
    }

    /**
     * Resolves anaphoric pronouns & follow-up references using latest conversation context.
     */
    fun resolveFollowUpReference(normalized: String, snapshot: ConversationReferenceContext = contextState): Pair<String, String?> {
        val lower = normalized.lowercase(Locale.ROOT)
        val hasPronoun = PRONOUN_MARKERS.any { lower.contains(it.trim()) }
        if (!hasPronoun) return Pair(normalized, null)

        var resolved = normalized
        val appliedNotes = mutableListOf<String>()

        if (snapshot.lastContact.isNotBlank()) {
            val contactPronoun = Regex("""(?:তার কাছে|তাকে|ওকে|উনাকে|\bhim\b|\bher\b)""", RegexOption.IGNORE_CASE)
            if (contactPronoun.containsMatchIn(resolved)) {
                resolved = contactPronoun.replace(resolved, snapshot.lastContact)
                appliedNotes.add("contact=${snapshot.lastContact}")
            }
        }
        if (snapshot.lastLocation.isNotBlank()) {
            val locPronoun = Regex("""(?:ওখানে|সেখানে|\bthere\b)""", RegexOption.IGNORE_CASE)
            if (locPronoun.containsMatchIn(resolved)) {
                resolved = locPronoun.replace(resolved, snapshot.lastLocation)
                appliedNotes.add("location=${snapshot.lastLocation}")
            }
        }
        if (snapshot.lastTopic.isNotBlank()) {
            val topicFollowUp = listOf("আরও বলো", "বিস্তারিত বলো", "সূত্র দাও", "ক্রস চেক", "verify this", "verify it", "more about it", "about that", "সেটা", "ওটা", "বিষয়টা")
            if (topicFollowUp.any { lower.contains(it) }) {
                resolved = "${snapshot.lastTopic} — $resolved"
                appliedNotes.add("topic=${snapshot.lastTopic}")
            }
        }

        return Pair(
            resolved.trim(),
            if (appliedNotes.isNotEmpty()) appliedNotes.joinToString(", ") else null
        )
    }

    fun analyze(rawText: String, updateReferenceContext: Boolean = true): StructuredUtterance {
        val cleanRaw = rawText.trim()
        val lang = detectLanguageScript(cleanRaw)
        val normalized = normalizePreservingEntities(cleanRaw)
        val (resolved, followUpNote) = resolveFollowUpReference(normalized)
        val antiHijack = isQuestionOrAnalysisAboutApp(resolved)
        val segments = splitCompoundCommands(resolved)
        val isCompound = segments.size > 1

        val entities = mutableMapOf<String, String>()
        APP_NAMES_SET.firstOrNull { resolved.lowercase(Locale.ROOT).contains(it) }?.let {
            entities["app"] = it
        }

        val subIntents = segments.map { classifySingleIntent(it, antiHijack) }.distinct()
        val primaryIntent = subIntents.firstOrNull() ?: CanonicalIntent.CONVERSATIONAL

        val requiresTool = primaryIntent !in setOf(CanonicalIntent.CONVERSATIONAL, CanonicalIntent.CLARIFICATION_NEEDED)

        if (updateReferenceContext && cleanRaw.isNotBlank()) {
            val extractedLocation = Regex("""(ঢাকা|ধাকা|চট্টগ্রাম|খুলনা|সিলেট|রাজশাহী|মোরেলগঞ্জ|dhaka|tokyo|london|chattogram|khulna)""", RegexOption.IGNORE_CASE)
                .find(resolved)?.value
            val extractedTopic = if (primaryIntent in setOf(CanonicalIntent.KNOWLEDGE_FAST, CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH)) {
                resolved.take(120)
            } else null
            recordTurnContext(
                topic = extractedTopic,
                app = entities["app"],
                location = extractedLocation,
                intent = primaryIntent
            )
        }

        return StructuredUtterance(
            rawText = cleanRaw,
            normalizedText = normalized,
            detectedLanguage = lang,
            primaryIntent = primaryIntent,
            subIntents = subIntents,
            isCompound = isCompound,
            compoundSegments = segments,
            resolvedQuery = resolved,
            entities = entities,
            requiresToolExecution = requiresTool,
            antiOpenAppHijackApplied = antiHijack,
            resolvedFollowUpContext = followUpNote,
            confidence = if (primaryIntent == CanonicalIntent.CONVERSATIONAL) 0.72f else 0.90f
        )
    }

    fun classifySingleIntent(segment: String, antiOpenAppHijack: Boolean = false): CanonicalIntent {
        val lower = segment.lowercase(Locale.ROOT).trim()
        if (lower.isBlank()) return CanonicalIntent.CLARIFICATION_NEEDED

        if (ActiveTaskInterruptionManager.classifyInterruptionIntent(segment) != InterruptionIntent.UNKNOWN_INTERRUPTION &&
            (lower.contains("স্টপ") || lower.contains("থামাও") || lower.contains("stop") || lower.contains("cancel") || lower.contains("বাতিল"))
        ) {
            return CanonicalIntent.SECURITY_INTERRUPT
        }

        if (antiOpenAppHijack) {
            return when {
                lower.contains("মেসেজ") || lower.contains("message") || lower.contains("নোটিফিকেশন") || lower.contains("notification") ->
                    CanonicalIntent.NOTIFICATION_INTEL
                lower.contains("ক্রস") || lower.contains("যাচাই") || lower.contains("verify") || lower.contains("রিসার্চ") || lower.contains("research") ->
                    CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH
                else -> CanonicalIntent.KNOWLEDGE_FAST
            }
        }

        if (lower.contains("home developer") || lower.contains("হোম ডেভেলপার") ||
            lower.contains("সেল্ফ রিপেয়ার") || lower.contains("সেল্ফ ডেভেলপমেন্ট") ||
            lower.contains("ক্যাপাবিলিটি গ্যাপ")
        ) {
            return CanonicalIntent.HOME_DEV
        }

        if (lower.contains("গভীর গবেষণা") || lower.contains("ডিপ রিসার্চ") || lower.contains("deep research") ||
            lower.contains("ক্রস চেক") || lower.contains("ক্রস-চেক") || lower.contains("cross check") || lower.contains("cross-check") ||
            lower.contains("প্রমাণসহ") || lower.contains("উৎস যাচাই") || lower.contains("তুলনামূলক গবেষণা") ||
            lower.contains("সর্বশেষ") || lower.contains("আজকের খবর") ||
            lower.contains("আজকের news") || lower.contains("today news") ||
            lower.contains("today's news") || lower.contains("breaking")
        ) {
            return CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH
        }

        if (lower.contains("নোটিফিকেশন") || lower.contains("notification") || lower.contains("ওটিপি") || lower.contains("otp")) {
            return CanonicalIntent.NOTIFICATION_INTEL
        }

        if (lower.contains("স্ক্রিনে কী") || lower.contains("স্ক্রিন দেখ") || lower.contains("ক্যামেরা") || lower.contains("camera") ||
            lower.contains("ছবিটা") || lower.contains("analyze screen") ||
            lower.contains("screen e ki") || lower.contains("on my screen") ||
            lower.contains("chobita") || lower.contains("chobi dekhe")
        ) {
            return CanonicalIntent.SCREEN_VISION
        }

        if (lower.contains("ক্লিক") || lower.contains("স্ক্রল") || lower.contains("টাইপ") || lower.contains("click") ||
            lower.contains("scroll") || lower.contains("tap") || lower.contains("swipe")
        ) {
            return CanonicalIntent.AUTOMATION_UI
        }

        if (lower.contains("হোয়াটসঅ্যাপ") || lower.contains("whatsapp")) {
            return CanonicalIntent.COMMUNICATION_WHATSAPP
        }
        if (lower.contains("এসএমএস") || lower.contains("sms")) {
            return CanonicalIntent.COMMUNICATION_SMS
        }
        if (lower.contains("কল") || lower.contains("ফোন") || lower.contains("call") || lower.contains("dial")) {
            return CanonicalIntent.COMMUNICATION_CALL
        }

        if (lower.contains("ইউটিউব") || lower.contains("youtube") || lower.contains("গান") || lower.contains("ভিডিও")) {
            return CanonicalIntent.MEDIA_PLAYBACK
        }

        if (lower.contains("দূরত্ব") || lower.contains("যাওয়ার পথ") || lower.contains("লোকেশন") ||
            lower.contains("কোথায় আছি") || lower.contains("navigate") || lower.contains("route")
        ) {
            return CanonicalIntent.NAVIGATION_ROUTE
        }

        if (lower.contains("মনে রাখ") || lower.contains("মেমোরি") || lower.contains("নোট") ||
            lower.contains("memory") || lower.contains("note") ||
            lower.contains("mone rakho") || lower.contains("remember ")
        ) {
            return CanonicalIntent.MEMORY_MANAGE
        }

        if (lower.contains("অ্যালার্ম") || lower.contains("রিমাইন্ডার") || lower.contains("রুটিন") || lower.contains("ব্রিফিং") ||
            lower.contains("মনে করিয়ে") || lower.contains("alarm") || lower.contains("reminder") || lower.contains("briefing")
        ) {
            return CanonicalIntent.TASK_SCHEDULE
        }

        if (lower.contains("টর্চ") || lower.contains("ফ্ল্যাশলাইট") || lower.contains("ব্যাটারি") || lower.contains("ভলিউম") ||
            lower.contains("ব্রাইটনেস") || lower.contains("flashlight") || lower.contains("battery") ||
            lower.contains("volume") || lower.contains("brightness") || lower.contains("silent") ||
            lower.contains("সিকিউরিটি অডিট") || lower.contains("পারমিশন") || lower.contains("security audit")
        ) {
            return CanonicalIntent.DEVICE_CONTROL
        }

        val hasOpenVerb = Regex("""\b(?:open|launch|start)\b|(?:খোলো|খুলুন|ওপেন|চালু)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        if (hasOpenVerb && APP_NAMES_SET.any { lower.contains(it) }) {
            return CanonicalIntent.APP_LAUNCH
        }

        val hasBengaliInterrogativeKi = Regex("""(?:^|\s)কি(?:\s|$|\?|।)""").containsMatchIn(lower)
        val isKnowledgeQuestion = lower.contains("?") ||
            lower.contains("কী") || hasBengaliInterrogativeKi || lower.contains("কেন") || lower.contains("কিভাবে") ||
            lower.contains("কবে") || lower.contains("কে") || lower.contains("কোথায়") || lower.contains("কত") ||
            lower.contains("আবহাওয়া") || lower.contains("weather") || lower.contains("news") || lower.contains("খবর") ||
            lower.contains("wikipedia") || lower.contains("উইকিপিডিয়া") || lower.contains("জানাও") || lower.contains("সার্চ") ||
            Regex("""\b(?:what|who|when|where|why|how|explain|tell\s+me|search|find|define|capital|price|rate|law|rule)\b""").containsMatchIn(lower)

        if (isKnowledgeQuestion) {
            return CanonicalIntent.KNOWLEDGE_FAST
        }

        return CanonicalIntent.CONVERSATIONAL
    }
}
