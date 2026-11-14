package com.example

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AssistantDatabase
import com.example.data.local.DatabaseMigrationHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantDatabaseMigrationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AssistantDatabase.resetInstanceForTest()
        app.deleteDatabase("nahid_jarvis_ai_db")
        app.deleteDatabase("pharmacy_db")
        app.deleteDatabase(DatabaseMigrationHelper.NEW_DB_NAME)
        app.deleteDatabase("schema_seed_temp.db")
        File(app.filesDir, "ai_db_backup").deleteRecursively()
    }

    @After
    fun tearDown() {
        AssistantDatabase.resetInstanceForTest()
        app.deleteDatabase("nahid_jarvis_ai_db")
        app.deleteDatabase("pharmacy_db")
        app.deleteDatabase(DatabaseMigrationHelper.NEW_DB_NAME)
        app.deleteDatabase("schema_seed_temp.db")
        File(app.filesDir, "ai_db_backup").deleteRecursively()
    }

    @Test
    fun legacyDbBackupAndMigration18To19_preservesAllPersonalAiDataAndRemovesPharmacyFields() = runBlocking {
        // 1. Obtain exact table & index DDL statements for schema validation
        val tempDb = Room.databaseBuilder(app, AssistantDatabase::class.java, "schema_seed_temp.db")
            .allowMainThreadQueries()
            .build()
        val ddlStatements = mutableListOf<String>()
        tempDb.openHelper.writableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sql = cursor.getString(0)
                if (!sql.isNullOrBlank()) ddlStatements.add(sql)
            }
        }
        tempDb.close()
        app.deleteDatabase("schema_seed_temp.db")

        // 2. Create legacy v18 database file (`nahid_jarvis_ai_db`) with legacy pharmacy fields & rich AI data
        val legacyFile = app.getDatabasePath("nahid_jarvis_ai_db")
        legacyFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { sqlite ->
            ddlStatements.forEach { sql -> sqlite.execSQL(sql) }
            // Inject legacy v18 columns onto app_users
            sqlite.execSQL("ALTER TABLE app_users ADD COLUMN pharmacyName TEXT NOT NULL DEFAULT ''")
            sqlite.execSQL("ALTER TABLE app_users ADD COLUMN pharmacyLocation TEXT NOT NULL DEFAULT ''")

            sqlite.execSQL(
                """
                INSERT INTO app_users (
                    userId, profileName, deviceId, userNumber, isMasterApp,
                    deviceModel, deviceName, manufacturer, installationId, appVersion,
                    androidVersion, isOnline, lastLoginAt, lastLogoutAt, lastSeenAt,
                    isBlocked, blockedBy, blockedAt, locationSharingEnabled,
                    latitude, longitude, accuracy, locationAddress, locationUpdatedAt,
                    stationName, stationLocation, photoUri, pharmacyName, pharmacyLocation
                ) VALUES (
                    'owner_1', 'Masum Billah Nahid', 'dev_abc', '01700000000', 1,
                    'Pixel', 'Pixel 8', 'Google', 'inst_1', '3.0',
                    '14', 1, 1000, 0, 2000,
                    0, '', 0, 0,
                    NULL, NULL, NULL, '', 0,
                    'JARVIS CORE', 'Dhaka', NULL, 'JANANI PHARMACY', 'Bazar Road'
                )
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO jarvis_profile (
                    id, systemName, assistantVoice, mobileNumber, location, photoUri,
                    ownerName, loginUserName, loginPassword, arcReactorColor, securityLevel, autoAutomationAllowed
                ) VALUES (
                    1, 'JANANI PHARMACY', 'Bengali-Natural', '01700000000', 'Dhaka', NULL,
                    'Masum Billah Nahid', 'Masum Billah Nahid', '994400', '#00F0FF', 'HIGH', 1
                )
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO ai_memories (key, content, category, importance, createdAt, updatedAt, source, isPinned, isActive)
                VALUES ('user_goal', 'Build NAHID AI personal assistant', 'PERSONAL_GOAL', 3, 1000, 1000, 'USER', 1, 1)
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO ai_tasks (title, description, scheduledTime, scheduledTimeString, status, actionType, targetCommand, repeatInterval, priority, isExecuted, createdAt)
                VALUES ('Legacy Alert', 'Evening reminder', 9999999999999, '8:00 PM', 'PENDING', 'MEDICINE_DOSE', 'Remind me', 'NONE', 'HIGH', 0, 1000)
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO notes (title, content, timestamp)
                VALUES ('Architecture Note', 'Keep Maya voice natural in Bengali', 1000)
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO jarvis_chat_history (userPrompt, aiResponse, timestamp, commandType, status, executionDetails)
                VALUES ('হ্যালো মায়া', 'হ্যালো স্যার, আমি প্রস্তুত!', 1000, 'VOICE', 'SUCCESS', 'ok')
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO jarvis_frequent_contacts (name, alias, phoneNumber, relationship, interactionCount, hasWhatsApp, isFavorite, lastContactedAt, notes)
                VALUES ('Baba', 'বাবা', '01711111111', 'Father', 5, 1, 1, 1000, '')
                """.trimIndent()
            )
            sqlite.version = 18
        }

        // 3. Open AssistantDatabase (automatically triggers backup + migration 18 -> 19)
        val newDb = AssistantDatabase.getDatabase(app)
        val report = DatabaseMigrationHelper.verifyMigratedDataCounts(newDb)

        // Verify internal backup files were created before migration
        val backupDir = File(app.filesDir, "ai_db_backup")
        val backupSnapshots = backupDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertTrue("Legacy SQLite backup folder must exist", backupSnapshots.isNotEmpty())
        assertTrue(
            "Backup file 'nahid_jarvis_ai_db' must exist in snapshot",
            backupSnapshots.any { File(it, "nahid_jarvis_ai_db").exists() }
        )

        // Verify 100% personal AI data survival
        assertTrue(report.isHealthy)
        assertEquals(1, report.memoryCount)
        assertEquals(1, report.taskCount)
        assertEquals(1, report.noteCount)
        assertEquals(1, report.chatHistoryCount)
        assertEquals(1, report.frequentContactCount)

        val dao = newDb.assistantDao()

        // Verify jarvis_profile normalized to NAHID AI & static PIN '994400' cleared
        val profile = dao.getProfileSync()
        assertNotNull(profile)
        assertEquals("NAHID AI", profile!!.systemName)
        assertEquals("", profile.loginPassword)
        assertEquals("#00F5D4", profile.arcReactorColor)
        assertEquals("Masum Billah Nahid", profile.ownerName)

        // Verify MEDICINE_DOSE normalized to REMINDER
        val tasks = dao.getAllAiTasksSync()
        assertEquals(1, tasks.size)
        assertEquals("REMINDER", tasks[0].actionType)

        // Verify app_users schema contains ZERO pharmacyName / pharmacyLocation columns
        val cols = mutableListOf<String>()
        newDb.openHelper.writableDatabase.query("PRAGMA table_info(app_users)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                cols.add(c.getString(nameIdx))
            }
        }
        assertFalse("pharmacyName must not exist in app_users", cols.contains("pharmacyName"))
        assertFalse("pharmacyLocation must not exist in app_users", cols.contains("pharmacyLocation"))
        assertTrue("stationName must exist in app_users", cols.contains("stationName"))

        val ownerUser = dao.getUserById("owner_1")
        assertNotNull(ownerUser)
        assertEquals("NAHID AI CORE", ownerUser!!.stationName)
        assertEquals("Masum Billah Nahid", ownerUser.profileName)
    }
}
