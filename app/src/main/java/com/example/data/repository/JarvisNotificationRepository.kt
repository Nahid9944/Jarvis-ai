package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.AppNotificationEntity
import com.example.data.local.AssistantDatabase
import com.example.service.EnhancedNotification
import com.example.service.JarvisNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Room-backed notification index and security repository for NAHID JARVIS AI.
 * Persists rich notification metadata in Room so history, search, OTP isolation,
 * and announcement tracking survive app restarts and offline queries.
 */
object JarvisNotificationRepository {

    private const val TAG = "NotificationRepo"
    private const val MAX_STORED = 200
    private val memoryStore = mutableListOf<EnhancedNotification>()

    private val _notificationsFlow = MutableStateFlow<List<EnhancedNotification>>(emptyList())
    val notificationsFlow: StateFlow<List<EnhancedNotification>> = _notificationsFlow.asStateFlow()

    private fun toEntity(notification: EnhancedNotification): AppNotificationEntity {
        val rawBody = notification.text.ifBlank { notification.bigText }
        return AppNotificationEntity(
            eventKey = notification.key,
            type = if (notification.isSensitiveOtp) "SECURITY_OTP" else "INCOMING_NOTIFICATION",
            title = if (notification.title.isNotBlank()) {
                "${notification.appName}: ${notification.title}"
            } else {
                notification.appName
            },
            message = rawBody,
            timestamp = notification.timestamp,
            targetRoute = "notifications",
            targetId = notification.packageName,
            targetName = notification.sender,
            isRead = false,
            isDeleted = false,
            packageName = notification.packageName,
            appName = notification.appName,
            sender = notification.sender,
            category = notification.category,
            hasReplyAction = notification.hasReplyAction,
            isAnnounced = false,
            announcedAt = 0L
        )
    }

    private fun fromEntity(entity: AppNotificationEntity): EnhancedNotification {
        val parsedApp = entity.appName.ifBlank { entity.title.substringBefore(": ").ifBlank { "Notification" } }
        val parsedTitle = if (entity.title.contains(": ")) entity.title.substringAfter(": ") else entity.title
        return EnhancedNotification(
            key = entity.eventKey.ifBlank { "db_${entity.id}" },
            id = entity.id.toInt(),
            packageName = entity.packageName.ifBlank { entity.targetId.ifBlank { "system" } },
            appName = parsedApp,
            sender = entity.sender.ifBlank { entity.targetName.ifBlank { parsedApp } },
            title = parsedTitle,
            text = entity.message,
            bigText = entity.message,
            timestamp = entity.timestamp,
            category = entity.category.ifBlank { "GENERAL" },
            isSensitiveOtp = entity.type == "SECURITY_OTP",
            hasReplyAction = entity.hasReplyAction,
            actionsSummary = if (entity.hasReplyAction) listOf("Reply") else emptyList()
        )
    }

