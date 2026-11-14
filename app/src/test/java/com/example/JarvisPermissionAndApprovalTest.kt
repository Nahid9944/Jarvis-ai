package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AssistantToolExecutor
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.VoiceCommandUnderstandingEngine
import com.example.automation.*
import com.example.data.local.AssistantDatabase
import com.example.permissions.*
import com.example.tools.ToolExecutionPipeline
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisPermissionAndApprovalTest {

    private lateinit var app: Application
    private lateinit var db: AssistantDatabase
    private lateinit var realToolExecutor: AssistantToolExecutor

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AssistantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        realToolExecutor = AssistantToolExecutor(
            dao = db.assistantDao(),
            onNavigate = {},
            context = app
        )
        ActionApprovalRegistry.resetForTest()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.resetAllSuppression()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @After
    fun tearDown() {
        try {
            ShadowLooper.idleMainLooper()
        } catch (_: Exception) { }
        db.close()
        ActionApprovalRegistry.resetForTest()
        JarvisPermissionManager.resetForTest()
        PermissionOrchestrator.resetAllSuppression()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @Test
    fun t01_grantedRuntimePermissionNeverRepromptsAndMovesToOneTimeConfirmation() = runBlocking(Dispatchers.IO) {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

        val execCount = AtomicInteger(0)
        val res = ToolExecutionPipeline.execute(
            toolName = "makePhoneCall",
            args = JSONObject().put("contactName", "বাবা").put("phoneNumber", "01711000000"),
            context = app,
            caller = "voice"
        ) { _, _ ->
            execCount.incrementAndGet()
            JSONObject().put("success", true)
        }

        // Must NOT prompt OS permission again when CALL_PHONE is GRANTED
        assertNull(PermissionOrchestrator.currentRequest.value)
        assertEquals("CONFIRMATION_REQUIRED", res.optString("status"))
        assertEquals(0, execCount.get())
        assertNotNull(ActionApprovalRegistry.pendingApproval.value)
        assertEquals(ApprovalStatus.PENDING, ActionApprovalRegistry.pendingApproval.value?.approvalStatus)
    }

    @Test
    fun t02_voiceYesExecutesPendingCallExactlyOnceWithZeroLoop() = runBlocking(Dispatchers.IO) {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

        // Step 1: Voice command initiates call -> triggers CONFIRMATION_REQUIRED
        val initial = realToolExecutor.executeToolWithCaller(
            name = "makePhoneCall",
            args = JSONObject().put("contactName", "বাবা").put("phoneNumber", "01711000000"),
            caller = "voice"
        )
        assertEquals("CONFIRMATION_REQUIRED", initial.optString("status"))
        val pendingId = ActionApprovalRegistry.pendingApproval.value?.approvalId
        assertFalse(pendingId.isNullOrBlank())

        // Step 2: User speaks "হ্যাঁ, কল দাও" -> MUST approve and execute IMMEDIATELY ONCE via pipeline
        val confirmOutcome = VoiceCommandUnderstandingEngine.processCommand(
            context = app,
            rawCommand = "হ্যাঁ, কল দাও",
            toolExecutor = realToolExecutor,
            caller = "voice"
        )
        assertTrue("Expected immediate execution on voice confirmation: ${confirmOutcome.feedbackMessage}", confirmOutcome.success)
        assertNull("Pending approval must clear after execution", ActionApprovalRegistry.pendingApproval.value)
        val startedActivity = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull("Expected ACTION_CALL intent to be dispatched once", startedActivity)
        assertEquals(Intent.ACTION_CALL, startedActivity?.action)

        // Step 3: Reusing the exact old approvalId must NOT replay execution
        val replay = realToolExecutor.executeToolWithCaller(
            name = "makePhoneCall",
            args = JSONObject()
                .put("contactName", "বাবা")
                .put("phoneNumber", "01711000000")
                .put("_approvalId", pendingId),
            caller = "voice"
        )
        assertEquals("CONFIRMATION_REQUIRED", replay.optString("status"))
        assertNull("Consumed approval must NOT trigger another ACTION_CALL", Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test
    fun t03_masterOrchestratorHandlesBengaliConfirmationWithoutFallingThroughToChat() = runBlocking(Dispatchers.IO) {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

        val initial = realToolExecutor.executeToolWithCaller(
            name = "makePhoneCall",
            args = JSONObject().put("contactName", "মা").put("phoneNumber", "01712000000"),
            caller = "user_ui"
        )
        assertEquals("CONFIRMATION_REQUIRED", initial.optString("status"))
        assertNotNull(ActionApprovalRegistry.pendingApproval.value)

        val orchResult = JarvisMasterOrchestrator.orchestrate(
            context = app,
            utterance = "অনুমতি দিলাম",
            toolExecutor = realToolExecutor,
            caller = "user_ui"
        )
        assertTrue(orchResult.success)
        assertEquals("SUCCEEDED", orchResult.finalState)
        assertNull(ActionApprovalRegistry.pendingApproval.value)
    }

    @Test
    fun t04_playbookRunnerDoesNotPromptSecondHumanGateWhenPipelineApprovalInFlight() = runBlocking(Dispatchers.IO) {
        val args = JSONObject().put("contactName", "রহিম").put("message", "আসসালামু আলাইকুম")
        val approved = ActionApprovalRegistry.grantExplicitUiApproval("controlWhatsApp", args, "user_ui")
        val acquired = ActionApprovalRegistry.acquireExecutionApproval("controlWhatsApp", args, "user_ui")
        assertNotNull(acquired)
        assertEquals(approved.approvalId, acquired!!.approvalId)
        assertTrue(ActionApprovalRegistry.isApprovalInFlight(acquired.approvalId))

        val playbook = Playbook(
            id = "test_wa_pb",
            titleBn = "Test WhatsApp",
            appPackage = "com.whatsapp",
            risk = RiskLevel.EXTERNAL_COMM,
            steps = listOf(
                Step.Tap(
                    selector = Selector(by = By.TEXT, value = "Send"),
                    dangerous = true
                )
            ),
            verify = VerifySpec(type = VerifyType.CUSTOM)
        )

        val result = PlaybookRunner.execute(
            context = app,
            playbook = playbook,
            params = mapOf("_pipelineApprovalId" to acquired.approvalId)
        )
        // Crucially, HumanGate should never have been requested (code will be STEP_FAILED since no real UI tree in JVM, NOT CONFIRMATION_DECLINED!)
        assertNull("HumanGate must NOT open a second confirmation dialog", HumanGate.pendingRequest.value)
        assertNotEquals("Must not fail due to duplicate confirmation gate", "CONFIRMATION_DECLINED", result.code)
        ActionApprovalRegistry.markExecutionFinished(acquired.approvalId, success = true, verified = true)
    }

    @Test
    fun t05_voiceYesNeverGrantsMissingAndroidOsPermission() = runBlocking(Dispatchers.IO) {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)

        val execCount = AtomicInteger(0)
        val res = ToolExecutionPipeline.execute(
            toolName = "sendSms",
            args = JSONObject().put("phoneNumber", "01711000000").put("message", "হ্যালো"),
            context = app,
            caller = "voice"
        ) { _, _ ->
            execCount.incrementAndGet()
            JSONObject().put("success", true)
        }

        assertEquals("PERMISSION_REQUIRED", res.optString("status"))
        assertEquals(0, execCount.get())

        val approvalAttempt = ActionApprovalRegistry.approvePending("voice")
        assertNull("Voice YES must NOT bypass OS permission block", approvalAttempt)
    }

    @Test
    fun t06_whatsappNeverChecksSendSmsPermission() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)

        val waSendSpecs = JarvisPermissionManager.getRequiredSpecs("sendWhatsAppMessage")
        val waOpenSpecs = JarvisPermissionManager.getRequiredSpecs("openWhatsAppConversation")
        val waControlSpecs = JarvisPermissionManager.getRequiredSpecs("controlWhatsApp")

        assertFalse("sendWhatsAppMessage must not require sms_send", waSendSpecs.any { it.id == "sms_send" })
        assertFalse("openWhatsAppConversation must not require sms_send", waOpenSpecs.any { it.id == "sms_send" })
        assertFalse("controlWhatsApp must not require sms_send", waControlSpecs.any { it.id == "sms_send" })

        val missingOpen = JarvisPermissionManager.getMissingPermissions(app, "openWhatsAppConversation")
        assertTrue("openWhatsAppConversation should have zero missing SMS permissions", missingOpen.isEmpty())
    }

    @Test
    fun t07_onResumeAutoExecutesHeldCommandAfterRuntimePermissionGranted() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)

        val execCount = AtomicInteger(0)
        val executor: suspend (String, JSONObject) -> JSONObject = { _, _ ->
            execCount.incrementAndGet()
            JSONObject().put("success", true).put("verified", true).put("summaryBengali", "অটো-রিজিউম কল সম্পন্ন")
        }

        val blocked = withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "makePhoneCall",
                args = JSONObject().put("contactName", "মা").put("phoneNumber", "01712000000").put("_confirmed", true),
                context = app,
                caller = "user_ui",
                executor = executor
            )
        }
        assertEquals("PERMISSION_REQUIRED", blocked.optString("status"))
        assertNotNull(JarvisPermissionManager.pendingOsAction.value)
        assertEquals(0, execCount.get())

        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val resumedLatch = CompletableDeferred<Boolean>()
        JarvisPermissionManager.onResumeRecheckAndExecute(app) { ok, _ ->
            resumedLatch.complete(ok)
        }

        val deadline = System.currentTimeMillis() + 5_000L
        while (!resumedLatch.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        ShadowLooper.idleMainLooper()

        assertTrue("Auto-resumed action should complete through pipeline", resumedLatch.isCompleted && resumedLatch.await())
        assertNull("Pending OS action must clear once resumed", JarvisPermissionManager.pendingOsAction.value)
        assertEquals(1, execCount.get())
    }

    @Test
    fun t08_parameterAndMessageMutationInvalidatesPriorApproval() = runBlocking(Dispatchers.IO) {
        val originalArgs = JSONObject().put("contactName", "নাহিদ").put("message", "আমি ৫ মিনিটে আসছি")
        val approved = ActionApprovalRegistry.grantExplicitUiApproval("sendSms", originalArgs, "ui_button")

        val modifiedMsgArgs = JSONObject()
            .put("contactName", "নাহিদ")
            .put("message", "টাকা পাঠাও")
            .put("_approvalId", approved.approvalId)

        val acqModified = ActionApprovalRegistry.acquireExecutionApproval("sendSms", modifiedMsgArgs, "voice")
        assertNull("Changed message text MUST invalidate prior approval", acqModified)

        val modifiedRecipientArgs = JSONObject()
            .put("contactName", "অজানা ব্যক্তি")
            .put("message", "আমি ৫ মিনিটে আসছি")
            .put("_approvalId", approved.approvalId)

        val acqWrongRecipient = ActionApprovalRegistry.acquireExecutionApproval("sendSms", modifiedRecipientArgs, "voice")
        assertNull("Changed recipient MUST invalidate prior approval", acqWrongRecipient)

        val exactArgs = JSONObject()
            .put("contactName", "নাহিদ")
            .put("message", "আমি ৫ মিনিটে আসছি")
            .put("_approvalId", approved.approvalId)
        val acqExact = ActionApprovalRegistry.acquireExecutionApproval("sendSms", exactArgs, "voice")
        assertNotNull("Exact matching params must acquire approval", acqExact)
        ActionApprovalRegistry.markExecutionFinished(acqExact?.approvalId, success = true, verified = true)
    }

    @Test
    fun t09_cancelAndEmergencyStopInvalidatePendingAndApprovedActions() {
        val pendingArgs = JSONObject().put("contactName", "বাবা").put("phoneNumber", "01711000000")
        ActionApprovalRegistry.registerPendingConfirmation(
            toolName = "makePhoneCall",
            actionType = "call",
            args = pendingArgs,
            promptBn = "কল দেবো?",
            reasonBn = "Confirm call",
            caller = "voice"
        )
        assertNotNull(ActionApprovalRegistry.pendingApproval.value)

        val rej = ActionApprovalRegistry.rejectPending("voice")
        assertEquals(ApprovalStatus.REJECTED, rej?.approvalStatus)
        assertNull(ActionApprovalRegistry.pendingApproval.value)

        val approved = ActionApprovalRegistry.grantExplicitUiApproval("makePhoneCall", pendingArgs, "ui_button")
        ActionApprovalRegistry.cancelAll("Emergency Stop")
        val afterStop = ActionApprovalRegistry.acquireExecutionApproval(
            "makePhoneCall",
            JSONObject(pendingArgs.toString()).put("_approvalId", approved.approvalId),
            "voice"
        )
        assertNull("Emergency stop must wipe all approved tokens", afterStop)
    }

    @Test
    fun t10_multilingualApprovalClassifierDetectsApproveRejectCorrectionUnrelated() {
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("হ্যাঁ"))
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("হ্যাঁ, পাঠিয়ে দাও"))
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("অনুমতি দিলাম"))
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("ঠিক আছে"))
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("Yes, proceed"))
        assertEquals(ApprovalReplyDecision.APPROVE, ActionApprovalRegistry.classifyApprovalReply("thik ache koro"))

        assertEquals(ApprovalReplyDecision.REJECT, ActionApprovalRegistry.classifyApprovalReply("না"))
        assertEquals(ApprovalReplyDecision.REJECT, ActionApprovalRegistry.classifyApprovalReply("বাতিল করো"))
        assertEquals(ApprovalReplyDecision.REJECT, ActionApprovalRegistry.classifyApprovalReply("Cancel it"))

        assertEquals(ApprovalReplyDecision.CORRECTION, ActionApprovalRegistry.classifyApprovalReply("না, মেসেজটা বদলাও"))
        assertEquals(ApprovalReplyDecision.UNRELATED, ActionApprovalRegistry.classifyApprovalReply("আজকে আবহাওয়া কেমন?"))
    }

    @Test
    fun t11_scopedTrustedLowRiskRuleBlocksSensitiveToolsAndAllowsSafeReadOnly() {
        for (sensitiveTool in listOf("makePhoneCall", "sendSms", "sendWhatsAppMessage", "controlWhatsApp", "deleteMemory")) {
            val (allowed, msg) = ActionApprovalRegistry.setTrustedLowRiskRule(sensitiveTool, "Unsafe test", true)
            assertFalse("$sensitiveTool must NEVER allow trusted auto-approval", allowed)
            assertTrue(msg.contains("নিষিদ্ধ"))
        }

        val (safeOk, _) = ActionApprovalRegistry.setTrustedLowRiskRule("getBatteryStatus", "ব্যাটারি অবস্থা দেখা", true)
        assertTrue(safeOk)
        val acquired = ActionApprovalRegistry.acquireExecutionApproval("getBatteryStatus", JSONObject(), "voice")
        assertNotNull("Safe low-risk tool with active rule should auto-acquire approval", acquired)
    }

    @Test
    fun t12_revokedPermissionDetectedTruthfullyOnRefresh() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        JarvisPermissionManager.refreshSnapshotAndDetectRevocations(app)
        assertTrue(JarvisPermissionManager.revokedNotices.value.isEmpty())

        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        JarvisPermissionManager.refreshSnapshotAndDetectRevocations(app)
        assertTrue(
            "Revoking CAMERA must record an honest notice",
            JarvisPermissionManager.revokedNotices.value.any { it.contains("ক্যামেরা") }
        )
    }

    @Test
    fun t13_executionIdConsentExecutesSubsequentTriggersImmediatelyUntilCompleted(): Unit = runBlocking {
        val execId = "exec-wa-task-999"
        val execCount = AtomicInteger(0)
        val args = JSONObject()
            .put("contactName", "বাবা")
            .put("phoneNumber", "01711000000")
            .put("messageText", "আমি বের হচ্ছি")
            .put("_executionId", execId)

        // 1. First trigger before consent -> requires one-time confirmation bound to execId
        val firstRes = withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "sendWhatsAppMessage",
                args = JSONObject(args.toString()),
                context = app,
                caller = "voice",
                executionId = execId
            ) { _, _ ->
                execCount.incrementAndGet()
                JSONObject().put("success", true).put("verified", true)
            }
        }
        assertEquals("CONFIRMATION_REQUIRED", firstRes.optString("status"))
        assertEquals(execId, firstRes.optString("executionId"))
        assertEquals(0, execCount.get())

        // 2. User approves via JarvisPermissionManager -> binds executionId in ConsentManager
        val approved = JarvisPermissionManager.approveUserAction(source = "voice_yes")
        assertNotNull(approved)
        assertTrue(ConsentManager.hasExecutionConsent(execId, "sendWhatsAppMessage", args))

        // 3. Subsequent trigger #1 and #2 for the SAME executionId -> execute IMMEDIATELY without prompt
        val secondRes = withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "sendWhatsAppMessage",
                args = JSONObject(args.toString()),
                context = app,
                caller = "voice",
                executionId = execId
            ) { _, _ ->
                execCount.incrementAndGet()
                JSONObject().put("success", true).put("verified", true)
            }
        }
        assertTrue("Second trigger for same executionId should execute immediately", secondRes.optBoolean("success"))
        assertEquals(1, execCount.get())

        val thirdRes = withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "sendWhatsAppMessage",
                args = JSONObject(args.toString()),
                context = app,
                caller = "voice",
                executionId = execId
            ) { _, _ ->
                execCount.incrementAndGet()
                JSONObject().put("success", true).put("verified", true)
            }
        }
        assertTrue("Third trigger for same executionId should execute immediately", thirdRes.optBoolean("success"))
        assertEquals(2, execCount.get())

        // 4. Nested ConsentManager.requestConsentIfNeeded for same executionId must NOT open HumanGate
        val nestedAllowed = ConsentManager.requestConsentIfNeeded(
            executionId = execId,
            pipelineApprovalId = null,
            toolName = "runPlaybook",
            actionType = "SEND_MESSAGE",
            target = "বাবা",
            content = "আমি বের হচ্ছি"
        )
        assertTrue(nestedAllowed)
        assertNull(HumanGate.pendingRequest.value)

        // 5. Completing executionId closes consent so future requests require fresh approval
        JarvisPermissionManager.completeExecutionConsent(execId)
        val afterComplete = withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "sendWhatsAppMessage",
                args = JSONObject(args.toString()),
                context = app,
                caller = "voice",
                executionId = execId
            ) { _, _ ->
                execCount.incrementAndGet()
                JSONObject().put("success", true).put("verified", true)
            }
        }
        assertEquals("CONFIRMATION_REQUIRED", afterComplete.optString("status"))
        assertEquals(2, execCount.get())
    }

    @Test
    fun t14_centralPermissionManagerUnifiesSmsAndCallChecksAndPreventsDuplicatePrompts(): Unit = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
        assertFalse(JarvisPermissionManager.hasSendSmsPermission(app))
        assertFalse(com.example.util.JarvisSmsManager.hasSendSmsPermission(app))
        assertFalse(JarvisPermissionManager.hasCallPhonePermission(app))

        Shadows.shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
        assertTrue(JarvisPermissionManager.hasSendSmsPermission(app))
        assertTrue(com.example.util.JarvisSmsManager.hasSendSmsPermission(app))
        assertTrue(JarvisPermissionManager.hasCallPhonePermission(app))

        // Revoke CALL_PHONE and invoke pipeline twice -> must NOT stack duplicate PermissionOrchestrator prompts
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "makePhoneCall",
                args = JSONObject().put("phoneNumber", "01711000000").put("_confirmed", true),
                context = app,
                caller = "user_ui"
            ) { _, _ -> JSONObject().put("success", true) }
        }
        val firstPrompt = PermissionOrchestrator.currentRequest.value
        assertNotNull(firstPrompt)

        withContext(Dispatchers.IO) {
            ToolExecutionPipeline.execute(
                toolName = "makePhoneCall",
                args = JSONObject().put("phoneNumber", "01711000000").put("_confirmed", true),
                context = app,
                caller = "user_ui"
            ) { _, _ -> JSONObject().put("success", true) }
        }
        val secondPrompt = PermissionOrchestrator.currentRequest.value
        assertSame("Duplicate OS permission prompt must be prevented", firstPrompt, secondPrompt)
    }
}
