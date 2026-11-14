package com.example

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.telephony.SmsManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AssistantToolExecutor
import com.example.automation.JarvisSkillRecorder
import com.example.data.local.AiTaskEntity
import com.example.data.local.AssistantDatabase
import com.example.data.repository.JarvisNotificationRepository
import com.example.data.repository.JarvisSkillRepository
import com.example.service.EnhancedNotification
import com.example.service.JarvisBootReceiver
import com.example.service.SmsStatusReceiver
import com.example.util.JarvisSmsManager
import com.example.util.SmsDeliveryStage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisRobolectricEndToEndTest {

    private lateinit var appContext: Application
    private lateinit var db: AssistantDatabase

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(appContext, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        JarvisNotificationRepository.clear()
    }

    @After
    fun tearDown() {
        db.close()
        JarvisNotificationRepository.clear()
    }

    @Test
    fun roomMigration16To17_preservesExistingRecordsAndAddsNewColumns() {
        val dbFile = appContext.getDatabasePath("migration_test_v16_v17.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        // Create legacy v16 schema & seed user data
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            sqlite.execSQL(
                """CREATE TABLE IF NOT EXISTS app_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    eventKey TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    message TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    targetRoute TEXT NOT NULL,
                    targetId TEXT NOT NULL,
                    targetName TEXT NOT NULL,
                    isRead INTEGER NOT NULL,
                    isDeleted INTEGER NOT NULL
                )"""
            )
            sqlite.execSQL(
                """CREATE TABLE IF NOT EXISTS jarvis_skills (
                    skillId TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    nameBengali TEXT NOT NULL,
                    description TEXT NOT NULL,
                    triggerPhrasesJson TEXT NOT NULL,
                    targetPackageName TEXT NOT NULL,
                    targetAppName TEXT NOT NULL,
                    category TEXT NOT NULL,
                    riskLevel TEXT NOT NULL,
                    requiresConfirmation INTEGER NOT NULL,
                    requiredPermissionsJson TEXT NOT NULL,
                    stepsJson TEXT NOT NULL,
                    stepCount INTEGER NOT NULL,
                    sourceType TEXT NOT NULL,
                    status TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    executionCount INTEGER NOT NULL,
                    successCount INTEGER NOT NULL,
                    lastExecutedAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )"""
            )
            sqlite.execSQL(
                "INSERT INTO app_notifications (eventKey, type, title, message, timestamp, targetRoute, targetId, targetName, isRead, isDeleted) VALUES ('legacy_k1', 'INCOMING_NOTIFICATION', 'WhatsApp: Rahim', 'Hello from v16', 1700000000000, 'notifications', 'com.whatsapp', 'Rahim', 0, 0)"
            )
            sqlite.execSQL(
                "INSERT INTO jarvis_skills (skillId, name, nameBengali, description, triggerPhrasesJson, targetPackageName, targetAppName, category, riskLevel, requiresConfirmation, requiredPermissionsJson, stepsJson, stepCount, sourceType, status, version, executionCount, successCount, lastExecutedAt, createdAt, updatedAt) VALUES ('legacy_skill_1', 'Legacy Skill', 'পুরনো স্কিল', 'desc', '[\"পুরনো স্কিল\"]', 'com.whatsapp', 'WhatsApp', 'CUSTOM', 'MEDIUM', 0, '[]', '[]', 0, 'USER_RECORDED', 'ACTIVE', 1, 3, 2, 0, 1000, 1000)"
            )
            sqlite.version = 16
        }

        // Run real Room MIGRATION_16_17 on existing v16 SQLite file
        val supportDb = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val helper = supportDb.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(appContext)
                .name(dbFile.name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        fail("Expected onUpgrade(16 -> 17), not onCreate")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(16, oldVersion)
                        assertEquals(17, newVersion)
                        AssistantDatabase.MIGRATION_16_17.migrate(db)
                    }
                }).build()
        )
        val upgraded = helper.writableDatabase
        upgraded.query("SELECT eventKey, message, packageName, category, isAnnounced FROM app_notifications WHERE eventKey='legacy_k1'").use { c ->
            assertTrue("Legacy notification row must survive migration", c.moveToFirst())
            assertEquals("Hello from v16", c.getString(1))
            assertEquals("GENERAL", c.getString(3))
            assertEquals(0, c.getInt(4))
        }
        upgraded.query("SELECT skillId, nameBengali, parametersSchemaJson, previousVersion FROM jarvis_skills WHERE skillId='legacy_skill_1'").use { c ->
            assertTrue("Legacy skill row must survive migration", c.moveToFirst())
            assertEquals("পুরনো স্কিল", c.getString(1))
            assertEquals("[]", c.getString(2))
            assertEquals(0, c.getInt(3))
        }
        helper.close()
        dbFile.delete()
    }

    @Test
    fun teachModeVersioningRollbackAndMissingParameterPromptWorkEndToEnd() = runBlocking {
        val repo = JarvisSkillRepository(db.jarvisSkillDao())

        val taught = JarvisSkillRecorder.teachSkillFromNaturalLanguage(
            skillName = "Send Greet",
            skillNameBengali = "অভিনন্দন পাঠাও",
            triggerPhrases = listOf("অভিনন্দন পাঠাও"),
            stepsInstructions = "Click Search\nType {contact}\nClick Chat\nType {message}",
            targetPackageName = "com.whatsapp",
            repository = repo
        )
        assertTrue(taught.stepCount >= 3)
        assertTrue(taught.parametersSchemaJson.contains("contact"))
        assertTrue(taught.parametersSchemaJson.contains("message"))

        // Upgrade to v2 and then rollback -> creates v3 with v1 steps preserved
        val newSteps = listOf(
            com.example.data.local.JarvisSkillStepEntity(
                skillId = taught.skillId,
                stepIndex = 1,
                actionType = "CLICK",
                targetText = "NewButton",
                descriptionBengali = "নতুন বাটন"
            )
        )
        val (updOk, _) = repo.updateSkillWithVersioning(taught.skillId, newSteps = newSteps)
        assertTrue(updOk)
        assertEquals(2, repo.getSkillById(taught.skillId)?.version)
        assertEquals(1, repo.getStepsForSkill(taught.skillId).size)

        val (rbOk, _) = repo.rollbackSkillToPreviousVersion(taught.skillId)
        assertTrue(rbOk)
        val afterRollback = repo.getSkillById(taught.skillId)!!
        assertEquals(3, afterRollback.version)
        assertEquals(taught.stepCount, repo.getStepsForSkill(taught.skillId).size)

        // Execute learned skill when Accessibility Service is NOT enabled -> MUST report PERMISSION_REQUIRED truthfully (zero fake success!)
        val singletonDb = AssistantDatabase.getDatabase(appContext)
        val executor = AssistantToolExecutor(
            dao = singletonDb.assistantDao(),
            onNavigate = {},
            context = appContext
        )
        val permResultRef = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            permResultRef.set(
                executor.executeToolWithCaller(
                    name = "executeLearnedSkill",
                    args = JSONObject().put("skillNameOrTrigger", "অভিনন্দন পাঠাও"),
                    caller = "robolectric_test"
                )
            )
        }
        val deadline = System.currentTimeMillis() + 10_000L
        while (permResultRef.get() == null && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        val execBlockedByPerm = permResultRef.get()
        assertNotNull("Permission check should complete without hanging", execBlockedByPerm)
        assertFalse(execBlockedByPerm!!.optBoolean("success", true))
        assertEquals("PERMISSION_REQUIRED", execBlockedByPerm.optString("status"))
        com.example.permissions.PermissionOrchestrator.resetAllSuppression()

        // Emergency stop pre-dispatch cancellation check via ToolExecutionPipeline
        com.example.util.JarvisEmergencyStopCoordinator.triggerEmergencyStop(null)
        val execCancelled = executor.executeToolWithCaller(
            name = "getDeviceInfo",
            args = JSONObject(),
            caller = "robolectric_test"
        )
        assertEquals("CANCELLED_BEFORE_DISPATCH", execCancelled.optString("status"))
        com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()

        // Disable & delete cleanup
        assertTrue(repo.setSkillEnabled(taught.skillId, false))
        assertEquals("DISABLED", repo.getSkillById(taught.skillId)?.status)
        repo.deleteSkill(taught.skillId)
        assertNull(repo.getSkillById(taught.skillId))
    }

    @Test
    fun smsStatusReceiver_updatesTelemetryStagesFromAndroidBroadcasts() {
        JarvisSmsManager.clearStatusHistory()
        val receiver = SmsStatusReceiver()
        val reqId = 7071
        val recipient = "+8801712345678"

        val sentOkIntent = Intent(SmsStatusReceiver.ACTION_SMS_SENT).apply {
            putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, reqId)
            putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, recipient)
        }
        appContext.sendOrderedBroadcast(sentOkIntent, null, receiver, null, Activity.RESULT_OK, null, null)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(SmsDeliveryStage.SEND_CONFIRMED, JarvisSmsManager.getDeliveryStatus(reqId)?.stage)
        assertEquals(SmsDeliveryStage.SEND_CONFIRMED, JarvisSmsManager.latestSmsStatus.value?.stage)

        val deliveredIntent = Intent(SmsStatusReceiver.ACTION_SMS_DELIVERED).apply {
            putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, reqId)
            putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, recipient)
        }
        appContext.sendOrderedBroadcast(deliveredIntent, null, receiver, null, Activity.RESULT_OK, null, null)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(SmsDeliveryStage.DELIVERY_CONFIRMED, JarvisSmsManager.getDeliveryStatus(reqId)?.stage)

        val failReqId = 7072
        val failIntent = Intent(SmsStatusReceiver.ACTION_SMS_SENT).apply {
            putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, failReqId)
            putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, recipient)
        }
        appContext.sendOrderedBroadcast(failIntent, null, receiver, null, SmsManager.RESULT_ERROR_NO_SERVICE, null, null)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(SmsDeliveryStage.SEND_FAILED, JarvisSmsManager.getDeliveryStatus(failReqId)?.stage)
        assertTrue(JarvisSmsManager.latestSmsStatus.value?.summaryBengali?.contains("সিম") == true)
    }

    @Test
    fun notificationDao_persistsSearchesFiltersOtpAndMarksAnnounced() = runBlocking {
        val dao = db.assistantDao()
        val now = System.currentTimeMillis()
        dao.upsertNotification(
            com.example.data.local.AppNotificationEntity(
                eventKey = "wa_msg_1",
                type = "INCOMING_NOTIFICATION",
                title = "WhatsApp: তানভির",
                message = "মিটিং ৫টায় শুরু হবে",
                timestamp = now,
                targetRoute = "notifications",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                sender = "তানভির",
                category = "MESSAGE",
                hasReplyAction = true
            )
        )
        dao.upsertNotification(
            com.example.data.local.AppNotificationEntity(
                eventKey = "nagad_otp_1",
                type = "SECURITY_OTP",
                title = "Nagad: OTP",
                message = "Your Nagad OTP is 918273",
                timestamp = now + 50,
                targetRoute = "notifications",
                packageName = "com.konasl.nagad",
                appName = "Nagad",
                sender = "Nagad",
                category = "OTP",
                hasReplyAction = false
            )
        )

        val searched = dao.searchNotificationsSync("তানভির", 10)
        assertEquals(1, searched.size)
        assertEquals("wa_msg_1", searched[0].eventKey)

        val otps = dao.getOtpNotificationsSync(10)
        assertEquals(1, otps.size)
        assertEquals("Nagad", otps[0].appName)

        dao.markNotificationAnnounced("wa_msg_1", now + 200)
        val after = dao.getRecentActiveNotificationsSync(10).first { it.eventKey == "wa_msg_1" }
        assertTrue(after.isAnnounced)
    }

    @Test
    fun bootReceiver_reschedulesPendingFutureTasksIntoAlarmManager() = runBlocking {
        val singletonDb = AssistantDatabase.getDatabase(appContext)
        val futureTime = System.currentTimeMillis() + 3_600_000L
        singletonDb.assistantDao().insertAiTask(
            AiTaskEntity(
                title = "গুরুত্বপূর্ণ মিটিং রিমাইন্ডার",
                description = "রাত ৯টার ব্যক্তিগত মিটিং",
                scheduledTime = futureTime,
                status = "PENDING",
                actionType = "REMINDER"
            )
        )

        val receiver = JarvisBootReceiver()
        val rescheduled = receiver.reschedulePendingAlarms(appContext)
        assertTrue("BootReceiver should reschedule at least 1 pending future task", rescheduled >= 1)

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        assertTrue("AlarmManager must hold scheduled alarm", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }
}
