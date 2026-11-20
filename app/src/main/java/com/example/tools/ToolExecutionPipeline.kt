package com.example.tools

import android.content.Context
import com.example.permissions.ActionApprovalRecord
import com.example.permissions.ActionApprovalRegistry
import com.example.permissions.JarvisActionState
import com.example.permissions.JarvisPermissionManager
import com.example.permissions.PermissionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Centralized execution pipeline for all Jarvis tools.
 *
 * State Machine & Pipeline Stages:
 * REQUEST_RECEIVED -> ACTION_RESOLVED -> PARAMETERS_VALIDATED ->
 * 1. OS Permission Check (JarvisPermissionManager - real OS checks, WhatsApp != SMS, auto-resume via pipeline)
 * 2. One-Time Exact User Approval Gate (ActionApprovalRegistry - prevents double prompt & parameter mutation)
 * 2.5. Atomic Pre-Dispatch Commit Boundary (ExecutionCommitController)
 * 3. Core Execution
 * 4. Result Verification (ToolResultVerifier)
 * 5. Approval Consumption & Audit Logging
 */
object ToolExecutionPipeline {

    data class PermissionCheckResult(
        val permissionId: String,
        val permissionName: String,
        val responseJson: JSONObject
    )

    data class ConfirmationCheckResult(
        val promptBn: String,
        val approvalRecord: ActionApprovalRecord,
        val responseJson: JSONObject
    )

