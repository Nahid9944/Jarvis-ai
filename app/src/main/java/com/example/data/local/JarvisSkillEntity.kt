package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

/**
 * Represents a learned or installed skill in the JARVIS Skill Library.
 * Can be acquired through demonstration learning, voice teaching, GitHub import, or manual definition.
 */
@Entity(
    tableName = "jarvis_skills",
    indices = [
        Index(value = ["skillId"], unique = true),
        Index(value = ["targetPackageName"]),
        Index(value = ["category"])
    ]
)
@JsonClass(generateAdapter = true)
data class JarvisSkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val skillId: String, // e.g. "skill_whatsapp_send_report"
    val name: String, // e.g. "WhatsApp Daily Report"
    val nameBengali: String, // e.g. "হোয়াটসঅ্যাপে দৈনিক রিপোর্ট পাঠানো"
    val description: String,
    val triggerPhrasesJson: String = "[]", // JSON array of natural language triggers in BN/EN/Banglish
    val targetPackageName: String = "", // e.g. "com.whatsapp"
    val targetAppName: String = "", // e.g. "WhatsApp"
    val category: String = "CUSTOM", // COMMUNICATION, PRODUCTIVITY, SOCIAL, SHOPPING, MEDIA, SYSTEM_TOOL, CUSTOM
    val riskLevel: String = "MEDIUM", // LOW, MEDIUM, HIGH, CRITICAL
    val requiresConfirmation: Boolean = false,
    val requiredPermissionsJson: String = "[\"ACCESSIBILITY_SERVICE\"]",
    val stepsJson: String = "[]", // Serialized JSON array of structured steps
    val stepCount: Int = 0,
    val sourceType: String = "DEMONSTRATION", // DEMONSTRATION, VOICE_TEACHING, GITHUB_IMPORT, YOUTUBE_TUTORIAL, MANUAL
    val status: String = "ACTIVE", // DRAFT, RECORDED, VERIFIED, ACTIVE, DISABLED
    val version: Int = 1,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastExecutedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val parametersSchemaJson: String = "[]",
    val previousStepsJson: String = "[]",
    val previousVersion: Int = 0
)

/**
 * An individual executable action step within a learned skill.
 */
@Entity(
    tableName = "jarvis_skill_steps",
    indices = [
        Index(value = ["skillId"]),
        Index(value = ["stepIndex"])
    ]
)
@JsonClass(generateAdapter = true)
data class JarvisSkillStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val skillId: String,
    val stepIndex: Int,
    val actionType: String, // CLICK, INPUT_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, OPEN_APP, WAIT_FOR_ELEMENT, BACK, HOME, VERIFY_TEXT
    val targetViewId: String? = null,
    val targetText: String? = null,
    val targetContentDescription: String? = null,
    val targetClassName: String? = null,
    val boundsInScreen: String? = null, // e.g. "left,top,right,bottom"
    val inputPayload: String? = null, // Text entered or parameter placeholder like "{message}"
    val descriptionBengali: String = "",
    val delayAfterMs: Long = 500L,
    val isOptional: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tracks an active or completed demonstration learning session.
 */
@Entity(
    tableName = "jarvis_learning_sessions",
    indices = [
        Index(value = ["sessionId"], unique = true)
    ]
)
@JsonClass(generateAdapter = true)
data class JarvisLearningSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val skillName: String,
    val skillNameBengali: String = "",
    val triggerPhrase: String = "",
    val targetAppPackage: String = "",
    val targetAppName: String = "",
    val mode: String = "DEMONSTRATION", // DEMONSTRATION, VOICE, GITHUB, DOCS
    val status: String = "RECORDING", // RECORDING, PAUSED, COMPLETED, CANCELLED
    val recordedStepCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val notes: String = ""
)
