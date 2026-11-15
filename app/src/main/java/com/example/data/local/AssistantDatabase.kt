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
        ArtifactEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AssistantDatabase : RoomDatabase() {
    abstract fun assistantDao(): AssistantDao
    abstract fun jarvisMemoryDao(): JarvisMemoryDao
    abstract fun jarvisSkillDao(): JarvisSkillDao
    abstract fun companionDao(): CompanionDao

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
                .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getDatabase(context).assistantDao()
                            // Seed Owner Master Profile
                            dao.insertProfile(
                                JarvisProfileEntity(
                                    id = 1,
                                    systemName = "NAHID AI",
                                    assistantVoice = "Bengali-Natural",
                                    ownerName = "Masum Billah Nahid",
                                    loginUserName = "Masum Billah Nahid",
                                    loginPassword = ""
                                )
                            )

                            // Seed Default Personal AI User Preferences
                            dao.insertPreferences(
                                listOf(
                                    JarvisUserPreferenceEntity("preferred_language", "bn-BD", "VOICE", "ডিফল্ট কথোপকথনের ভাষা (বাংলা)"),
                                    JarvisUserPreferenceEntity("assistant_voice", "Bengali-Natural", "VOICE", "ভয়েস সিন্থেসাইজার স্টাইল"),
                                    JarvisUserPreferenceEntity("orb_overlay_enabled", "true", "UI", "স্ক্রিন ওএস ফ্লোটিং ওর্ব ডিসপ্লে"),
                                    JarvisUserPreferenceEntity("wake_word_enabled", "false", "VOICE", "অফলাইন 'Jarvis' ওয়েক-ওয়ার্ড লিসেনিং"),
                                    JarvisUserPreferenceEntity("wake_word_sensitivity", "MEDIUM", "VOICE", "শব্দ সনাক্তকরণ সংবেদনশীলতা"),
                                    JarvisUserPreferenceEntity("haptic_feedback_enabled", "true", "GENERAL", "টাচ ও ওয়েক-ওয়ার্ড ভাইব্রেশন ফিডব্যাক"),
                                    JarvisUserPreferenceEntity("arc_reactor_color", "#00F5D4", "UI", "নিউরাল কোর নিওন কালার থিম")
                                )
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
