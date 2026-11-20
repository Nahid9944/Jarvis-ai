package com.example

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAgentRegistry
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.VoiceCommandUnderstandingEngine
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.HttpFetchResult
import com.example.data.local.AiMemoryEntity
import com.example.data.local.AssistantDatabase
import com.example.data.local.DatabaseMigrationHelper
import com.example.data.local.DevTaskEntity
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.developer.AutonomyLevel
import com.example.developer.CapabilityDecisionType
import com.example.developer.CapabilityIntelligenceGraph
import com.example.developer.DevWorkStatus
import com.example.developer.DeveloperWorkspaceEngine
import com.example.developer.HomeDeveloperOrchestrator
import com.example.developer.HomeDeveloperScheduler
import com.example.developer.HomeDeveloperSettingsRepository
import com.example.developer.SupportedCodeLanguage
import com.example.developer.UniversalSkillFactory
import com.example.tools.ToolRegistry
import com.example.ui.screens.HomeDeveloperCenterScreen
import com.example.viewmodel.AssistantViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeDeveloperAgentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var db: AssistantDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AssistantDatabase.resetInstanceForTest()
        app.deleteDatabase(DatabaseMigrationHelper.NEW_DB_NAME)
        app.deleteDatabase("nahid_jarvis_ai_db")
        app.deleteDatabase("schema_v20_seed.db")
        app.getSharedPreferences("nahid_home_dev_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        AssistantDatabase.resetInstanceForTest()
        app.deleteDatabase(DatabaseMigrationHelper.NEW_DB_NAME)
        app.deleteDatabase("schema_v20_seed.db")
    }

    @Test
    fun migration20To21_createsHomeDevTablesAndPreservesExistingData() = runBlocking {
        val seedDb = Room.databaseBuilder(app, AssistantDatabase::class.java, "schema_v20_seed.db")
            .allowMainThreadQueries()
            .build()
        val v21Excluded = setOf(
            "dev_tasks", "dev_activity_logs", "dev_patch_history", "capability_gaps", "skill_evidence",
            "knowledge_cache", "knowledge_telemetry"
        )
        val v20Statements = mutableListOf<String>()
        seedDb.openHelper.writableDatabase.query(
            "SELECT name, tbl_name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'"
        ).use { c ->
            while (c.moveToNext()) {
                val tblName = c.getString(1)
                val sql = c.getString(2)
                if (tblName !in v21Excluded && !sql.isNullOrBlank()) {
                    v20Statements.add(sql)
                }
            }
        }
        seedDb.close()
        app.deleteDatabase("schema_v20_seed.db")

        val dbFile = app.getDatabasePath(DatabaseMigrationHelper.NEW_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            v20Statements.forEach { sql -> sqlite.execSQL(sql) }
            sqlite.execSQL(
                "INSERT INTO ai_memories (key, content, category, importance, createdAt, updatedAt, source, isPinned, isActive) VALUES ('v20_mem', 'Keep Bangla UI clear', 'PREFERENCE', 2, 1000, 1000, 'USER', 1, 1)"
            )
            sqlite.version = 20
        }

        val migrated = AssistantDatabase.getDatabase(app)
        assertEquals(1, migrated.assistantDao().getAllActiveMemoriesSync().size)
        migrated.homeDeveloperDao().upsertDevTask(DevTaskEntity(taskId = "t_mig_1", goal = "Verify v21 table", totalSteps = 2))
        assertEquals("Verify v21 table", migrated.homeDeveloperDao().getDevTaskByIdSync("t_mig_1")?.goal)
    }

    @Test
    fun agentRegistryToolRegistryAndCapabilityGraph_classify4WayRequestsHonestly() = runBlocking {
        assertEquals(14, AiAgentRegistry.ALL_AGENTS.size)
        assertEquals(AiAgentRegistry.HOME_DEVELOPER, AiAgentRegistry.getAgentById("home_developer"))
        assertNotNull(ToolRegistry.get("startHomeDevTask"))
        assertNotNull(ToolRegistry.get("pauseHomeDeveloper"))
        assertNotNull(ToolRegistry.get("resumeHomeDeveloper"))
        assertNotNull(ToolRegistry.get("cancelHomeDeveloper"))
        assertNotNull(ToolRegistry.get("getHomeDevStatus"))
        assertNotNull(ToolRegistry.get("runSelfRepairAudit"))
        assertNotNull(ToolRegistry.get("createCombinedSkill"))
        assertNotNull(ToolRegistry.get("learnFromDocument"))
        assertNotNull(ToolRegistry.get("learnFromScreenshot"))
        assertNotNull(ToolRegistry.get("setSelfDevelopmentMode"))
        assertNotNull(ToolRegistry.get("generateDeveloperArtifact"))

        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        skillRepo.saveSkillWithSteps(
            JarvisSkillEntity(
                skillId = "sk_morning",
                name = "Morning WhatsApp Check",
                nameBengali = "সকালের হোয়াটসঅ্যাপ চেক",
                description = "Checks unread messages",
                triggerPhrasesJson = "[\"সকালের হোয়াটসঅ্যাপ চেক\"]",
                stepCount = 1,
                status = "ACTIVE"
            ),
            listOf(JarvisSkillStepEntity(skillId = "sk_morning", stepIndex = 1, actionType = "OPEN_APP", targetViewId = "com.whatsapp", descriptionBengali = "হোয়াটসঅ্যাপ খোলা"))
        )

        val d1 = CapabilityIntelligenceGraph.classifyUserCapabilityNeed("সকালের হোয়াটসঅ্যাপ চেক করো", skillRepo)
        assertEquals(CapabilityDecisionType.EXISTING_SKILL_AVAILABLE, d1.decisionType)

        val d2 = CapabilityIntelligenceGraph.classifyUserCapabilityNeed("flashlight", skillRepo)
        assertEquals(CapabilityDecisionType.EXISTING_TOOLS_CAN_PERFORM, d2.decisionType)

        val d3 = CapabilityIntelligenceGraph.classifyUserCapabilityNeed("AndroidManifest permission যোগ করো নতুন সার্ভিস", skillRepo)
        assertEquals(CapabilityDecisionType.NATIVE_CAPABILITY_OR_CODE_REQUIRED, d3.decisionType)
        assertTrue(d3.requiresNativeBuildAndInstall)

        val snapshot = CapabilityIntelligenceGraph.refreshAndPersistGaps(app, skillRepo, db.homeDeveloperDao())
        assertTrue(snapshot.totalTools >= 135)
        assertEquals(14, snapshot.totalAgents)
        assertTrue(db.homeDeveloperDao().getAllCapabilityGapsSync().isNotEmpty())
    }

    @Test
    fun universalSkillFactory_supportsDocumentScreenshotCombineSecurityAndEvidence() = runBlocking {
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val devDao = db.homeDeveloperDao()
        val aDao = db.assistantDao()
        val settingsRepo = HomeDeveloperSettingsRepository(app)

        // 1. Document with DCL blocked
        val blockedDoc = UniversalSkillFactory.learnFromDocument(
            context = app,
            fileName = "unsafe.kt",
            documentContent = "val loader = DexClassLoader(\"bad.dex\", null, null, null)",
            skillRepoOverride = skillRepo,
            assistantDaoOverride = aDao,
            devDaoOverride = devDao,
            settingsRepo = settingsRepo
        )
        assertFalse(blockedDoc.success)
        assertEquals("BLOCKED", blockedDoc.status)

        // 2. Clean Document -> DRAFT -> Approved ACTIVE
        val docRes = UniversalSkillFactory.learnFromDocument(
            context = app,
            fileName = "guide.md",
            documentContent = "1. Open WhatsApp app\n2. Click Search button\n3. Type Nahid",
            skillRepoOverride = skillRepo,
            assistantDaoOverride = aDao,
            devDaoOverride = devDao,
            settingsRepo = settingsRepo
        )
        assertTrue(docRes.success)
        assertEquals("DRAFT", docRes.status)
        val activated = UniversalSkillFactory.approveAndActivateDraftSkill(docRes.skillId, skillRepo, devDao)
        assertTrue(activated.success)
        assertEquals("ACTIVE", activated.status)

        // 3. Screenshot OCR skill
        val shotRes = UniversalSkillFactory.learnFromScreenshotOrVisionText(
            context = app,
            observedUiText = "Open Settings app\nClick Display button",
            targetAppHint = "com.android.settings",
            title = "ডিসপ্লে শর্টকাট",
            skillRepoOverride = skillRepo,
            devDaoOverride = devDao,
            settingsRepo = settingsRepo
        )
        assertTrue(shotRes.success)
        assertEquals(2, shotRes.stepCount)

        // 4. Composite combineSkills
        val combo = UniversalSkillFactory.combineSkills(
            context = app,
            skillIds = listOf(docRes.skillId, shotRes.skillId),
            newSkillName = "যৌথ হোয়াটসঅ্যাপ ও সেটিংস",
            activateImmediately = true,
            skillRepoOverride = skillRepo,
            devDaoOverride = devDao
        )
        assertTrue(combo.success)
        assertEquals("ACTIVE", combo.status)
        assertEquals(5, combo.stepCount)

        // 5. Evidence tracking & automatic review threshold (>=2 failures)
        UniversalSkillFactory.recordSkillOutcomeAndAudit(combo.skillId, false, 450L, "ELEMENT_MISSING", skillRepo, devDao)
        val ev2 = UniversalSkillFactory.recordSkillOutcomeAndAudit(combo.skillId, false, 500L, "TIMEOUT", skillRepo, devDao)
        assertTrue(ev2.needsReview)
        assertEquals(0, ev2.healthScore)

        // 6. Respect disabled source setting
        settingsRepo.update { it.copy(allowDocumentLearning = false) }
        val disabled = UniversalSkillFactory.learnFromDocument(
            context = app,
            fileName = "x.md",
            documentContent = "Open Chrome app\nClick Home",
            skillRepoOverride = skillRepo,
            assistantDaoOverride = aDao,
            devDaoOverride = devDao,
            settingsRepo = settingsRepo
        )
        assertEquals("SOURCE_DISABLED", disabled.status)
    }

    @Test
    fun developerWorkspaceEngine_supportsMultiLanguageWebSecurityBlockAndRollback() = runBlocking {
        val aDao = db.assistantDao()
        val devDao = db.homeDeveloperDao()

        // Supported language detection
        assertEquals(SupportedCodeLanguage.PYTHON, SupportedCodeLanguage.detectFromFileOrPrompt("script.py", ""))
        assertEquals(SupportedCodeLanguage.RUST, SupportedCodeLanguage.detectFromFileOrPrompt("main.rs", ""))

        // Kotlin native patch -> NOT_COMPILED_REQUIRES_BUILD + test stub
        val ktPatch = DeveloperWorkspaceEngine.generateLanguagePatchAndTestStub(
            context = app,
            taskId = "t_kt",
            targetFile = "VoiceFilter.kt",
            goalPrompt = "Normalize audio pitch",
            previousSnapshot = "// original VoiceFilter.kt",
            assistantDaoOverride = aDao,
            devDaoOverride = devDao
        )
        assertTrue(ktPatch.success)
        assertEquals("NOT_COMPILED_REQUIRES_BUILD", ktPatch.patch.compileStatus)
        assertTrue(ktPatch.artifactId > 0L)
        assertTrue(ktPatch.testStubArtifactId > 0L)

        // Security block on hardcoded secret
        val secretBlock = DeveloperWorkspaceEngine.generateLanguagePatchAndTestStub(
            context = app,
            targetFile = "Leaky.kt",
            goalPrompt = "bad secret",
            proposedContentOverride = "val key = \"AIzaSyD123456789012345678901234567890123\"",
            assistantDaoOverride = aDao,
            devDaoOverride = devDao
        )
        assertFalse(secretBlock.success)
        assertEquals("BLOCKED", secretBlock.patch.securityStatus)

        // Web App Workspace -> DECLARATIVE_READY
        val web = DeveloperWorkspaceEngine.generateWebAppWorkspace(
            context = app,
            taskId = "t_web",
            appTitle = "Bangla Task Board",
            goalPrompt = "Interactive task list",
            assistantDaoOverride = aDao,
            devDaoOverride = devDao
        )
        assertTrue(web.success)
        assertEquals("DECLARATIVE_READY", web.patch.compileStatus)

        // Rollback restores previousSnapshot
        val (rbOk, _) = DeveloperWorkspaceEngine.rollbackPatch(ktPatch.patch.patchId, devDao, aDao)
        assertTrue(rbOk)
        val rolled = devDao.getPatchByIdSync(ktPatch.patch.patchId)
        assertEquals("ROLLED_BACK", rolled?.status)
        assertEquals("// original VoiceFilter.kt", rolled?.patchContent)
    }

    @Test
    fun orchestrator_checkpointPauseResumeCancelAndTruthfulZeroPercentIdle() = runBlocking {
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())

        // Idle -> 0% progress
        HomeDeveloperOrchestrator.syncInitialStateFromDatabase(app, db, HomeDeveloperSettingsRepository(app))
        assertEquals(0, HomeDeveloperOrchestrator.devState.value.progressPercent)
        assertEquals(DevWorkStatus.IDLE, HomeDeveloperOrchestrator.devState.value.status)

        // Persist a paused mid-way checkpoint & simulate cold resume from DB
        val pausedTask = DevTaskEntity(
            taskId = "dev_ckpt_1",
            goal = "Build Audio Helper",
            phase = "PAUSED",
            status = "PAUSED",
            currentStepIndex = 1,
            totalSteps = 3,
            completedStepsJson = "[\"CAPABILITY_GRAPH_AUDIT\"]",
            pendingStepsJson = "[\"RUN_TEST_AND_SECURITY_AUDIT\",\"COMPLETE\"]",
            pauseReason = "USER_PAUSED"
        )
        devDao.upsertDevTask(pausedTask)
        val resumed = HomeDeveloperOrchestrator.resumeFromCheckpoint("dev_ckpt_1", app, db)
        assertNotNull(resumed)
        assertEquals("SUCCEEDED", resumed!!.status)
        assertEquals(100, resumed.computeProgressPercent())

        // Cancel quarantines linked DRAFT skill without disabling stable ACTIVE skills
        skillRepo.saveSkillWithSteps(
            JarvisSkillEntity(skillId = "sk_stable", name = "Stable", nameBengali = "স্থায়ী স্কিল", description = "ok", stepCount = 1, status = "ACTIVE"),
            listOf(JarvisSkillStepEntity(skillId = "sk_stable", stepIndex = 1, actionType = "WAIT", descriptionBengali = "wait"))
        )
        skillRepo.saveSkillWithSteps(
            JarvisSkillEntity(skillId = "sk_temp_draft", name = "Draft", nameBengali = "খসড়া স্কিল", description = "draft", stepCount = 1, status = "DRAFT"),
            listOf(JarvisSkillStepEntity(skillId = "sk_temp_draft", stepIndex = 1, actionType = "WAIT", descriptionBengali = "wait"))
        )
        devDao.upsertDevTask(
            DevTaskEntity(taskId = "dev_cancel_1", goal = "Risky draft", status = "WAITING_APPROVAL", draftSkillId = "sk_temp_draft", totalSteps = 2)
        )
        val cancelled = HomeDeveloperOrchestrator.cancelAndQuarantine("dev_cancel_1", app, db)
        assertEquals("CANCELLED", cancelled.status)
        assertEquals("DISABLED", skillRepo.getSkillById("sk_temp_draft")?.status)
        assertEquals("ACTIVE", skillRepo.getSkillById("sk_stable")?.status)
    }

    @Test
    fun schedulerVoiceRoutingAndAgentSelection_behaveAccurately() = runBlocking {
        // 1. Constraint-aware JobScheduler
        val settingsRepo = HomeDeveloperSettingsRepository(app)
        settingsRepo.update {
            it.copy(
                selfDevelopmentEnabled = true,
                autonomyLevel = AutonomyLevel.L4_SCHEDULED_BACKGROUND,
                wifiOnly = true,
                requireCharging = true,
                batteryNotLow = true
            )
        }
        val scheduledOk = HomeDeveloperScheduler.scheduleBackgroundDevelopment(app, settingsRepo.settings.value)
        assertTrue(scheduledOk)
        val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pendingJob = js.allPendingJobs.firstOrNull { it.id == HomeDeveloperScheduler.HOME_DEV_JOB_ID }
        assertNotNull(pendingJob)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, pendingJob!!.networkType)
        assertTrue(pendingJob.isRequireCharging)
        assertTrue(pendingJob.isPersisted)

        HomeDeveloperScheduler.cancelScheduledWork(app)
        assertTrue(js.allPendingJobs.none { it.id == HomeDeveloperScheduler.HOME_DEV_JOB_ID })

        // 2. Specialist selection & Voice command routing
        assertEquals(
            AiAgentRegistry.HOME_DEVELOPER,
            JarvisMasterOrchestrator.selectSpecialistAgent("HOME DEVELOPER কী করছ?")
        )
        val statusRes = VoiceCommandUnderstandingEngine.processCommand(app, "HOME DEVELOPER কী করছ?")
        assertTrue(statusRes.handled)
        assertTrue(statusRes.success)
        assertEquals("getHomeDevStatus", statusRes.toolName)

        val auditRes = VoiceCommandUnderstandingEngine.processCommand(app, "সেল্ফ রিপেয়ার অডিট চালাও")
        assertTrue(auditRes.handled)
        assertEquals("runSelfRepairAudit", auditRes.toolName)
    }

    @Test
    fun homeDeveloperCenterScreen_displaysControlsWithTestTags() {
        val vm = AssistantViewModel(app)
        composeRule.setContent {
            HomeDeveloperCenterScreen(
                viewModel = vm,
                onOpenArtifacts = {},
                onOpenSkills = {},
                onBack = {}
            )
        }
        composeRule.onNodeWithTag("home_dev_back_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_dev_self_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("home_dev_start_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_pause_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_resume_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_cancel_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_open_artifacts_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_view_skills_btn").assertExists()
        composeRule.onNodeWithTag("home_dev_section_1").performClick()
        composeRule.onNodeWithTag("home_dev_learn_submit").assertExists()
        composeRule.onNodeWithTag("home_dev_lazy_list").performScrollToNode(hasTestTag("home_dev_teach_me_btn"))
        composeRule.onNodeWithTag("home_dev_teach_me_btn").assertExists()
    }
}
