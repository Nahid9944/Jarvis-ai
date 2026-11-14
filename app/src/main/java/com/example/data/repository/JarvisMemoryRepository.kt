package com.example.data.repository

import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class JarvisMemoryRepository(
    private val dao: AssistantDao,
    private val memoryDao: JarvisMemoryDao? = null
) {

    // -------------------------------------------------------------
    // Conversation Snippets (Long-Term Context Memory)
    // -------------------------------------------------------------
    suspend fun recordConversationSnippet(
        userPrompt: String,
        aiResponse: String,
        topic: String = "GENERAL",
        keywords: String = "",
        importanceScore: Int = 1
    ): Long = withContext(Dispatchers.IO) {
        val snippet = JarvisConversationSnippetEntity(
            userPrompt = userPrompt.trim(),
            aiResponse = aiResponse.trim(),
            topic = topic,
            keywords = keywords.trim(),
            importanceScore = importanceScore,
            timestamp = System.currentTimeMillis()
        )
        memoryDao?.insertSnippet(snippet) ?: -1L
    }

    suspend fun getRecentSnippets(limit: Int = 5): List<JarvisConversationSnippetEntity> = withContext(Dispatchers.IO) {
        memoryDao?.getRecentSnippetsSync(limit) ?: emptyList()
    }

    // -------------------------------------------------------------
    // User Preferences
    // -------------------------------------------------------------
    val allPreferences: Flow<List<JarvisUserPreferenceEntity>> = dao.getAllPreferences()

    suspend fun getPreference(key: String, defaultValue: String = ""): String = withContext(Dispatchers.IO) {
        dao.getPreferenceSync(key)?.value ?: defaultValue
    }

    suspend fun savePreference(key: String, value: String, category: String = "GENERAL", desc: String = "") = withContext(Dispatchers.IO) {
        dao.insertPreference(
            JarvisUserPreferenceEntity(
                key = key,
                value = value,
                category = category,
                description = desc,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removePreference(key: String) = withContext(Dispatchers.IO) {
        dao.deletePreference(key)
    }

    // -------------------------------------------------------------
    // Frequent Contacts & Aliases
    // -------------------------------------------------------------
    val allFrequentContacts: Flow<List<JarvisFrequentContactEntity>> = dao.getAllFrequentContacts()
    val topFrequentContacts: Flow<List<JarvisFrequentContactEntity>> = dao.getTopFrequentContacts(8)

    suspend fun findContactByAliasOrName(query: String): JarvisFrequentContactEntity? = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext null
        dao.getFrequentContactByAliasOrNameSync(clean)
    }

    suspend fun saveContact(
        name: String,
        alias: String = "",
        phoneNumber: String,
        relationship: String = "",
        hasWhatsApp: Boolean = true,
        isFavorite: Boolean = false,
        notes: String = ""
    ): Long = withContext(Dispatchers.IO) {
        dao.insertFrequentContact(
            JarvisFrequentContactEntity(
                name = name.trim(),
                alias = alias.trim(),
                phoneNumber = phoneNumber.trim(),
                relationship = relationship.trim(),
                hasWhatsApp = hasWhatsApp,
                isFavorite = isFavorite,
                notes = notes.trim()
            )
        )
    }

    suspend fun recordContactInteraction(contactId: Long) = withContext(Dispatchers.IO) {
        dao.incrementContactInteraction(contactId)
    }

    suspend fun deleteContact(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteFrequentContact(id)
    }

    // -------------------------------------------------------------
    // Task & Context Interaction History
    // -------------------------------------------------------------
    val recentTaskHistory: Flow<List<JarvisTaskHistoryEntity>> = dao.getRecentTaskHistory(30)

    suspend fun recordTaskExecution(
        taskTitle: String,
        taskCategory: String = "VOICE_COMMAND",
        triggerSource: String = "FLOATING_ORB",
        rawPrompt: String = "",
        structuredCommand: String = "",
        executionStatus: String = "SUCCESS",
        resultSummary: String = "",
        durationMs: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        dao.insertTaskHistory(
            JarvisTaskHistoryEntity(
                taskTitle = taskTitle,
                taskCategory = taskCategory,
                triggerSource = triggerSource,
                rawPrompt = rawPrompt,
                structuredCommand = structuredCommand,
                executionStatus = executionStatus,
                resultSummary = resultSummary,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearAllTaskHistory() = withContext(Dispatchers.IO) {
        dao.clearTaskHistory()
    }

    // -------------------------------------------------------------
    // Context Enrichment Engine for Context-Aware AI Interactions
    // -------------------------------------------------------------
    suspend fun getEnrichedContextForAi(userQuery: String = ""): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("=== J.A.R.V.I.S. PERSISTENT CONTEXT & MEMORY MATRIX ===\n")

        // 1. User Preferences
        val prefs = dao.getAllPreferencesSync()
        if (prefs.isNotEmpty()) {
            sb.append("\n[USER PREFERENCES]:\n")
            prefs.take(6).forEach { p ->
                sb.append("• ${p.key}: ${p.value}\n")
            }
        }

        // 2. Matching Contacts / Family Aliases
        val cleanQuery = userQuery.lowercase(Locale.ROOT)
        val contacts = dao.getAllFrequentContactsSync()
        val matchedContacts = contacts.filter { c ->
            cleanQuery.contains(c.name.lowercase(Locale.ROOT)) ||
            (c.alias.isNotBlank() && cleanQuery.contains(c.alias.lowercase(Locale.ROOT))) ||
            (c.relationship.isNotBlank() && cleanQuery.contains(c.relationship.lowercase(Locale.ROOT)))
        }

        if (matchedContacts.isNotEmpty()) {
            sb.append("\n[RESOLVED CONTACT ALIASES]:\n")
            matchedContacts.forEach { mc ->
                sb.append("• ${mc.name} (Alias: '${mc.alias}', Rel: '${mc.relationship}'): ${mc.phoneNumber} [WhatsApp: ${mc.hasWhatsApp}]\n")
            }
        } else if (contacts.isNotEmpty()) {
            sb.append("\n[FAVORITE FREQUENT CONTACTS]:\n")
            contacts.take(3).forEach { c ->
                sb.append("• ${c.name} (${c.alias}): ${c.phoneNumber}\n")
            }
        }

        // 3. Recent 3 Tasks for Immediate Interaction Context
        val recentTasks = dao.getRecentTaskHistorySync(3)
        if (recentTasks.isNotEmpty()) {
            sb.append("\n[RECENT TASK HISTORY]:\n")
            recentTasks.forEach { t ->
                sb.append("• [${t.executionStatus}] ${t.taskTitle} (Trigger: ${t.triggerSource}) -> ${t.resultSummary}\n")
            }
        }

        // 4. Stored Long-Term AI Memories
        val memories = dao.getAllActiveMemoriesSync()
        if (memories.isNotEmpty()) {
            sb.append("\n[LONG-TERM KNOWLEDGE & USER FACTS]:\n")
            memories.take(5).forEach { m ->
                sb.append("• [${m.category}] ${m.key}: ${m.content}\n")
            }
        }

        // 5. Recent Conversation Snippets
        val snippets = memoryDao?.getRecentSnippetsSync(3) ?: emptyList()
        if (snippets.isNotEmpty()) {
            sb.append("\n[RECENT CONVERSATION SNIPPETS]:\n")
            snippets.forEach { s ->
                sb.append("• User: \"${s.userPrompt}\" -> Jarvis: \"${s.aiResponse.take(70)}...\"\n")
            }
        }

        sb.append("=======================================================\n")
        sb.toString()
    }
}
