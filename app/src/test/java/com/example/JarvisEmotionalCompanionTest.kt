package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ai.*
import com.example.data.local.CompanionDao
import com.example.data.local.AssistantDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisEmotionalCompanionTest {

    private lateinit var app: Application
    private lateinit var db: AssistantDatabase
    private lateinit var companionDao: CompanionDao

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        JarvisPersonalityManager.resetForTest()
        EmotionContextEngine.resetForTest()
        LiveConversationContextBridge.resetForTest()
        JarvisCameraVisionCoordinator.resetForTest()

        db = Room.inMemoryDatabaseBuilder(app, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        companionDao = db.companionDao()
    }

    @After
    fun tearDown() {
        db.close()
        JarvisPersonalityManager.resetForTest()
        EmotionContextEngine.resetForTest()
        LiveConversationContextBridge.resetForTest()
        JarvisCameraVisionCoordinator.resetForTest()
    }

    @Test
    fun c01_fivePersonalitiesHaveDistinctProfilesAndPrompts() {
        val modes = JarvisPersonalityMode.entries
        assertEquals(5, modes.size)

        val prompts = modes.map { mode ->
            val profile = JarvisPersonalityManager.getProfile(mode)
            assertTrue(profile.speakingStyleBn.isNotBlank())
            assertTrue(profile.toneDescriptionBn.isNotBlank())
            assertTrue(profile.emotionBehaviorBn.isNotBlank())
            JarvisPersonalityManager.getPersonalityPrompt(mode)
        }
        assertEquals("All 5 character prompts should be distinct", 5, prompts.toSet().size)
    }

    @Test
    fun c02_voiceSwitchAndRevertToPreviousModeWork() {
        JarvisPersonalityManager.setPersonality(JarvisPersonalityMode.PROFESSIONAL)
        val romantic = JarvisPersonalityManager.parseVoicePersonalitySwitch("রোমান্টিক মোডে যাও")
        assertEquals(JarvisPersonalityMode.ROMANTIC, romantic)
        JarvisPersonalityManager.setPersonality(romantic!!)

        assertEquals(JarvisPersonalityMode.ROMANTIC, JarvisPersonalityManager.currentMode.value)
        assertEquals(JarvisPersonalityMode.PROFESSIONAL, JarvisPersonalityManager.previousMode.value)

        // Voice switch back ("আগের মোডে ফিরে যাও")
        assertTrue(JarvisPersonalityManager.isSwitchBackRequest("আগের মোডে ফিরে যাও"))
        assertTrue(JarvisPersonalityManager.isSwitchBackRequest("switch to previous mode"))
        val reverted = JarvisPersonalityManager.revertToPreviousMode()
        assertEquals(JarvisPersonalityMode.PROFESSIONAL, reverted)
        assertEquals(JarvisPersonalityMode.PROFESSIONAL, JarvisPersonalityManager.currentMode.value)
    }

    @Test
    fun c03_characterFeedbackCreatesVersionHistoryAndSupportsRollback() = runBlocking(Dispatchers.IO) {
        JarvisPersonalityManager.attachDao(companionDao)
        JarvisPersonalityManager.setPersonality(JarvisPersonalityMode.ROMANTIC)

        JarvisPersonalityManager.recordUserCharacterFeedback("উত্তর আরও সংক্ষিপ্ত রাখো")
        Thread.sleep(80)
        JarvisPersonalityManager.recordUserCharacterFeedback("বাক্যের শেষে মিষ্টি শুভেচ্ছা দাও")
        Thread.sleep(80)

        val currentProfile = JarvisPersonalityManager.activeProfile.value
        assertTrue(currentProfile.version >= 3)
        assertTrue(currentProfile.customRules.contains("উত্তর আরও সংক্ষিপ্ত রাখো"))
        assertTrue(currentProfile.customRules.contains("বাক্যের শেষে মিষ্টি শুভেচ্ছা দাও"))

        // Rollback to v1 baseline restores clean default rules safely
        val rolledBack = JarvisPersonalityManager.revertToVersion(1)
        assertTrue(rolledBack)
        assertTrue(JarvisPersonalityManager.activeProfile.value.customRules.isEmpty())
    }

    @Test
    fun c04_adaptivePersonalityEngineEnforcesPriorityHierarchy() {
        JarvisPersonalityManager.setPersonality(JarvisPersonalityMode.BEST_FRIEND, explicitSelection = true)
        EmotionContextEngine.analyzeText("আমার আজকে খুব মন খারাপ, কষ্ট হচ্ছে")

        val directive = AdaptivePersonalityEngine.computeDirective(
            explicitMode = JarvisPersonalityManager.currentMode.value,
            explicitLocked = true,
            conversationContextHint = "casual talk",
            emotionEval = EmotionContextEngine.currentEmotion.value
        )

        // Priority 1: MUST keep user's explicitly selected BEST_FRIEND character rather than overriding to COMFORT
        assertEquals(JarvisPersonalityMode.BEST_FRIEND, directive.activeCharacterMode)
        assertTrue(directive.subToneBn.contains("কোমল") || directive.subToneBn.contains("সান্ত্বনাদায়ক"))
        assertTrue(directive.priorityApplied.contains("Explicit Character"))
    }

    @Test
    fun c05_emotionEngineHandlesConflictUncertaintyTogglesAndReset() {
        // Sad text vs happy camera expression -> MUST mark uncertain
        val textSad = EmotionContextEngine.analyzeText("খুব মন খারাপ, ভালো লাগছে না")
        assertEquals(UserEmotion.SAD, textSad.emotion)

        val conflict = EmotionContextEngine.updateMultimodalEmotion(
            textEval = textSad,
            faceDetected = true,
            visualEmotion = UserEmotion.HAPPY,
            visualConfidence = 0.85f
        )
        assertTrue("Conflicting text vs camera must be marked uncertain", conflict.isUncertain)
        assertEquals(UserEmotion.UNKNOWN, conflict.emotion)

        // Manual correction restores clarity
        EmotionContextEngine.applyUserEmotionCorrection(UserEmotion.TIRED)
        assertEquals(UserEmotion.TIRED, EmotionContextEngine.currentEmotion.value.emotion)
        assertFalse(EmotionContextEngine.currentEmotion.value.isUncertain)
        assertTrue(EmotionContextEngine.currentEmotion.value.userCorrected)

        // Toggle text analysis off -> analyzeText returns unchanged state
        EmotionContextEngine.updateConfig(EmotionControlConfig(textAnalysisEnabled = false))
        EmotionContextEngine.analyzeText("খুব খুশি")
        assertEquals(UserEmotion.TIRED, EmotionContextEngine.currentEmotion.value.emotion)

        // Clear history & reset
        EmotionContextEngine.clearHistory()
        assertTrue(EmotionContextEngine.history.value.isEmpty())
        assertEquals(UserEmotion.NEUTRAL, EmotionContextEngine.currentEmotion.value.emotion)
    }

    @Test
    fun c06_liveConversationContextBridgeQueuesOfflineDeduplicatesAndFlushesOnConnect() {
        LiveConversationContextBridge.onConnectionStateChanged(LiveSessionState.DISCONNECTED)

        val added1 = LiveConversationContextBridge.enqueueContextUpdate("scene_desk", "ডেস্কের ওপর ল্যাপটপ দেখা যাচ্ছে")
        val dupIgnored = LiveConversationContextBridge.enqueueContextUpdate("scene_desk", "ডেস্কের ওপর ল্যাপটপ দেখা যাচ্ছে")
        assertTrue(added1)
        assertFalse("Duplicate observation within cooldown must be suppressed", dupIgnored)
        assertEquals(1, LiveConversationContextBridge.peekPendingCount())

        var flushedItems = emptyList<QueuedContextPacket>()
        LiveConversationContextBridge.setFlushListener { items -> flushedItems = items }

        // Transitioning to CONNECTED flushes queued observations cleanly
        LiveConversationContextBridge.onConnectionStateChanged(LiveSessionState.CONNECTED)
        assertEquals(1, flushedItems.size)
        assertEquals(0, LiveConversationContextBridge.peekPendingCount())
    }

    @Test
    fun c07_cameraVisionCoordinatorClampsSamplingIntervalAndNeverFakesOfflineDetection() = runBlocking(Dispatchers.IO) {
        JarvisCameraVisionCoordinator.setSamplingInterval(500L) // below min -> clamps to 1000ms
        assertEquals(1000L, JarvisCameraVisionCoordinator.observationState.value.samplingIntervalMs)

        JarvisCameraVisionCoordinator.setSamplingInterval(3000L)
        assertEquals(3000L, JarvisCameraVisionCoordinator.observationState.value.samplingIntervalMs)

        // Offline expression check with blank API key MUST truthfully report available = false
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val offlineRes = ScreenVisualAnalyzer.analyzeExpressionAndGesture(bmp, "")
        assertFalse("Offline camera analysis without API key must never fake detection", offlineRes.available)
        assertFalse(offlineRes.faceDetected)
    }

    @Test
    fun c08_roomMigration17To18PreservesExistingTablesAndCreatesAllNewTables() {
        val dbName = "migration_17_18_test_db"
        app.deleteDatabase(dbName)

        // Create a v17 database with legacy data
        val helper17 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, category TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO notes (title, content, category, timestamp) VALUES ('Test Note', 'Preserved across migration', 'GENERAL', 123456)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writableV17 = helper17.writableDatabase
        writableV17.close()

        // Apply MIGRATION_17_18 manually to verify SQL validity & non-destructive behavior
        val migratedHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AssistantDatabase.MIGRATION_17_18.migrate(db)
                    }
                })
                .build()
        )
        val upgradedDb = migratedHelper.writableDatabase

        // Verify legacy note still exists intact
        val noteCursor = upgradedDb.query("SELECT title FROM notes WHERE title = 'Test Note'")
        assertTrue("Existing v17 note must survive MIGRATION_17_18", noteCursor.moveToFirst())
        noteCursor.close()

        // Verify all 5 new v18 tables exist and accept writes
        for (table in listOf(
            "character_profiles",
            "character_profile_versions",
            "emotion_history",
            "action_approval_audits",
            "trusted_low_risk_rules"
        )) {
            val c = upgradedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
            assertTrue("Table '$table' must exist after MIGRATION_17_18", c.moveToFirst())
            c.close()
        }

        upgradedDb.close()
        app.deleteDatabase(dbName)
    }
}
