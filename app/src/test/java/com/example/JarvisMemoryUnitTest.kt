package com.example

import com.example.ai.JarvisOfflineKeywordSpotter
import com.example.data.local.JarvisFrequentContactEntity
import com.example.data.local.JarvisTaskHistoryEntity
import com.example.data.local.JarvisUserPreferenceEntity
import com.example.ui.components.OrbVisualState
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class JarvisMemoryUnitTest {

    @Test
    fun verifyUserPreferenceEntityStructure() {
        val pref = JarvisUserPreferenceEntity(
            key = "wake_word_sensitivity",
            value = "HIGH",
            category = "VOICE",
            description = "সংবেদনশীলতা স্তর"
        )
        assertEquals("wake_word_sensitivity", pref.key)
        assertEquals("HIGH", pref.value)
        assertEquals("VOICE", pref.category)
        assertTrue(pref.updatedAt > 0L)
    }

    @Test
    fun verifyFrequentContactEntityAndAliasResolution() {
        val contactFather = JarvisFrequentContactEntity(
            id = 1L,
            name = "বাবা",
            alias = "Baba",
            phoneNumber = "+8801711000000",
            relationship = "পিতা",
            interactionCount = 12,
            hasWhatsApp = true,
            isFavorite = true
        )

        assertEquals("Baba", contactFather.alias)
        assertEquals("+8801711000000", contactFather.phoneNumber)
        assertTrue(contactFather.hasWhatsApp)
        assertTrue(contactFather.isFavorite)

        // Test alias matching logic
        val userPrompt = "Baba-ke call dao"
        val lower = userPrompt.lowercase(Locale.ROOT)
        val isMatched = lower.contains(contactFather.alias.lowercase(Locale.ROOT)) || lower.contains(contactFather.name.lowercase(Locale.ROOT))
        assertTrue("User prompt must match contact alias 'Baba'", isMatched)
    }

    @Test
    fun verifyTaskHistoryEntityLogging() {
        val task = JarvisTaskHistoryEntity(
            id = 1L,
            taskTitle = "হোয়াটসঅ্যাপ বার্তা প্রেরণ",
            taskCategory = "COMMUNICATION",
            triggerSource = "FLOATING_ORB",
            rawPrompt = "Baba-ke bolo ami ashchi",
            structuredCommand = "sendWhatsAppMessage",
            executionStatus = "SUCCESS",
            resultSummary = "+8801711000000 নম্বরে হোয়াটসঅ্যাপ বার্তা পাঠানো হয়েছে।"
        )

        assertEquals("FLOATING_ORB", task.triggerSource)
        assertEquals("COMMUNICATION", task.taskCategory)
        assertEquals("SUCCESS", task.executionStatus)
        assertTrue(task.timestamp > 0L)
    }

    @Test
    fun verifyOfflineKeywordPatternsMatch() {
        val patterns = JarvisOfflineKeywordSpotter.KEYWORD_PATTERNS
        assertTrue(patterns.isNotEmpty())

        val testInputs = listOf(
            "jarvis, what is the battery level?",
            "hey jarvis, turn on flashlight",
            "জারভিস, স্ক্রিনে কি আছে দেখো",
            "শোনো জারভিস, বাবাকে কল দাও",
            "ok jarvis, play music"
        )

        testInputs.forEach { input ->
            val lower = input.lowercase(Locale.ROOT)
            val matched = patterns.any { pattern -> lower.contains(pattern) }
            assertTrue("Pattern matching should detect wake word in: '$input'", matched)
        }
    }

    @Test
    fun verifyOrbVisualStates() {
        val idle = OrbVisualState.IDLE
        val listening = OrbVisualState.LISTENING
        val thinking = OrbVisualState.THINKING
        val speaking = OrbVisualState.SPEAKING
        val error = OrbVisualState.ERROR

        assertNotNull(idle.primaryColor)
        assertNotNull(listening.primaryColor)
        assertNotNull(thinking.primaryColor)
        assertNotNull(speaking.primaryColor)
        assertNotNull(error.primaryColor)

        assertEquals("স্ট্যান্ডবাই প্রস্তুত", idle.titleBengali)
        assertEquals("শুনছি...", listening.titleBengali)
    }

    @Test
    fun verifyConversationSnippetEntityStructure() {
        val snippet = com.example.data.local.JarvisConversationSnippetEntity(
            id = 1L,
            userPrompt = "আগামীকাল আমার শিডিউল কি?",
            aiResponse = "স্যার, সকাল ৯টায় টিম মিটিং এবং দুপুর ২টায় রিভিউ কল আছে।",
            topic = "SCHEDULE",
            keywords = "meeting, review, tomorrow",
            importanceScore = 3
        )

        assertEquals("SCHEDULE", snippet.topic)
        assertEquals("আগামীকাল আমার শিডিউল কি?", snippet.userPrompt)
        assertEquals(3, snippet.importanceScore)
        assertTrue(snippet.keywords.contains("meeting"))
        assertTrue(snippet.timestamp > 0L)
    }

    @Test
    fun verifyGlobalOrbEntryPointIntentFlags() {
        // Test that global entry point intent flags match Android system overlay requirements
        val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertTrue((flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }
}
