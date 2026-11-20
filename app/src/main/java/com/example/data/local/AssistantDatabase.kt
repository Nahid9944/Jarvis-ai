package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        NoteEntity::class,
        JarvisProfileEntity::class,
        AppNotificationEntity::class,
        AiMemoryEntity::class,
        AiTaskEntity::class,
        AppUserEntity::class,
        JarvisChatHistoryEntity::class,
        JarvisUserPreferenceEntity::class,
        JarvisFrequentContactEntity::class,
        JarvisTaskHistoryEntity::class,
        JarvisConversationSnippetEntity::class,
        JarvisSkillEntity::class,
        JarvisSkillStepEntity::class,
        JarvisLearningSessionEntity::class,
        CharacterProfileEntity::class,
        CharacterProfileVersionEntity::class,
        EmotionHistoryEntity::class,
        ActionApprovalAuditEntity::class,
        TrustedLowRiskRuleEntity::class,
        ArtifactEntity::class,
        DevTaskEntity::class,
        DevActivityLogEntity::class,
        DevPatchHistoryEntity::class,
        CapabilityGapEntity::class,
        SkillEvidenceEntity::class,
        KnowledgeCacheEntity::class,
        KnowledgeTelemetryEntity::class
    ],
    version = 22,
    exportSchema = false
)
abstract class AssistantDatabase : RoomDatabase() {
    abstract fun assistantDao(): AssistantDao
    abstract fun jarvisMemoryDao(): JarvisMemoryDao
    abstract fun jarvisSkillDao(): JarvisSkillDao
    abstract fun companionDao(): CompanionDao
    abstract fun homeDeveloperDao(): HomeDeveloperDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile
        private var INSTANCE: AssistantDatabase? = null

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN appName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN sender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN category TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN hasReplyAction INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN isAnnounced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN announcedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jarvis_skills ADD COLUMN parametersSchemaJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE jarvis_skills ADD COLUMN previousStepsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE jarvis_skills ADD COLUMN previousVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS character_profiles (
                        modeId TEXT NOT NULL PRIMARY KEY,
                        profileVersion INTEGER NOT NULL DEFAULT 1,
                        speakingStyleBn TEXT NOT NULL,
                        toneDescriptionBn TEXT NOT NULL,
                        emotionBehaviorBn TEXT NOT NULL,
                        responseLength TEXT NOT NULL,
                        humorLevel INTEGER NOT NULL,
                        formalityLevel INTEGER NOT NULL,
                        affectionLevel INTEGER NOT NULL,
                        memoryPreference TEXT NOT NULL,
                        toolAccessPolicy TEXT NOT NULL,
                        customInstructionsJson TEXT NOT NULL DEFAULT '[]',
                        isActive INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS character_profile_versions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modeId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        feedbackSummary TEXT NOT NULL,
                        customRuleText TEXT NOT NULL,
                        snapshotJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS emotion_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        emotion TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        isUncertain INTEGER NOT NULL DEFAULT 0,
                        contextSummaryBn TEXT NOT NULL,
                        userCorrected INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS action_approval_audits (
                        approvalId TEXT NOT NULL PRIMARY KEY,
                        executionId TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        targetApp TEXT NOT NULL,
                        recipientName TEXT NOT NULL,
                        paramsHash TEXT NOT NULL,
                        messageContentHash TEXT NOT NULL,
                        approvalStatus TEXT NOT NULL,
                        dispatchStatus TEXT NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        confirmationTime INTEGER NOT NULL,
                        expiryTime INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trusted_low_risk_rules (
                        ruleId TEXT NOT NULL PRIMARY KEY,
                        toolName TEXT NOT NULL,
                        scopeDescriptionBn TEXT NOT NULL,
                        grantedAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild app_users cleanly without legacy pharmacyName / pharmacyLocation columns
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_users_new (
                        userId TEXT NOT NULL PRIMARY KEY,
                        profileName TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        userNumber TEXT NOT NULL,
                        isMasterApp INTEGER NOT NULL,
                        deviceModel TEXT NOT NULL,
                        deviceName TEXT NOT NULL,
                        manufacturer TEXT NOT NULL,
                        installationId TEXT NOT NULL,
                        appVersion TEXT NOT NULL,
                        androidVersion TEXT NOT NULL,
                        isOnline INTEGER NOT NULL,
                        lastLoginAt INTEGER NOT NULL,
                        lastLogoutAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        isBlocked INTEGER NOT NULL,
                        blockedBy TEXT NOT NULL,
                        blockedAt INTEGER NOT NULL,
                        locationSharingEnabled INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        accuracy REAL,
                        locationAddress TEXT NOT NULL,
                        locationUpdatedAt INTEGER NOT NULL,
                        stationName TEXT NOT NULL,
                        stationLocation TEXT NOT NULL,
                        photoUri TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO app_users_new (
                        userId, profileName, deviceId, userNumber, isMasterApp,
                        deviceModel, deviceName, manufacturer, installationId, appVersion,
                        androidVersion, isOnline, lastLoginAt, lastLogoutAt, lastSeenAt,
                        isBlocked, blockedBy, blockedAt, locationSharingEnabled,
                        latitude, longitude, accuracy, locationAddress, locationUpdatedAt,
                        stationName, stationLocation, photoUri
                    )
                    SELECT
                        userId, profileName, deviceId, userNumber, isMasterApp,
                        deviceModel, deviceName, manufacturer, installationId, appVersion,
                        androidVersion, isOnline, lastLoginAt, lastLogoutAt, lastSeenAt,
                        isBlocked, blockedBy, blockedAt, locationSharingEnabled,
                        latitude, longitude, accuracy, locationAddress, locationUpdatedAt,
                        CASE WHEN stationName = 'JARVIS CORE' THEN 'NAHID AI CORE' ELSE stationName END,
                        stationLocation, photoUri
                    FROM app_users
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS app_users")
                db.execSQL("ALTER TABLE app_users_new RENAME TO app_users")

                // Normalize legacy branding and strip default static PIN backdoor if unchanged
                db.execSQL(
                    """
                    UPDATE jarvis_profile
                    SET systemName = 'NAHID AI',
                        loginPassword = CASE WHEN loginPassword = '994400' THEN '' ELSE loginPassword END,
                        arcReactorColor = CASE WHEN arcReactorColor = '#00F0FF' THEN '#00F5D4' ELSE arcReactorColor END
                    WHERE systemName LIKE '%PHARMACY%' OR systemName LIKE '%JANANI%' OR systemName = 'NAHID JARVIS AI'
                    """.trimIndent()
                )
                db.execSQL("UPDATE ai_tasks SET actionType = 'REMINDER' WHERE actionType = 'MEDICINE_DOSE'")
            }
        }

        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artifacts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        format TEXT NOT NULL DEFAULT 'MARKDOWN',
                        category TEXT NOT NULL DEFAULT 'DOCUMENT',
                        filePath TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dev_tasks (
                        taskId TEXT NOT NULL PRIMARY KEY,
                        goal TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'FEATURE',
                        priority TEXT NOT NULL DEFAULT 'NORMAL',
                        phase TEXT NOT NULL DEFAULT 'IDLE',
                        status TEXT NOT NULL DEFAULT 'QUEUED',
                        autonomyLevel TEXT NOT NULL DEFAULT 'L2_SAFE_AUTONOMOUS',
                        currentStepIndex INTEGER NOT NULL DEFAULT 0,
                        totalSteps INTEGER NOT NULL DEFAULT 0,
                        completedStepsJson TEXT NOT NULL DEFAULT '[]',
                        pendingStepsJson TEXT NOT NULL DEFAULT '[]',
                        failedStepsJson TEXT NOT NULL DEFAULT '[]',
                        currentFileOrTarget TEXT NOT NULL DEFAULT '',
                        learningSource TEXT NOT NULL DEFAULT '',
                        learningSourceUrl TEXT NOT NULL DEFAULT '',
                        draftSkillId TEXT NOT NULL DEFAULT '',
                        workingArtifactId INTEGER NOT NULL DEFAULT 0,
                        pauseReason TEXT NOT NULL DEFAULT '',
                        lastError TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_tasks_status ON dev_tasks(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_tasks_priority ON dev_tasks(priority)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_tasks_updatedAt ON dev_tasks(updatedAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dev_activity_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId TEXT NOT NULL DEFAULT '',
                        phase TEXT NOT NULL DEFAULT 'IDLE',
                        eventType TEXT NOT NULL DEFAULT 'STATUS',
                        messageBn TEXT NOT NULL,
                        details TEXT NOT NULL DEFAULT '',
                        severity TEXT NOT NULL DEFAULT 'INFO',
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_activity_logs_taskId ON dev_activity_logs(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_activity_logs_timestamp ON dev_activity_logs(timestamp)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dev_patch_history (
                        patchId TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL DEFAULT '',
                        targetFile TEXT NOT NULL,
                        language TEXT NOT NULL DEFAULT 'KOTLIN',
                        summaryBn TEXT NOT NULL,
                        patchContent TEXT NOT NULL,
                        previousSnapshot TEXT NOT NULL DEFAULT '',
                        securityStatus TEXT NOT NULL DEFAULT 'PASS',
                        compileStatus TEXT NOT NULL DEFAULT 'NOT_COMPILED_REQUIRES_BUILD',
                        status TEXT NOT NULL DEFAULT 'DRAFT',
                        artifactId INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_patch_history_taskId ON dev_patch_history(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_patch_history_status ON dev_patch_history(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dev_patch_history_updatedAt ON dev_patch_history(updatedAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS capability_gaps (
                        gapId TEXT NOT NULL PRIMARY KEY,
                        titleBn TEXT NOT NULL,
                        category TEXT NOT NULL,
                        severity TEXT NOT NULL DEFAULT 'MEDIUM',
                        sourceEvidence TEXT NOT NULL,
                        resolutionType TEXT NOT NULL DEFAULT 'SKILL_CREATE',
                        status TEXT NOT NULL DEFAULT 'OPEN',
                        linkedTaskId TEXT NOT NULL DEFAULT '',
                        detectedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_gaps_status ON capability_gaps(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_gaps_severity ON capability_gaps(severity)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skill_evidence (
                        skillId TEXT NOT NULL PRIMARY KEY,
                        sourceType TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL DEFAULT '',
                        learnedAt INTEGER NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        executionCount INTEGER NOT NULL DEFAULT 0,
                        verifiedSuccessCount INTEGER NOT NULL DEFAULT 0,
                        failureCount INTEGER NOT NULL DEFAULT 0,
                        avgExecTimeMs INTEGER NOT NULL DEFAULT 0,
                        lastFailureReason TEXT NOT NULL DEFAULT '',
                        needsReview INTEGER NOT NULL DEFAULT 0,
                        healthScore INTEGER NOT NULL DEFAULT 100
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_evidence_needsReview ON skill_evidence(needsReview)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_evidence_healthScore ON skill_evidence(healthScore)")
            }
        }

        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        normalizedQuery TEXT NOT NULL,
                        topicCategory TEXT NOT NULL DEFAULT 'GENERAL',
                        answerSummaryBn TEXT NOT NULL,
                        claimsJson TEXT NOT NULL DEFAULT '[]',
                        citationsJson TEXT NOT NULL DEFAULT '[]',
                        verificationStatus TEXT NOT NULL DEFAULT 'UNVERIFIED',
                        storageState TEXT NOT NULL DEFAULT 'PENDING',
                        sourceTier TEXT NOT NULL DEFAULT 'L4_WEB',
                        etag TEXT NOT NULL DEFAULT '',
                        lastModified TEXT NOT NULL DEFAULT '',
                        contentHash TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        lastAccessedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_cache_normalizedQuery ON knowledge_cache(normalizedQuery)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_cache_expiresAt ON knowledge_cache(expiresAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_cache_storageState ON knowledge_cache(storageState)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_telemetry (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        query TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        cacheTierReached TEXT NOT NULL,
                        firstResponseMs INTEGER NOT NULL,
                        totalLatencyMs INTEGER NOT NULL,
                        bytesTransferred INTEGER NOT NULL DEFAULT 0,
                        sourceCount INTEGER NOT NULL DEFAULT 0,
                        verifiedClaimCount INTEGER NOT NULL DEFAULT 0,
                        storageState TEXT NOT NULL DEFAULT 'NOT_REQUESTED',
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_telemetry_timestamp ON knowledge_telemetry(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_telemetry_cacheTierReached ON knowledge_telemetry(cacheTierReached)")
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTest() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun getDatabase(context: Context): AssistantDatabase {
            return INSTANCE ?: synchronized(this) {
                // Ensure non-destructive backup + copy from legacy SQLite files before opening Room
                DatabaseMigrationHelper.prepareAndBackupLegacyDatabases(context.applicationContext)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AssistantDatabase::class.java,
                    DatabaseMigrationHelper.NEW_DB_NAME
                )
                .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        val now = System.currentTimeMillis()
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO jarvis_profile (
                                id, systemName, assistantVoice, mobileNumber, location, photoUri,
                                ownerName, loginUserName, loginPassword, arcReactorColor, securityLevel, autoAutomationAllowed
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(1, "NAHID AI", "Bengali-Natural", "", "", null, "Masum Billah Nahid", "Masum Billah Nahid", "", "#00F5D4", "HIGH", 1)
                        )
                        val defaultPrefs = listOf(
                            arrayOf<Any?>("preferred_language", "bn-BD", "VOICE", "ডিফল্ট কথোপকথনের ভাষা (বাংলা)", now),
                            arrayOf<Any?>("assistant_voice", "Bengali-Natural", "VOICE", "ভয়েস সিন্থেসাইজার স্টাইল", now),
                            arrayOf<Any?>("orb_overlay_enabled", "true", "UI", "স্ক্রিন ওএস ফ্লোটিং ওর্ব ডিসপ্লে", now),
                            arrayOf<Any?>("wake_word_enabled", "false", "VOICE", "অফলাইন 'Jarvis' ওয়েক-ওয়ার্ড লিসেনিং", now),
                            arrayOf<Any?>("wake_word_sensitivity", "MEDIUM", "VOICE", "শব্দ সনাক্তকরণ সংবেদনশীলতা", now),
                            arrayOf<Any?>("haptic_feedback_enabled", "true", "GENERAL", "টাচ ও ওয়েক-ওয়ার্ড ভাইব্রেশন ফিডব্যাক", now),
                            arrayOf<Any?>("arc_reactor_color", "#00F5D4", "UI", "নিউরাল কোর নিওন কালার থিম", now)
                        )
                        defaultPrefs.forEach { bindArgs ->
                            db.execSQL(
                                """
                                INSERT OR IGNORE INTO jarvis_user_preferences (key, value, category, description, updatedAt)
                                VALUES (?, ?, ?, ?, ?)
                                """.trimIndent(),
                                bindArgs
                            )
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