    suspend fun execute(
        toolName: String,
        args: JSONObject,
        context: Context?,
        caller: String = "voice",
        executionId: String = args.optString("_executionId", "").ifBlank { UUID.randomUUID().toString() },
        executor: suspend (String, JSONObject) -> JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val resolvedExecutionId = executionId.ifBlank { UUID.randomUUID().toString() }
        val spec = ToolRegistry.get(toolName)
        val category = spec?.category ?: ToolCategory.SYSTEM_ACTIONS
        val paramsStr = try { args.toString() } catch (_: Throwable) { "{}" } ?: "{}"

        ActionApprovalRegistry.updateActionState(JarvisActionState.ACTION_RESOLVED)

        // -------------------------------------------------------------
        // STAGE 0: COMMIT CONTROLLER REGISTRATION & INTERRUPTION GUARD
        // -------------------------------------------------------------
        val actionId = UUID.randomUUID().toString()
        com.example.ai.ExecutionCommitController.registerAction(actionId, toolName)

        if (com.example.util.JarvisEmergencyStopCoordinator.isEmergencyStopActive.value) {
            com.example.ai.ExecutionCommitController.cancelAction(actionId)
            ActionApprovalRegistry.cancelAll("জরুরি বিরতি সক্রিয়")
            return@withContext JSONObject().apply {
                put("executionId", resolvedExecutionId)
                put("latencyMs", System.currentTimeMillis() - startTime)
                put("status", "CANCELLED_BEFORE_DISPATCH")
                put("actionStatus", "CANCELLED_BEFORE_DISPATCH")
                put("errorCode", "EMERGENCY_STOP_ACTIVE")
                put("executionState", JarvisActionState.CANCELLED.name)
                put("success", false)
                put("verified", false)
                put("verificationStatus", "CANCELLED_BEFORE_DISPATCH")
                put("summaryBengali", "জরুরি বিরতি সক্রিয় থাকায় '$toolName' কাজটি শুরু হওয়ার আগেই বাতিল করা হয়েছে।")
            }
        }

        // -------------------------------------------------------------
        // STAGE 1: REAL OS PERMISSION PRE-CHECK & AUTO-RESUME HOLD
        // -------------------------------------------------------------
        ActionApprovalRegistry.updateActionState(JarvisActionState.OS_PERMISSION_CHECKED)
        val permissionResult = checkPermissions(resolvedExecutionId, toolName, spec, args, context, caller, executor)
        if (permissionResult != null) {
            com.example.ai.ExecutionCommitController.cancelAction(actionId)
            val duration = System.currentTimeMillis() - startTime
            ToolAuditLogger.log(
                ToolAuditRecord(
                    toolName = toolName,
                    category = category,
                    paramsJson = paramsStr,
                    caller = caller,
                    status = ExecutionStatus.PERMISSION_REQUIRED,
                    durationMs = duration,
                    permissionId = permissionResult.permissionId,
                    errorMessage = "Missing permission: ${permissionResult.permissionName}"
                )
            )
            return@withContext permissionResult.responseJson.apply {
                put("executionId", resolvedExecutionId)
                put("latencyMs", duration)
                put("errorCode", "PERMISSION_REQUIRED")
                put("actionStatus", "PERMISSION_REQUIRED")
            }
        }

        // -------------------------------------------------------------
        // STAGE 2: ONE-TIME EXACT USER APPROVAL GATE & EXECUTION-ID CONSENT
        // -------------------------------------------------------------
        val (confirmationBlocked, approvedRecord) = checkConfirmation(
            executionId = resolvedExecutionId,
            toolName = toolName,
            spec = spec,
            args = args,
            caller = caller
        )
        if (confirmationBlocked != null) {
            com.example.ai.ExecutionCommitController.cancelAction(actionId)
            val duration = System.currentTimeMillis() - startTime
            ToolAuditLogger.log(
                ToolAuditRecord(
                    toolName = toolName,
                    category = category,
                    paramsJson = paramsStr,
                    caller = caller,
                    status = ExecutionStatus.CONFIRMATION_REQUIRED,
                    durationMs = duration,
                    confirmationRequired = true,
                    errorMessage = "Confirmation required: ${confirmationBlocked.promptBn}"
                )
            )
            return@withContext confirmationBlocked.responseJson.apply {
                put("executionId", resolvedExecutionId)
                put("latencyMs", duration)
                put("errorCode", "CONFIRMATION_REQUIRED")
                put("actionStatus", "CONFIRMATION_REQUIRED")
            }
        }

        if (approvedRecord != null) {
            args.put("_pipelineApprovalId", approvedRecord.approvalId)
            args.put("_executionId", resolvedExecutionId)
        }

        // -------------------------------------------------------------
        // STAGE 2.5: ATOMIC PRE-DISPATCH COMMIT BOUNDARY
        // -------------------------------------------------------------
        val activeTask = com.example.ai.ActiveTaskInterruptionManager.currentTask.value
        val activeTaskBlocked = activeTask != null &&
            activeTask.status in listOf(
                com.example.ai.TaskStatus.CANCELLED,
                com.example.ai.TaskStatus.CANCELLING,
                com.example.ai.TaskStatus.PAUSED,
                com.example.ai.TaskStatus.PAUSING
            ) &&
            toolName != "emergencyStopAllWork"

        if (activeTaskBlocked || !com.example.ai.ExecutionCommitController.canDispatchAction(actionId)) {
            com.example.ai.ExecutionCommitController.cancelAction(actionId)
            ActionApprovalRegistry.markExecutionFinished(approvedRecord?.approvalId, success = false, verified = false)
            return@withContext JSONObject().apply {
                put("executionId", resolvedExecutionId)
                put("latencyMs", System.currentTimeMillis() - startTime)
                put("status", "CANCELLED_BEFORE_DISPATCH")
                put("actionStatus", "CANCELLED_BEFORE_DISPATCH")
                put("errorCode", "INTERRUPTION_CANCELLED")
                put("executionState", JarvisActionState.CANCELLED.name)
                put("success", false)
                put("verified", false)
                put("verificationStatus", "CANCELLED_BEFORE_DISPATCH")
                put("summaryBengali", "'$toolName' কার্যকর হওয়ার আগেই ব্যবহারকারীর ইন্টারাপশন/বাতিলের কারণে থামানো হয়েছে।")
            }
        }

        com.example.ai.ExecutionCommitController.markDispatched(actionId)
        ActionApprovalRegistry.updateActionState(JarvisActionState.DISPATCHED)

        // -------------------------------------------------------------
        // STAGE 3: CORE EXECUTION
        // -------------------------------------------------------------
        val rawResult: JSONObject = try {
            executor(toolName, args)
        } catch (e: Exception) {
            com.example.ai.ExecutionCommitController.markCompleted(actionId)
            ActionApprovalRegistry.markExecutionFinished(approvedRecord?.approvalId, success = false, verified = false)
            val duration = System.currentTimeMillis() - startTime
            ToolAuditLogger.log(
                ToolAuditRecord(
                    toolName = toolName,
                    category = category,
                    paramsJson = paramsStr,
                    caller = caller,
                    status = ExecutionStatus.FAILED,
                    durationMs = duration,
                    errorMessage = e.message ?: "Execution error"
                )
            )
            return@withContext JSONObject().apply {
                put("executionId", resolvedExecutionId)
                put("latencyMs", duration)
                put("error", true)
                put("errorCode", "EXECUTION_EXCEPTION")
                put("status", "FAILED")
                put("actionStatus", "FAILED")
                put("executionState", JarvisActionState.FAILED.name)
                put("success", false)
                put("message", "টুল এক্সিকিউশনে সমস্যা: ${e.message}")
                put("summaryBengali", "দুঃখিত, '$toolName' সম্পন্ন করার সময় একটি অপ্রত্যাশিত ত্রুটি ঘটেছে।")
                put("verified", false)
                put("verificationStatus", "EXECUTION_EXCEPTION")
                put("verificationDetails", "এক্সিকিউশনের সময় ব্যতিক্রম ত্রুটি: ${e.message}")
            }
        }

        com.example.ai.ExecutionCommitController.markCompleted(actionId)
        ActionApprovalRegistry.updateActionState(JarvisActionState.VERIFYING)

        // -------------------------------------------------------------
        // STAGE 4: RESULT VERIFICATION
        // -------------------------------------------------------------
        val verifiedResult = ToolResultVerifier.verifyAndEnrich(toolName, spec, args, rawResult)

        // -------------------------------------------------------------
        // STAGE 5: CONSUMPTION & AUDIT LOGGING
        // -------------------------------------------------------------
        val duration = System.currentTimeMillis() - startTime
        val resultStatus = verifiedResult.optString("status", "")
        val vStatus = verifiedResult.optString("verificationStatus", resultStatus)
        val isExplicitVerified = verifiedResult.optBoolean("verified", false)
        val isResultSuccess = verifiedResult.optBoolean("success", false)
        val isVerifiedSuccess = !verifiedResult.optBoolean("error", false) &&
            vStatus != "FAILED" &&
            vStatus != "ERROR" &&
            vStatus != "UNVERIFIED" &&
            isExplicitVerified &&
            isResultSuccess

        ActionApprovalRegistry.markExecutionFinished(
            approvalId = approvedRecord?.approvalId,
            success = isResultSuccess && !verifiedResult.optBoolean("error", false),
            verified = isExplicitVerified
        )

        val auditStatus = when (resultStatus) {
            "SUCCESS" -> ExecutionStatus.SUCCESS
            "PARTIAL_SUCCESS" -> ExecutionStatus.PARTIAL_SUCCESS
            "UNVERIFIED", "REQUEST_ACCEPTED", "MULTIPLE_MATCHES", "TRANSCRIPT_REQUIRED", "PARAMETERS_REQUIRED", "AWAITING_PROJECTION_CONSENT" -> ExecutionStatus.UNVERIFIED
            "PERMISSION_REQUIRED" -> ExecutionStatus.PERMISSION_REQUIRED
            "CONFIRMATION_REQUIRED" -> ExecutionStatus.CONFIRMATION_REQUIRED
            else -> if (isVerifiedSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
        }

        val preview = if (verifiedResult.length() > 0) verifiedResult.toString().take(200) else "OK"

        ToolAuditLogger.log(
            ToolAuditRecord(
                toolName = toolName,
                category = category,
                paramsJson = paramsStr,
                caller = caller,
                status = auditStatus,
                durationMs = duration,
                resultPreview = preview,
                errorMessage = if (auditStatus == ExecutionStatus.FAILED) {
                    verifiedResult.optString("error", verifiedResult.optString("message", "Verification failed"))
                } else null
            )
        )

        verifiedResult.put("executionId", resolvedExecutionId)
        verifiedResult.put("latencyMs", duration)
        if (!verifiedResult.has("actionStatus")) {
            verifiedResult.put("actionStatus", verifiedResult.optString("status", "UNKNOWN"))
        }
        return@withContext verifiedResult
    }

    private fun checkPermissions(
        executionId: String,
        toolName: String,
        spec: ToolSpec?,
        args: JSONObject,
        context: Context?,
        caller: String,
        executor: suspend (String, JSONObject) -> JSONObject
    ): PermissionCheckResult? {
        if (context == null) {
            val requiredSpecs = JarvisPermissionManager.getRequiredSpecs(toolName, spec)
            val firstRequired = requiredSpecs.firstOrNull() ?: return null
            val failClosedJson = JSONObject().apply {
                put("status", "PERMISSION_REQUIRED")
                put("executionState", JarvisActionState.WAITING_FOR_OS_PERMISSION.name)
                put("success", false)
                put("toolName", toolName)
                put("permissionRequired", firstRequired.id)
                put("permissionKind", firstRequired.kind.name)
                put("permissionTitleBn", firstRequired.titleBn)
                put("message", "অ্যান্ড্রয়েড Context ও '${firstRequired.titleBn}' অনুমতি ছাড়া এই কাজ চালানো যাবে না।")
                put(
                    "summaryBengali",
                    "'$toolName' সম্পন্ন করতে সিস্টেম Context এবং '${firstRequired.titleBn}' অনুমতি আবশ্যক।"
                )
                put("verified", false)
                put("verificationStatus", "PERMISSION_BLOCKED_NO_CONTEXT")
                put("verificationDetails", "Context অনুপস্থিত থাকায় নিরাপত্তা নীতির কারণে '${firstRequired.titleBn}' ব্লক করা হয়েছে।")
            }
            return PermissionCheckResult(
                permissionId = firstRequired.id,
                permissionName = firstRequired.titleBn,
                responseJson = failClosedJson
            )
        }

        val missingSpecs = JarvisPermissionManager.getMissingPermissions(context, toolName, spec)
        val missingSpec = missingSpecs.firstOrNull() ?: return null

        val existingApprovalId = args.optString("_approvalId", "").ifBlank { null }

        JarvisPermissionManager.holdForMissingPermission(
            context = context,
            executionId = executionId,
            toolName = toolName,
            descriptionBn = spec?.titleBn ?: toolName,
            args = args,
            caller = caller,
            missingSpecs = missingSpecs,
            approvedIdIfAny = existingApprovalId,
            executor = executor
        )

        val json = JSONObject().apply {
            put("status", "PERMISSION_REQUIRED")
            put("executionState", JarvisActionState.WAITING_FOR_OS_PERMISSION.name)
            put("success", false)
            put("toolName", toolName)
            put("permissionRequired", missingSpec.id)
            put("permissionKind", missingSpec.kind.name)
            put("permissionTitleBn", missingSpec.titleBn)
            put("message", "এই কাজটি সম্পাদন করতে '${missingSpec.titleBn}' অনুমতি প্রয়োজন।")
            put(
                "summaryBengali",
                "'$toolName' সম্পন্ন করতে সিস্টেম পারমিশন '${missingSpec.titleBn}' প্রয়োজন। অনুমতি দিলে কাজটি স্বয়ংক্রিয়ভাবে সম্পন্ন হবে।"
            )
            put("verified", false)
            put("verificationStatus", "PERMISSION_BLOCKED")
            put("verificationDetails", "অনুমতি '${missingSpec.titleBn}' অনুপস্থিত থাকায় পেন্ডিং কিউতে সংরক্ষিত আছে।")
        }

        return PermissionCheckResult(
            permissionId = missingSpec.id,
            permissionName = missingSpec.titleBn,
            responseJson = json
        )
    }

    private fun checkConfirmation(
        executionId: String,
        toolName: String,
        spec: ToolSpec?,
        args: JSONObject,
        caller: String
    ): Pair<ConfirmationCheckResult?, ActionApprovalRecord?> {
        val level = spec?.confirmationLevel ?: ConfirmationLevel.NONE
        val isHumanGate = level == ConfirmationLevel.HUMAN_GATE || isKnownDestructiveTool(toolName)

        val requiresConfirm = isHumanGate ||
            level == ConfirmationLevel.EXPLICIT_CONFIRM ||
            level == ConfirmationLevel.BIOMETRIC

        if (!requiresConfirm) return Pair(null, null)

        // Check centralized JarvisPermissionManager (ConsentManager + ActionApprovalRegistry)
        val approved = JarvisPermissionManager.acquireActionApproval(executionId, toolName, args, caller)
        if (approved != null) {
            return Pair(null, approved)
        }

        // Not approved yet -> register ONE structured Pending Approval Record
        val promptBn = buildConfirmationPrompt(toolName, spec, args)
        val pendingRecord = JarvisPermissionManager.registerPendingUserApproval(
            executionId = executionId,
            toolName = toolName,
            actionType = spec?.category?.name ?: "SENSITIVE_ACTION",
            args = args,
            promptBn = promptBn,
            reasonBn = spec?.titleBn ?: toolName,
            caller = caller,
            requiresDoubleConfirm = level == ConfirmationLevel.HUMAN_GATE && isKnownDestructiveTool(toolName)
        )

        val json = JSONObject().apply {
            put("status", "CONFIRMATION_REQUIRED")
            put("executionState", JarvisActionState.WAITING_FOR_ACTION_APPROVAL.name)
            put("success", false)
            put("toolName", toolName)
            put("approvalId", pendingRecord.approvalId)
            put("executionId", pendingRecord.executionId)
            put("targetApp", pendingRecord.targetApp)
            put("recipientName", pendingRecord.recipientName)
            put("requiresConfirmation", true)
            put("confirmationLevel", level.name)
            put("promptBn", promptBn)
            put("summaryBengali", promptBn)
            put("message", "একবারের জন্য আপনার 'হ্যাঁ' সম্মতি দিলে কাজটি সঙ্গে সঙ্গে সম্পন্ন হবে।")
            put("verified", false)
            put("verificationStatus", "PENDING_CONFIRMATION")
            put("verificationDetails", "সম্মতি পেন্ডিং (ID: ${pendingRecord.approvalId})। 'হ্যাঁ' বা 'না' বলুন।")
        }

        return Pair(ConfirmationCheckResult(promptBn, pendingRecord, json), null)
    }

    fun isKnownDestructiveTool(toolName: String): Boolean {
        return when (toolName) {
            "deleteAiTask",
            "deleteMemory",
            "deleteNote",
            "makePhoneCall",
            "sendWhatsAppMessage",
            "controlWhatsApp",
            "startWhatsAppVoiceCall",
            "startWhatsAppVideoCall",
            "sendSmsMessage",
            "sendSms",
            "replyFromNotification",
            "sendNotificationReply" -> true
            else -> false
        }
    }

    fun buildConfirmationPrompt(toolName: String, spec: ToolSpec?, args: JSONObject): String {
        return when (toolName) {
            "deleteAiTask" -> {
                val title = args.optString("title", "টাস্ক")
                "আপনি কি এআই টাস্ক '$title' মুছে ফেলতে চান?"
            }
            "deleteMemory" -> {
                val key = args.optString("keyOrId", args.optString("key", "স্মৃতি"))
                "আপনি কি '$key' এর সংরক্ষিত মেমোরি মুছে ফেলতে চান?"
            }
            "deleteNote" -> {
                "আপনি কি এই নোটটি চিরতরে মুছে ফেলতে চান?"
            }
            "makePhoneCall" -> {
                val target = args.optString("contactName", "").ifBlank { args.optString("phoneNumber", "") }
                "আপনি কি $target-কে সরাসরি ফোন কল করতে চান?"
            }
            "sendWhatsAppMessage", "controlWhatsApp" -> {
                val target = args.optString("contactName", args.optString("contact", args.optString("phoneNumber", "")))
                val msg = args.optString("messageText", args.optString("message", ""))
                "আপনি কি $target-কে এই হোয়াটসঅ্যাপ বার্তা পাঠাতে চান:\n\"$msg\"?"
            }
            "startWhatsAppVoiceCall" -> {
                val target = args.optString("contact", args.optString("phoneNumber", ""))
                "আপনি কি $target-কে হোয়াটসঅ্যাপ ভয়েস কল দিতে চান?"
            }
            "startWhatsAppVideoCall" -> {
                val target = args.optString("contact", args.optString("phoneNumber", ""))
                "আপনি কি $target-কে হোয়াটসঅ্যাপ ভিডিও কল দিতে চান?"
            }
            "sendSmsMessage" -> {
                val phone = args.optString("phoneNumber", args.optString("contactName", ""))
                val msg = args.optString("message", args.optString("text", ""))
                "আপনি কি $phone নম্বরে এই এসএমএসটি পাঠাতে চান:\n\"$msg\"?"
            }
            "replyFromNotification", "sendNotificationReply" -> {
                val msg = args.optString("replyText", args.optString("text", ""))
                "আপনি কি নোটিফিকেশনে এই উত্তর পাঠাতে চান:\n\"$msg\"?"
            }
            else -> {
                val title = spec?.titleBn ?: toolName
                "আপনি কি নিশ্চিত যে '$title' অ্যাকশনটি সম্পন্ন করতে চান?"
            }
        }
    }
}
