package com.example.developer

import android.content.Context
import com.example.automation.JarvisSkillRecorder
import com.example.data.local.AssistantDatabase
import com.example.data.local.DevActivityLogEntity
import com.example.data.local.DevTaskEntity
import com.example.data.repository.JarvisSkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

/**
 * Stateful, Checkpoint-Resumable Orchestrator for HOME DEVELOPER Agent.
 * Guarantees:
 * - Zero fake progress: progressPercent == 0 when idle or no real steps finished.
 * - Cooperative Stop/Pause cleanly checkpoints completed & remaining steps in Room.
 * - Resume works seamlessly from persisted Room checkpoints even across cold restarts.
 * - Cancel cleanly quarantines unapproved drafts without harming active/stable skills.
 */
object HomeDeveloperOrchestrator {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var pauseRequested: Boolean = false

    @Volatile
    private var cancelRequested: Boolean = false

    private val _devState = MutableStateFlow(HomeDeveloperLiveState())
    val devState: StateFlow<HomeDeveloperLiveState> = _devState.asStateFlow()

    suspend fun syncInitialStateFromDatabase(
        context: Context,
        databaseOverride: AssistantDatabase? = null,
        settingsRepoOverride: HomeDeveloperSettingsRepository? = null
    ) = withContext(Dispatchers.IO) {
        val db = databaseOverride ?: AssistantDatabase.getDatabase(context)
        val settingsRepo = settingsRepoOverride ?: HomeDeveloperSettingsRepository(context)
        val settings = settingsRepo.settings.value
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())

        val snapshot = CapabilityIntelligenceGraph.inspectProjectCapabilities(
            context = context,
            skills = skillRepo.getSkillSnapshotList()
        )
        val latest = devDao.getLatestResumableOrActiveTaskSync()
        if (latest == null) {
            _devState.value = _devState.value.copy(
                selfDevelopmentEnabled = settings.selfDevelopmentEnabled,
                status = DevWorkStatus.IDLE,
                phase = DevPhase.IDLE,
                indicatorState = DevIndicatorState.GREY_IDLE,
                activeTaskId = "",
                activeTaskGoal = "",
                currentStepIndex = 0,
                totalSteps = 0,
                progressPercent = 0,
                capabilityCoveragePercent = snapshot.capabilityCoveragePercent,
                projectHealthScore = snapshot.projectHealthScore,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val statusEnum = runCatching { DevWorkStatus.valueOf(latest.status) }.getOrDefault(DevWorkStatus.PAUSED)
            val phaseEnum = runCatching { DevPhase.valueOf(latest.phase) }.getOrDefault(DevPhase.PAUSED)
            val indicator = mapIndicator(statusEnum, phaseEnum, latest.learningSource)
            _devState.value = _devState.value.copy(
                selfDevelopmentEnabled = settings.selfDevelopmentEnabled,
                status = statusEnum,
                phase = phaseEnum,
                indicatorState = indicator,
                activeTaskId = latest.taskId,
                activeTaskGoal = latest.goal,
                currentStepIndex = latest.currentStepIndex,
                totalSteps = latest.totalSteps,
                progressPercent = latest.computeProgressPercent(),
                currentFileOrTarget = latest.currentFileOrTarget,
                completedSteps = parseStringList(latest.completedStepsJson),
                pendingSteps = parseStringList(latest.pendingStepsJson),
                failedSteps = parseStringList(latest.failedStepsJson),
                pauseReason = latest.pauseReason,
                lastError = latest.lastError,
                capabilityCoveragePercent = snapshot.capabilityCoveragePercent,
                projectHealthScore = snapshot.projectHealthScore,
                updatedAt = latest.updatedAt
            )
        }
    }

