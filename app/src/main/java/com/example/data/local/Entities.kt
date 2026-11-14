package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(tableName = "notes")
@JsonClass(generateAdapter = true)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "jarvis_profile")
@JsonClass(generateAdapter = true)
data class JarvisProfileEntity(
    @PrimaryKey
    val id: Int = 1,
    val systemName: String = "NAHID AI",
    val assistantVoice: String = "Bengali-Natural",
    val mobileNumber: String = "",
    val location: String = "",
    val photoUri: String? = null,
    val ownerName: String = "Masum Billah Nahid",
    val loginUserName: String = "Masum Billah Nahid",
    val loginPassword: String = "",
    val arcReactorColor: String = "#00F5D4",
    val securityLevel: String = "HIGH",
    val autoAutomationAllowed: Boolean = true
)

@Entity(
    tableName = "app_notifications",
    indices = [androidx.room.Index(value = ["eventKey"], unique = true)]
)
@JsonClass(generateAdapter = true)
data class AppNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventKey: String,
    val type: String, // SYSTEM_ALERT, AUTOMATION, REMINDER, SECURITY, TELEMETRY, SECURITY_OTP, INCOMING_NOTIFICATION
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val targetRoute: String,
    val targetId: String = "",
    val targetName: String = "",
    val isRead: Boolean = false,
    val isDeleted: Boolean = false,
    val packageName: String = "",
    val appName: String = "",
    val sender: String = "",
    val category: String = "GENERAL",
    val hasReplyAction: Boolean = false,
    val isAnnounced: Boolean = false,
    val announcedAt: Long = 0L
)

@Entity(tableName = "ai_memories")
@JsonClass(generateAdapter = true)
data class AiMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val key: String,
    val content: String,
    val category: String = "USER_PREFERENCE", // USER_PREFERENCE, AI_BEHAVIOR, SYSTEM_RULE, CUSTOM_NOTE, CONTEXT
    val importance: Int = 1, // 1 normal, 2 medium, 3 high / pinned
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "USER",
    val isPinned: Boolean = false,
    val isActive: Boolean = true
)

@Entity(tableName = "ai_tasks")
@JsonClass(generateAdapter = true)
data class AiTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val scheduledTime: Long = System.currentTimeMillis(),
    val scheduledTimeString: String = "",
    val status: String = "PENDING", // PENDING, COMPLETED, CANCELLED
    val actionType: String = "REMINDER", // REMINDER, APP_COMMAND, SYSTEM_DIAGNOSTIC, PLAYBOOK
    val targetCommand: String = "",
    val repeatInterval: String = "NONE", // NONE, DAILY, WEEKLY
    val priority: String = "NORMAL", // LOW, NORMAL, HIGH, URGENT
    val isExecuted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_users")
@JsonClass(generateAdapter = true)
data class AppUserEntity(
    @PrimaryKey
    val userId: String,
    val profileName: String,
    val deviceId: String,
    val userNumber: String = "",
    val isMasterApp: Boolean = false,
    val deviceModel: String = "",
    val deviceName: String = "",
    val manufacturer: String = "",
    val installationId: String = "",
    val appVersion: String = "3.0",
    val androidVersion: String = "",
    val isOnline: Boolean = false,
    val lastLoginAt: Long = 0L,
    val lastLogoutAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val isBlocked: Boolean = false,
    val blockedBy: String = "",
    val blockedAt: Long = 0L,
    val locationSharingEnabled: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val locationAddress: String = "",
    val locationUpdatedAt: Long = 0L,
    val stationName: String = "NAHID AI CORE",
    val stationLocation: String = "",
    val photoUri: String? = null
)

@Entity(tableName = "jarvis_chat_history")
@JsonClass(generateAdapter = true)
data class JarvisChatHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userPrompt: String,
    val aiResponse: String,
    val timestamp: Long = System.currentTimeMillis(),
    val commandType: String = "VOICE", // VOICE, TEXT, AUTOMATION, LOCATION, SCREEN_VISION
    val status: String = "SUCCESS", // SUCCESS, EXECUTED, ERROR
    val executionDetails: String = ""
)

@Entity(tableName = "jarvis_user_preferences")
@JsonClass(generateAdapter = true)
data class JarvisUserPreferenceEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val category: String = "GENERAL", // GENERAL, VOICE, SECURITY, AUTOMATION, UI
    val description: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "jarvis_frequent_contacts")
@JsonClass(generateAdapter = true)
data class JarvisFrequentContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val alias: String = "", // e.g. "Baba", "Maa", "Office", "Friend", "Doctor"
    val phoneNumber: String,
    val relationship: String = "",
    val interactionCount: Int = 1,
    val hasWhatsApp: Boolean = true,
    val isFavorite: Boolean = false,
    val lastContactedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

@Entity(tableName = "jarvis_task_history")
@JsonClass(generateAdapter = true)
data class JarvisTaskHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskTitle: String,
    val taskCategory: String = "VOICE_COMMAND", // VOICE_COMMAND, AUTOMATION, PLAYBOOK, REMINDER, SYSTEM_ACTION
    val triggerSource: String = "FLOATING_ORB", // WAKE_WORD, FLOATING_ORB, HUD_TAP, BACKGROUND_ALARM
    val rawPrompt: String = "",
    val structuredCommand: String = "",
    val executionStatus: String = "SUCCESS", // SUCCESS, FAILED, CANCELLED, REQUIRES_CONFIRMATION
    val resultSummary: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L
)

@Entity(tableName = "jarvis_conversation_snippets")
@JsonClass(generateAdapter = true)
data class JarvisConversationSnippetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userPrompt: String,
    val aiResponse: String,
    val topic: String = "GENERAL", // AUTOMATION, CODE, PERSONAL_NOTE, TELEPHONY, WEATHER, FACT
    val keywords: String = "",
    val importanceScore: Int = 1, // 1 normal, 2 medium, 3 high / pinned
    val timestamp: Long = System.currentTimeMillis()
)