    @Synchronized
    fun addNotification(notification: EnhancedNotification, context: Context? = null) {
        memoryStore.removeAll { it.key == notification.key }
        memoryStore.add(0, notification)
        if (memoryStore.size > MAX_STORED) {
            memoryStore.removeAt(memoryStore.lastIndex)
        }
        _notificationsFlow.value = ArrayList(memoryStore)

        if (context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    persistNotificationSync(context, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist notification ${notification.key}", e)
                }
            }
        }
    }

    suspend fun persistNotificationSync(context: Context, notification: EnhancedNotification) {
        val db = AssistantDatabase.getDatabase(context)
        db.assistantDao().upsertNotification(toEntity(notification))
    }

    /**
     * Loads persisted notification history from Room Database into memory cache on app startup.
     */
    fun loadPersistedHistory(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                getRecentFromDb(context, MAX_STORED)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load persisted notification history", e)
            }
        }
    }

    suspend fun getRecentFromDb(context: Context, limit: Int = 20): List<EnhancedNotification> {
        val db = AssistantDatabase.getDatabase(context)
        val entities = db.assistantDao().getRecentActiveNotificationsSync(limit)
        val loaded = entities.map { fromEntity(it) }
        synchronized(this) {
            if (loaded.isNotEmpty()) {
                val combined = (memoryStore + loaded).distinctBy { it.key }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_STORED)
                memoryStore.clear()
                memoryStore.addAll(combined)
                _notificationsFlow.value = ArrayList(memoryStore)
            }
            return memoryStore.take(limit)
        }
    }

    suspend fun searchNotificationsFromDb(context: Context, query: String, limit: Int = 30): List<EnhancedNotification> {
        val clean = query.trim()
        if (clean.isBlank()) return getRecentFromDb(context, limit)
        val db = AssistantDatabase.getDatabase(context)
        val fromDb = db.assistantDao().searchNotificationsSync(clean, limit).map { fromEntity(it) }
        val fromMem = searchNotifications(clean)
        return (fromMem + fromDb).distinctBy { it.key }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    suspend fun getNotificationsByAppFromDb(context: Context, appFilter: String, limit: Int = 30): List<EnhancedNotification> {
        val clean = appFilter.trim()
        if (clean.isBlank()) return getRecentFromDb(context, limit)
        val db = AssistantDatabase.getDatabase(context)
        val fromDb = db.assistantDao().getNotificationsByAppSync(clean, limit).map { fromEntity(it) }
        val lower = clean.lowercase(Locale.ROOT)
        val fromMem = synchronized(this) {
            memoryStore.filter {
                it.packageName.lowercase(Locale.ROOT).contains(lower) ||
                it.appName.lowercase(Locale.ROOT).contains(lower)
            }
        }
        return (fromMem + fromDb).distinctBy { it.key }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    suspend fun getNotificationsBySenderFromDb(context: Context, senderFilter: String, limit: Int = 30): List<EnhancedNotification> {
        val clean = senderFilter.trim()
        if (clean.isBlank()) return getRecentFromDb(context, limit)
        val db = AssistantDatabase.getDatabase(context)
        val fromDb = db.assistantDao().getNotificationsBySenderSync(clean, limit).map { fromEntity(it) }
        val lower = clean.lowercase(Locale.ROOT)
        val fromMem = synchronized(this) {
            memoryStore.filter {
                it.sender.lowercase(Locale.ROOT).contains(lower) ||
                it.title.lowercase(Locale.ROOT).contains(lower)
            }
        }
        return (fromMem + fromDb).distinctBy { it.key }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    suspend fun getNotificationsSinceFromDb(context: Context, sinceMillis: Long, limit: Int = 50): List<EnhancedNotification> {
        val db = AssistantDatabase.getDatabase(context)
        val fromDb = db.assistantDao().getNotificationsSinceSync(sinceMillis, limit).map { fromEntity(it) }
        val fromMem = synchronized(this) {
            memoryStore.filter { it.timestamp >= sinceMillis }
        }
        return (fromMem + fromDb).distinctBy { it.key }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    suspend fun getOtpsFromDb(context: Context, limit: Int = 20): List<EnhancedNotification> {
        val db = AssistantDatabase.getDatabase(context)
        val fromDb = db.assistantDao().getOtpNotificationsSync(limit).map { fromEntity(it) }
        val fromMem = getOtps()
        return (fromMem + fromDb).distinctBy { it.key }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    suspend fun markNotificationAnnounced(context: Context, eventKey: String) {
        if (eventKey.isBlank()) return
        try {
            AssistantDatabase.getDatabase(context).assistantDao()
                .markNotificationAnnounced(eventKey, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark notification announced: $eventKey", e)
        }
    }

    suspend fun markNotificationRead(context: Context, eventKey: String) {
        if (eventKey.isBlank()) return
        try {
            AssistantDatabase.getDatabase(context).assistantDao()
                .markNotificationReadByKey(eventKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark notification read: $eventKey", e)
        }
    }

    fun formatSafeNotificationText(n: EnhancedNotification): String {
        return if (n.isSensitiveOtp) {
            "[গোপনীয় ওটিপি কোড সুরক্ষিত রয়েছে]"
        } else {
            n.bigText.ifBlank { n.text }
        }
    }

    @Synchronized
    fun getRecent(limit: Int = 20): List<EnhancedNotification> {
        return memoryStore.take(limit)
    }

    @Synchronized
    fun searchNotifications(query: String): List<EnhancedNotification> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return getRecent()
        return memoryStore.filter {
            it.appName.lowercase(Locale.ROOT).contains(q) ||
            it.packageName.lowercase(Locale.ROOT).contains(q) ||
            it.sender.lowercase(Locale.ROOT).contains(q) ||
            it.title.lowercase(Locale.ROOT).contains(q) ||
            it.text.lowercase(Locale.ROOT).contains(q) ||
            it.bigText.lowercase(Locale.ROOT).contains(q)
        }
    }

    @Synchronized
    fun getOtps(): List<EnhancedNotification> {
        return memoryStore.filter { it.isSensitiveOtp }
    }

    @Synchronized
    fun getLatestOtp(): EnhancedNotification? {
        return memoryStore.firstOrNull { it.isSensitiveOtp }
    }

    @Synchronized
    fun clear() {
        memoryStore.clear()
        _notificationsFlow.value = emptyList()
    }

    suspend fun clearAll(context: Context?) {
        clear()
        if (context != null) {
            try {
                AssistantDatabase.getDatabase(context).assistantDao().clearAllNotifications()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear notifications in Room", e)
            }
        }
    }

    /**
     * Sends a direct reply to an intercepted notification using RemoteInput.
     */
    fun sendReply(context: Context, packageFilter: String, sender: String? = null, replyText: String): Pair<Boolean, String> {
        val success = JarvisNotificationListenerService.sendDirectReply(
            context = context,
            packageFilter = packageFilter,
            sender = sender,
            replyText = replyText
        )
        return if (success) {
            Pair(true, "নোটিফিকেশনে উত্তর সফলভাবে পাঠানো হয়েছে: \"$replyText\"")
        } else {
            Pair(false, "উত্তর পাঠানো সম্ভব হয়নি — কোনো অ্যাক্টিভ রিপ্লাই অপশন বা নোটিফিকেশন পাওয়া যায়নি।")
        }
    }
}