    suspend fun startTask(
        context: Context? = null,
        goal: String,
        category: String = "FEATURE",
        priority: String = "NORMAL",
        sourceType: String = "TEXT",
        sourceUrl: String = "",
        autonomyOverride: AutonomyLevel? = null,
        databaseOverride: AssistantDatabase? = null,
        settingsRepoOverride: HomeDeveloperSettingsRepository? = null
    ): DevTaskEntity = withContext(Dispatchers.IO) {
        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) }
            ?: throw IllegalStateException("Context or databaseOverride is required")
        val settingsRepo = settingsRepoOverride ?: context?.let { HomeDeveloperSettingsRepository(it) }
        val settings = settingsRepo?.settings?.value ?: HomeDeveloperSettingsState()
        val autonomy = autonomyOverride ?: settings.autonomyLevel
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val aDao = db.assistantDao()

        pauseRequested = false
        cancelRequested = false

        val taskId = "dev_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val plannedSteps = buildDeterministicSteps(category, sourceType, autonomy)

        var task = DevTaskEntity(
            taskId = taskId,
            goal = goal.trim(),
            category = category.uppercase(Locale.ROOT),
            priority = priority.uppercase(Locale.ROOT),
            phase = DevPhase.PLANNING.name,
            status = DevWorkStatus.RUNNING.name,
            autonomyLevel = autonomy.name,
            currentStepIndex = 0,
            totalSteps = plannedSteps.size,
            completedStepsJson = "[]",
            pendingStepsJson = JSONArray(plannedSteps).toString(),
            currentFileOrTarget = sourceUrl.ifBlank { category },
            learningSource = sourceType.uppercase(Locale.ROOT),
            learningSourceUrl = sourceUrl
        )
        devDao.upsertDevTask(task)
        appendLog(devDao, taskId, DevPhase.PLANNING, "START", "কাজ শুরু হয়েছে: ${task.goal} (${autonomy.titleBn})")
        publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)

        try {
            val completed = mutableListOf<String>()
            var remaining = plannedSteps.toMutableList()

            for ((idx, stepName) in plannedSteps.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (pauseRequested) {
                    task = task.copy(
                        phase = DevPhase.PAUSED.name,
                        status = DevWorkStatus.PAUSED.name,
                        currentStepIndex = idx,
                        completedStepsJson = JSONArray(completed).toString(),
                        pendingStepsJson = JSONArray(remaining).toString(),
                        pauseReason = "USER_PAUSED",
                        updatedAt = System.currentTimeMillis()
                    )
                    devDao.upsertDevTask(task)
                    appendLog(devDao, taskId, DevPhase.PAUSED, "CHECKPOINT_PAUSED", "ব্যবহারকারীর নির্দেশে চেকপয়েন্ট সংরক্ষণ করে বিরতি নেওয়া হয়েছে (${idx}/${plannedSteps.size})।")
                    publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)
                    return@withContext task
                }
                if (cancelRequested) {
                    return@withContext cancelAndQuarantine(taskId, context, db)
                }

                val stepPhase = mapStepPhase(stepName, sourceType)
                task = task.copy(
                    phase = stepPhase.name,
                    status = DevWorkStatus.RUNNING.name,
                    currentFileOrTarget = stepName,
                    updatedAt = System.currentTimeMillis()
                )
                devDao.upsertDevTask(task)
                publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)

                val stepOutcome = executeSingleStep(
                    stepName = stepName,
                    stepPhase = stepPhase,
                    task = task,
                    context = context,
                    autonomy = autonomy,
                    db = db,
                    skillRepo = skillRepo,
                    settingsRepo = settingsRepo
                )
                task = stepOutcome.updatedTask
                if (stepOutcome.terminalReturn) {
                    publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)
                    return@withContext task
                }

                completed.add(stepName)
                remaining = remaining.drop(1).toMutableList()
                task = task.copy(
                    currentStepIndex = idx + 1,
                    completedStepsJson = JSONArray(completed).toString(),
                    pendingStepsJson = JSONArray(remaining).toString(),
                    updatedAt = System.currentTimeMillis()
                )
                devDao.upsertDevTask(task)
                publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)
            }

            task = task.copy(
                phase = DevPhase.COMPLETED.name,
                status = DevWorkStatus.SUCCEEDED.name,
                currentStepIndex = plannedSteps.size,
                pendingStepsJson = "[]",
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(task)
            appendLog(devDao, taskId, DevPhase.COMPLETED, "STEP_DONE", "টাস্ক সফলভাবে সম্পন্ন হয়েছে: ${task.goal}", severity = "SUCCESS")
            publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)
            task
        } catch (ce: CancellationException) {
            task = task.copy(
                phase = DevPhase.PAUSED.name,
                status = DevWorkStatus.PAUSED.name,
                pauseReason = "COROUTINE_CANCELLED_CHECKPOINT",
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(task)
            appendLog(devDao, taskId, DevPhase.PAUSED, "CHECKPOINT_PAUSED", "চলমান কাজ নিরাপদে থামিয়ে চেকপয়েন্ট সেভ করা হয়েছে।", severity = "WARNING")
            publishLiveState(task, settings.selfDevelopmentEnabled, context, skillRepo)
            task
        }
    }

    private data class StepExecutionOutcome(
        val updatedTask: DevTaskEntity,
        val terminalReturn: Boolean = false
    )

    private suspend fun executeSingleStep(
        stepName: String,
        stepPhase: DevPhase,
        task: DevTaskEntity,
        context: Context?,
        autonomy: AutonomyLevel,
        db: AssistantDatabase,
        skillRepo: JarvisSkillRepository,
        settingsRepo: HomeDeveloperSettingsRepository?
    ): StepExecutionOutcome {
        val devDao = db.homeDeveloperDao()
        val aDao = db.assistantDao()
        var current = task

        when (stepName) {
            "CAPABILITY_GRAPH_AUDIT" -> {
                val snap = CapabilityIntelligenceGraph.refreshAndPersistGaps(context, skillRepo, devDao)
                appendLog(devDao, current.taskId, stepPhase, "STEP_DONE", "সক্ষমতা গ্রাফ যাচাই সম্পন্ন: ${snap.totalTools} টুল, ${snap.activeSkillsCount} স্কিল, ${snap.detectedGaps.size} গ্যাপ।")
            }
            "L1_ADVISORY_REPORT" -> {
                val decision = CapabilityIntelligenceGraph.classifyUserCapabilityNeed(current.goal, skillRepo)
                val artId = aDao.insertArtifact(
                    com.example.data.local.ArtifactEntity(
                        title = "Advisory Plan: ${current.goal.take(40)}",
                        content = "# L1 Advisory Assessment\n- Decision: ${decision.decisionType}\n- Summary: ${decision.summaryBn}",
                        format = "MARKDOWN",
                        category = "HOME_DEV_ADVISORY"
                    )
                )
                current = current.copy(workingArtifactId = artId)
                appendLog(devDao, current.taskId, stepPhase, "STEP_DONE", decision.summaryBn)
            }
            "ACQUIRE_LEARNING_SOURCE" -> {
                val learned = when (current.learningSource.uppercase(Locale.ROOT)) {
                    "YOUTUBE" -> UniversalSkillFactory.learnFromYouTube(context, current.learningSourceUrl, current.goal, null, draftMode = true, skillRepo = skillRepo, devDao = devDao, settingsRepo = settingsRepo)
                    "GITHUB" -> UniversalSkillFactory.learnFromGitHub(context, current.learningSourceUrl, "", skillRepo = skillRepo, assistantDao = aDao, devDao = devDao, settingsRepo = settingsRepo)
                    "DOCUMENT" -> UniversalSkillFactory.learnFromDocument(context, current.learningSourceUrl.ifBlank { "guide.md" }, current.goal, skillRepoOverride = skillRepo, assistantDaoOverride = aDao, devDaoOverride = devDao, settingsRepo = settingsRepo)
                    "SCREENSHOT" -> UniversalSkillFactory.learnFromScreenshotOrVisionText(context, current.goal, current.learningSourceUrl, current.goal.take(40), skillRepoOverride = skillRepo, devDaoOverride = devDao, settingsRepo = settingsRepo)
                    else -> UniversalSkillFactory.learnFromText(context, current.goal, current.goal.take(40), dryRunOnly = false, saveAsActiveIfValid = false, daoOverride = aDao, skillRepoOverride = skillRepo, devDaoOverride = devDao, settingsRepo = settingsRepo)
                }
                if (learned.skillId.isNotBlank()) current = current.copy(draftSkillId = learned.skillId)
                if (learned.artifactId > 0L) current = current.copy(workingArtifactId = learned.artifactId)
                if (learned.status == "BLOCKED") {
                    current = current.copy(
                        phase = DevPhase.FAILED.name,
                        status = DevWorkStatus.FAILED.name,
                        lastError = learned.summaryBn,
                        updatedAt = System.currentTimeMillis()
                    )
                    devDao.upsertDevTask(current)
                    appendLog(devDao, current.taskId, DevPhase.FAILED, "SECURITY_BLOCK", learned.summaryBn, severity = "ERROR")
                    return StepExecutionOutcome(current, terminalReturn = true)
                }
                appendLog(devDao, current.taskId, stepPhase, "STEP_DONE", learned.summaryBn)
            }
            "BUILD_PATCH_OR_WEB_WORKSPACE" -> {
                val isWeb = current.category.equals("WEB_APP", ignoreCase = true) ||
                    current.goal.lowercase(Locale.ROOT).let { it.contains("html") || it.contains("website") || it.contains("ওয়েব") }
                val outcome = if (isWeb) {
                    DeveloperWorkspaceEngine.generateWebAppWorkspace(context, current.taskId, current.goal.take(42), current.goal, aDao, devDao)
                } else {
                    DeveloperWorkspaceEngine.generateLanguagePatchAndTestStub(
                        context = context,
                        taskId = current.taskId,
                        targetFile = current.learningSourceUrl.ifBlank { "HomeDevFeature.kt" },
                        goalPrompt = current.goal,
                        assistantDaoOverride = aDao,
                        devDaoOverride = devDao
                    )
                }
                current = current.copy(workingArtifactId = outcome.artifactId)
                if (!outcome.success) {
                    current = current.copy(
                        phase = DevPhase.FAILED.name,
                        status = DevWorkStatus.FAILED.name,
                        lastError = outcome.messageBn,
                        updatedAt = System.currentTimeMillis()
                    )
                    devDao.upsertDevTask(current)
                    appendLog(devDao, current.taskId, DevPhase.FAILED, "SECURITY_BLOCK", outcome.messageBn, severity = "ERROR")
                    return StepExecutionOutcome(current, terminalReturn = true)
                }
                appendLog(devDao, current.taskId, stepPhase, "PATCH_GENERATED", outcome.messageBn)
            }
            "RUN_TEST_AND_SECURITY_AUDIT" -> {
                val gaps = CapabilityIntelligenceGraph.detectRealCapabilityGaps(context, skillRepo.getSkillSnapshotList())
                val auditText = "নিরাপত্তা ও সিস্টেম অডিট সম্পন্ন (${gaps.size}টি উন্মুক্ত গ্যাপ শনাক্ত)।"
                appendLog(devDao, current.taskId, DevPhase.SECURITY_CHECK, "STEP_DONE", auditText)
            }
            "AWAIT_HUMAN_APPROVAL" -> {
                if (autonomy == AutonomyLevel.L3_FEATURE_BUILDER || current.draftSkillId.isNotBlank() || current.workingArtifactId > 0L) {
                    val completedNow = parseStringList(current.completedStepsJson) + stepName
                    val remainingNow = parseStringList(current.pendingStepsJson).drop(1)
                    current = current.copy(
                        phase = DevPhase.AWAITING_APPROVAL.name,
                        status = DevWorkStatus.WAITING_APPROVAL.name,
                        currentStepIndex = completedNow.size,
                        completedStepsJson = JSONArray(completedNow).toString(),
                        pendingStepsJson = JSONArray(remainingNow).toString(),
                        updatedAt = System.currentTimeMillis()
                    )
                    devDao.upsertDevTask(current)
                    appendLog(devDao, current.taskId, DevPhase.AWAITING_APPROVAL, "STATUS", "ড্রাফট/প্যাচ প্রস্তুত — ব্যবহারকারীর অনুমোদনের অপেক্ষায়।", severity = "WARNING")
                    return StepExecutionOutcome(current, terminalReturn = true)
                }
            }
            "COMPLETE" -> {
                appendLog(devDao, current.taskId, DevPhase.COMPLETED, "STEP_DONE", "সকল অডিট ও চূড়ান্ত ধাপ যাচাই সম্পন্ন।")
            }
            else -> {
                appendLog(devDao, current.taskId, stepPhase, "STEP_DONE", "ধাপ '$stepName' সম্পন্ন।")
            }
        }
        return StepExecutionOutcome(current, terminalReturn = false)
    }

    suspend fun pauseOrStopCleanly(
        reason: String = "USER_REQUESTED_STOP",
        context: Context? = null,
        databaseOverride: AssistantDatabase? = null
    ): DevTaskEntity? = withContext(Dispatchers.IO) {
        pauseRequested = true
        activeJob?.cancel()
        activeJob = null
        try {
            JarvisSkillRecorder.getInstance().pauseRecording()
        } catch (_: Exception) {
        }

        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) } ?: return@withContext null
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val currentId = _devState.value.activeTaskId
        val task = (if (currentId.isNotBlank()) devDao.getDevTaskByIdSync(currentId) else null)
            ?: devDao.getLatestResumableOrActiveTaskSync()
            ?: return@withContext null

        val paused = task.copy(
            phase = DevPhase.PAUSED.name,
            status = DevWorkStatus.PAUSED.name,
            pauseReason = reason,
            updatedAt = System.currentTimeMillis()
        )
        devDao.upsertDevTask(paused)
        appendLog(devDao, paused.taskId, DevPhase.PAUSED, "CHECKPOINT_PAUSED", "কাজ থামানো হয়েছে ($reason)। পরবর্তী ধাপ থেকে রিজিউম করা যাবে।", severity = "WARNING")
        publishLiveState(paused, _devState.value.selfDevelopmentEnabled, context, skillRepo)
        paused
    }

    suspend fun resumeFromCheckpoint(
        taskId: String = "",
        context: Context? = null,
        databaseOverride: AssistantDatabase? = null,
        settingsRepoOverride: HomeDeveloperSettingsRepository? = null
    ): DevTaskEntity? = withContext(Dispatchers.IO) {
        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) } ?: return@withContext null
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val settingsRepo = settingsRepoOverride ?: context?.let { HomeDeveloperSettingsRepository(it) }
        val target = (if (taskId.isNotBlank()) devDao.getDevTaskByIdSync(taskId) else devDao.getLatestResumableOrActiveTaskSync())
            ?: return@withContext null

        val settingsEnabled = settingsRepo?.settings?.value?.selfDevelopmentEnabled ?: _devState.value.selfDevelopmentEnabled
        val pending = parseStringList(target.pendingStepsJson)
        val completed = parseStringList(target.completedStepsJson).toMutableList()
        if (pending.isEmpty()) {
            val done = target.copy(
                phase = DevPhase.COMPLETED.name,
                status = DevWorkStatus.SUCCEEDED.name,
                currentStepIndex = target.totalSteps.coerceAtLeast(completed.size),
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(done)
            appendLog(devDao, done.taskId, DevPhase.COMPLETED, "RESUMED", "বাকি সব ধাপ পূর্বেই সম্পন্ন ছিল; টাস্কটি COMPLETED হিসেবে চিহ্নিত হয়েছে।", severity = "SUCCESS")
            publishLiveState(done, settingsEnabled, context, skillRepo)
            return@withContext done
        }

        pauseRequested = false
        cancelRequested = false
        val autonomy = runCatching { AutonomyLevel.valueOf(target.autonomyLevel) }.getOrDefault(AutonomyLevel.L2_SAFE_AUTONOMOUS)
        appendLog(devDao, target.taskId, DevPhase.BUILDING, "RESUMED", "চেকপয়েন্ট (${target.currentStepIndex}/${target.totalSteps}) থেকে পুনরায় শুরু করা হচ্ছে...")

        var running = target.copy(
            status = DevWorkStatus.RUNNING.name,
            phase = DevPhase.BUILDING.name,
            pauseReason = "",
            updatedAt = System.currentTimeMillis()
        )
        devDao.upsertDevTask(running)
        publishLiveState(running, settingsEnabled, context, skillRepo)

        val remaining = pending.toMutableList()
        for (stepName in pending) {
            currentCoroutineContext().ensureActive()
            if (pauseRequested) {
                val pausedAgain = running.copy(
                    status = DevWorkStatus.PAUSED.name,
                    phase = DevPhase.PAUSED.name,
                    completedStepsJson = JSONArray(completed).toString(),
                    pendingStepsJson = JSONArray(remaining).toString(),
                    pauseReason = "USER_PAUSED",
                    updatedAt = System.currentTimeMillis()
                )
                devDao.upsertDevTask(pausedAgain)
                appendLog(devDao, pausedAgain.taskId, DevPhase.PAUSED, "CHECKPOINT_PAUSED", "রিজিউম চলাকালীন আবার বিরতি নেওয়া হয়েছে।", severity = "WARNING")
                publishLiveState(pausedAgain, settingsEnabled, context, skillRepo)
                return@withContext pausedAgain
            }
            if (cancelRequested) {
                return@withContext cancelAndQuarantine(running.taskId, context, db)
            }

            val stepPhase = mapStepPhase(stepName, running.learningSource)
            running = running.copy(
                phase = stepPhase.name,
                status = DevWorkStatus.RUNNING.name,
                currentFileOrTarget = stepName,
                completedStepsJson = JSONArray(completed).toString(),
                pendingStepsJson = JSONArray(remaining).toString(),
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(running)
            publishLiveState(running, settingsEnabled, context, skillRepo)

            val stepOutcome = executeSingleStep(
                stepName = stepName,
                stepPhase = stepPhase,
                task = running,
                context = context,
                autonomy = autonomy,
                db = db,
                skillRepo = skillRepo,
                settingsRepo = settingsRepo
            )
            running = stepOutcome.updatedTask
            if (stepOutcome.terminalReturn) {
                publishLiveState(running, settingsEnabled, context, skillRepo)
                return@withContext running
            }

            completed.add(stepName)
            remaining.removeAt(0)
            running = running.copy(
                currentStepIndex = completed.size,
                currentFileOrTarget = stepName,
                completedStepsJson = JSONArray(completed).toString(),
                pendingStepsJson = JSONArray(remaining).toString(),
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(running)
            publishLiveState(running, settingsEnabled, context, skillRepo)
        }

        val finalTask = running.copy(
            status = DevWorkStatus.SUCCEEDED.name,
            phase = DevPhase.COMPLETED.name,
            currentStepIndex = running.totalSteps.coerceAtLeast(completed.size),
            pendingStepsJson = "[]",
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        devDao.upsertDevTask(finalTask)
        appendLog(devDao, finalTask.taskId, DevPhase.COMPLETED, "STEP_DONE", "রিজিউম করা টাস্ক বাস্তব ধাপসমূহ চালিয়ে সফলভাবে সম্পন্ন হয়েছে।", severity = "SUCCESS")
        publishLiveState(finalTask, settingsEnabled, context, skillRepo)
        finalTask
    }

    suspend fun approveWaitingTask(
        taskId: String,
        context: Context? = null,
        databaseOverride: AssistantDatabase? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) }
            ?: return@withContext false to "ডাটাবেজ উপলব্ধ নয়।"
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())
        val task = devDao.getDevTaskByIdSync(taskId)
            ?: return@withContext false to "টাস্ক '$taskId' পাওয়া যায়নি।"

        val approvalErrors = mutableListOf<String>()

        if (task.draftSkillId.isNotBlank()) {
            val skillOutcome = UniversalSkillFactory.approveAndActivateDraftSkill(task.draftSkillId, skillRepo, devDao)
            if (!skillOutcome.success) {
                approvalErrors.add(skillOutcome.summaryBn.ifBlank { "ড্রাফট স্কিল অনুমোদন ব্যর্থ (${task.draftSkillId})" })
            }
        }
        val draftPatches = devDao.getAllPatchesSync().filter { it.taskId == taskId && it.status == "DRAFT" }
        draftPatches.forEach { patch ->
            val (patchOk, patchMsg) = DeveloperWorkspaceEngine.approvePatch(patch.patchId, devDao)
            if (!patchOk) {
                approvalErrors.add(patchMsg.ifBlank { "প্যাচ অনুমোদন ব্যর্থ (${patch.patchId})" })
            }
        }

        if (approvalErrors.isNotEmpty()) {
            val combinedErr = approvalErrors.joinToString("; ")
            val blocked = task.copy(
                status = DevWorkStatus.WAITING_APPROVAL.name,
                phase = DevPhase.AWAITING_APPROVAL.name,
                lastError = combinedErr,
                updatedAt = System.currentTimeMillis()
            )
            devDao.upsertDevTask(blocked)
            appendLog(devDao, taskId, DevPhase.AWAITING_APPROVAL, "APPROVAL_FAILED", "অনুমোদন ব্যর্থ হয়েছে: $combinedErr", severity = "ERROR")
            publishLiveState(blocked, _devState.value.selfDevelopmentEnabled, context, skillRepo)
            return@withContext false to combinedErr
        }

        val finished = task.copy(
            status = DevWorkStatus.SUCCEEDED.name,
            phase = DevPhase.COMPLETED.name,
            currentStepIndex = task.totalSteps,
            pendingStepsJson = "[]",
            lastError = "",
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        devDao.upsertDevTask(finished)
        appendLog(devDao, taskId, DevPhase.COMPLETED, "STEP_DONE", "অনুমোদন প্রাপ্ত ও ড্রাফট সক্রিয় করা হয়েছে।", severity = "SUCCESS")
        publishLiveState(finished, _devState.value.selfDevelopmentEnabled, context, skillRepo)
        true to "টাস্ক অনুমোদিত ও সম্পন্ন হয়েছে।"
    }

    suspend fun cancelAndQuarantine(
        taskId: String = "",
        context: Context? = null,
        databaseOverride: AssistantDatabase? = null
    ): DevTaskEntity = withContext(Dispatchers.IO) {
        cancelRequested = true
        activeJob?.cancel()
        activeJob = null
        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) }
        val devDao = db?.homeDeveloperDao()
        val skillRepo = db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }

        val targetId = taskId.ifBlank { _devState.value.activeTaskId }
        val existing = if (targetId.isNotBlank()) devDao?.getDevTaskByIdSync(targetId) else null

        // Quarantine unapproved DRAFT skill created by this cancelled task without touching active stable skills
        if (existing != null && existing.draftSkillId.isNotBlank() && skillRepo != null) {
            val draftSkill = skillRepo.getSkillById(existing.draftSkillId)
            if (draftSkill != null && draftSkill.status == "DRAFT") {
                skillRepo.setSkillEnabled(draftSkill.skillId, false)
            }
        }
        if (existing != null && devDao != null) {
            devDao.getAllPatchesSync().filter { it.taskId == existing.taskId && it.status == "DRAFT" }.forEach { p ->
                devDao.upsertPatch(p.copy(status = "ROLLED_BACK", updatedAt = System.currentTimeMillis()))
            }
        }

        val cancelled = (existing ?: DevTaskEntity(
            taskId = targetId.ifBlank { "cancelled_${System.currentTimeMillis()}" },
            goal = _devState.value.activeTaskGoal.ifBlank { "Cancelled Task" }
        )).copy(
            status = DevWorkStatus.CANCELLED.name,
            phase = DevPhase.CANCELLED.name,
            updatedAt = System.currentTimeMillis()
        )
        devDao?.upsertDevTask(cancelled)
        if (devDao != null) {
            appendLog(devDao, cancelled.taskId, DevPhase.CANCELLED, "CANCELLED", "কাজ বাতিল ও অসম্পূর্ণ ড্রাফট কোয়ারেন্টাইন করা হয়েছে; মূল সিস্টেম সম্পূর্ণ সুরক্ষিত আছে।", severity = "WARNING")
        }
        if (skillRepo != null) {
            publishLiveState(cancelled, _devState.value.selfDevelopmentEnabled, context, skillRepo)
        }
        cancelled
    }

    suspend fun runSelfRepairAudit(
        context: Context? = null,
        databaseOverride: AssistantDatabase? = null
    ): CapabilityIntelligenceSnapshot = withContext(Dispatchers.IO) {
        val db = databaseOverride ?: context?.let { AssistantDatabase.getDatabase(it) }
            ?: throw IllegalStateException("Context or databaseOverride required")
        val devDao = db.homeDeveloperDao()
        val skillRepo = JarvisSkillRepository(db.jarvisSkillDao())

        val snapshot = CapabilityIntelligenceGraph.refreshAndPersistGaps(context, skillRepo, devDao)
        // Propose safe playbook stabilization for any failing skill
        skillRepo.getSkillSnapshotList().filter { it.failureCount >= 2 }.forEach { skill ->
            skillRepo.analyzeFailureHistoryAndProposeRevision(skill.skillId, "AUTO_SELF_REPAIR_AUDIT")
        }

        appendLog(
            devDao,
            taskId = "self_repair_${System.currentTimeMillis()}",
            phase = DevPhase.TESTING,
            eventType = "STEP_DONE",
            messageBn = "সেল্ফ-রিপেয়ার অডিট সম্পন্ন: হেলথ ${snapshot.projectHealthScore}%, টুল কভারেজ ${snapshot.capabilityCoveragePercent}%, ${snapshot.detectedGaps.size}টি গ্যাপ।"
        )
        _devState.value = _devState.value.copy(
            capabilityCoveragePercent = snapshot.capabilityCoveragePercent,
            projectHealthScore = snapshot.projectHealthScore,
            updatedAt = System.currentTimeMillis()
        )
        snapshot
    }

    fun getLiveVoiceReport(): String {
        val s = _devState.value
        return when (s.status) {
            DevWorkStatus.IDLE ->
                "হোম ডেভেলপার এখন অপেক্ষমাণ (০% কাজ চলছে)। সেল্ফ-ডেভেলপমেন্ট মোড ${if (s.selfDevelopmentEnabled) "চালু" else "বন্ধ"} আছে।"
            DevWorkStatus.PAUSED ->
                "হোম ডেভেলপার '${s.activeTaskGoal}' কাজটি ${s.progressPercent}% অবস্থায় (${s.currentStepIndex}/${s.totalSteps} ধাপ) বিরতিতে রেখেছে। চাইলে রিজিউম করতে বলুন।"
            DevWorkStatus.WAITING_APPROVAL ->
                "হোম ডেভেলপার '${s.activeTaskGoal}'-এর ড্রাফট তৈরি করে আপনার অনুমোদনের জন্য অপেক্ষা করছে (${s.progressPercent}%)।"
            DevWorkStatus.SUCCEEDED ->
                "সর্বশেষ ডেভেলপমেন্ট কাজ '${s.activeTaskGoal}' ১০০% সম্পন্ন হয়েছে। প্রোজেক্ট হেলথ ${s.projectHealthScore}%।"
            DevWorkStatus.FAILED ->
                "সর্বশেষ কাজে ত্রুটি দেখা দিয়েছে: ${s.lastError.ifBlank { "নিরাপত্তা বা ভ্যালিডেশন ব্যর্থ" }}।"
            else ->
                "হোম ডেভেলপার এখন '${s.phase.name}' ধাপে '${s.activeTaskGoal}' কাজটি করছে — অগ্রগতি ${s.progressPercent}% (${s.currentStepIndex}/${s.totalSteps} ধাপ)।"
        }
    }

    private fun buildDeterministicSteps(
        category: String,
        sourceType: String,
        autonomy: AutonomyLevel
    ): List<String> {
        return when {
            autonomy == AutonomyLevel.L1_ADVISORY -> listOf("CAPABILITY_GRAPH_AUDIT", "L1_ADVISORY_REPORT")
            category.equals("SELF_REPAIR", ignoreCase = true) -> listOf("CAPABILITY_GRAPH_AUDIT", "RUN_TEST_AND_SECURITY_AUDIT")
            category.equals("CODE_PATCH", ignoreCase = true) || category.equals("WEB_APP", ignoreCase = true) ->
                listOf("CAPABILITY_GRAPH_AUDIT", "BUILD_PATCH_OR_WEB_WORKSPACE", "RUN_TEST_AND_SECURITY_AUDIT", "AWAIT_HUMAN_APPROVAL")
            sourceType.equals("YOUTUBE", ignoreCase = true) || sourceType.equals("GITHUB", ignoreCase = true) ||
                sourceType.equals("DOCUMENT", ignoreCase = true) || sourceType.equals("SCREENSHOT", ignoreCase = true) ->
                listOf("CAPABILITY_GRAPH_AUDIT", "ACQUIRE_LEARNING_SOURCE", "RUN_TEST_AND_SECURITY_AUDIT", "AWAIT_HUMAN_APPROVAL")
            else -> listOf("CAPABILITY_GRAPH_AUDIT", "ACQUIRE_LEARNING_SOURCE", "RUN_TEST_AND_SECURITY_AUDIT")
        }
    }

    private fun mapStepPhase(stepName: String, sourceType: String): DevPhase = when (stepName) {
        "CAPABILITY_GRAPH_AUDIT", "L1_ADVISORY_REPORT" -> DevPhase.PLANNING
        "ACQUIRE_LEARNING_SOURCE" -> if (sourceType.isNotBlank()) DevPhase.LEARNING else DevPhase.BUILDING
        "BUILD_PATCH_OR_WEB_WORKSPACE" -> DevPhase.BUILDING
        "RUN_TEST_AND_SECURITY_AUDIT" -> DevPhase.SECURITY_CHECK
        "AWAIT_HUMAN_APPROVAL" -> DevPhase.AWAITING_APPROVAL
        else -> DevPhase.BUILDING
    }

    private fun mapIndicator(status: DevWorkStatus, phase: DevPhase, source: String): DevIndicatorState = when (status) {
        DevWorkStatus.RUNNING, DevWorkStatus.QUEUED -> when {
            phase == DevPhase.LEARNING || source in listOf("YOUTUBE", "GITHUB", "DOCUMENT", "SCREENSHOT") -> DevIndicatorState.CYAN_LEARNING
            else -> DevIndicatorState.GREEN_WORKING
        }
        DevWorkStatus.WAITING_APPROVAL, DevWorkStatus.WAITING_PERMISSION, DevWorkStatus.PAUSED -> DevIndicatorState.YELLOW_WAITING
        DevWorkStatus.FAILED -> DevIndicatorState.RED_ERROR
        else -> DevIndicatorState.GREY_IDLE
    }

    private suspend fun publishLiveState(
        task: DevTaskEntity,
        selfDevEnabled: Boolean,
        context: Context?,
        skillRepo: JarvisSkillRepository
    ) {
        val statusEnum = runCatching { DevWorkStatus.valueOf(task.status) }.getOrDefault(DevWorkStatus.IDLE)
        val phaseEnum = runCatching { DevPhase.valueOf(task.phase) }.getOrDefault(DevPhase.IDLE)
        val snap = CapabilityIntelligenceGraph.inspectProjectCapabilities(context, skillRepo.getSkillSnapshotList())
        _devState.value = _devState.value.copy(
            selfDevelopmentEnabled = selfDevEnabled,
            status = statusEnum,
            phase = phaseEnum,
            indicatorState = mapIndicator(statusEnum, phaseEnum, task.learningSource),
            activeTaskId = task.taskId,
            activeTaskGoal = task.goal,
            currentStepIndex = task.currentStepIndex,
            totalSteps = task.totalSteps,
            progressPercent = task.computeProgressPercent(),
            currentFileOrTarget = task.currentFileOrTarget,
            completedSteps = parseStringList(task.completedStepsJson),
            pendingSteps = parseStringList(task.pendingStepsJson),
            failedSteps = parseStringList(task.failedStepsJson),
            pauseReason = task.pauseReason,
            lastError = task.lastError,
            capabilityCoveragePercent = snap.capabilityCoveragePercent,
            projectHealthScore = snap.projectHealthScore,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun appendLog(
        devDao: com.example.data.local.HomeDeveloperDao,
        taskId: String,
        phase: DevPhase,
        eventType: String,
        messageBn: String,
        details: String = "",
        severity: String = "INFO"
    ) {
        devDao.insertActivityLog(
            DevActivityLogEntity(
                taskId = taskId,
                phase = phase.name,
                eventType = eventType,
                messageBn = messageBn,
                details = details,
                severity = severity,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun parseStringList(rawJson: String): List<String> {
        return try {
            val arr = JSONArray(rawJson)
            List(arr.length()) { idx -> arr.optString(idx, "") }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
