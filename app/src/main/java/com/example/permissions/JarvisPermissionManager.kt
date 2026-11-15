package com.example.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import com.example.service.QueuedAnnouncement
import com.example.tools.ToolExecutionPipeline
import org.json.JSONArray
import com.example.tools.ToolRegistry
import com.example.tools.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Centralized Single Source of Truth for BOTH:
 * 1) Android OS System Permissions (Runtime, Special Access, Role, Per-Session)
 * 2) User Action Approvals & Execution-ID Consents (via ActionApprovalRegistry & ConsentManager)
 *
 * Strictly enforces:
 * - Never treats voice "YES" as Android OS permission.
 * - Never re-prompts already GRANTED Android permissions or duplicates active dialogs.
 * - Never checks SEND_SMS for WhatsApp actions.
 * - Once an action is approved for a specific executionId, subsequent triggers execute immediately.
 * - Auto-resumes held actions through ToolExecutionPipeline on Activity onResume().
 */
object JarvisPermissionManager {

    private const val TAG = "JarvisPermissionMgr"
    private const val PREFS_PENDING_OS = "jarvis_pending_os_action_prefs"
    private const val KEY_PENDING_JSON = "pending_os_action_json"
    private const val PENDING_TTL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val resumeMutex = Mutex()

    private val _pendingOsAction = MutableStateFlow<PendingOsPermissionAction?>(null)
    val pendingOsAction: StateFlow<PendingOsPermissionAction?> = _pendingOsAction.asStateFlow()

    val pendingUserApproval: StateFlow<ActionApprovalRecord?>
        get() = ActionApprovalRegistry.pendingApproval

    private val _revokedNotices = MutableStateFlow<List<String>>(emptyList())
    val revokedNotices: StateFlow<List<String>> = _revokedNotices.asStateFlow()

    private val _lastAutoResumeResult = MutableStateFlow<Pair<String, String>?>(null)
    val lastAutoResumeResult: StateFlow<Pair<String, String>?> = _lastAutoResumeResult.asStateFlow()

    private var previousGrantedSnapshot: Map<String, Boolean> = emptyMap()

    @Volatile
    private var pendingExecutor: (suspend (String, JSONObject) -> JSONObject)? = null

    private fun savePendingOsToPrefs(context: Context, pending: PendingOsPermissionAction?) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_OS, Context.MODE_PRIVATE)
            if (pending == null) {
                prefs.edit().remove(KEY_PENDING_JSON).apply()
                return
            }
            val obj = JSONObject().apply {
                put("pendingId", pending.pendingId)
                put("executionId", pending.executionId)
                put("toolName", pending.toolName)
                put("descriptionBn", pending.descriptionBn)
                put("argsJson", pending.argsJson)
                put("caller", pending.caller)
                put("requiredPermissionIds", JSONArray(pending.requiredPermissionIds))
                put("missingPermissionIds", JSONArray(pending.missingPermissionIds))
                put("approvedIdIfAny", pending.approvedIdIfAny ?: "")
                put("createdAt", pending.createdAt)
            }
            prefs.edit().putString(KEY_PENDING_JSON, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun restorePendingOsFromPrefsIfNeeded(context: Context, now: Long = System.currentTimeMillis()): PendingOsPermissionAction? {
        _pendingOsAction.value?.let { inMem ->
            if (now - inMem.createdAt <= PENDING_TTL_MS) return inMem
            _pendingOsAction.value = null
            savePendingOsToPrefs(context, null)
            return null
        }
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_OS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING_JSON, null) ?: return null
            val obj = JSONObject(raw)
            val createdAt = obj.optLong("createdAt", 0L)
            if (createdAt <= 0L || (now - createdAt) > PENDING_TTL_MS) {
                prefs.edit().remove(KEY_PENDING_JSON).apply()
                return null
            }
            val reqArr = obj.optJSONArray("requiredPermissionIds") ?: JSONArray()
            val missArr = obj.optJSONArray("missingPermissionIds") ?: JSONArray()
            val restored = PendingOsPermissionAction(
                pendingId = obj.optString("pendingId"),
                executionId = obj.optString("executionId"),
                toolName = obj.optString("toolName"),
                descriptionBn = obj.optString("descriptionBn"),
                argsJson = obj.optString("argsJson", "{}"),
                caller = obj.optString("caller", "resume"),
                requiredPermissionIds = List(reqArr.length()) { reqArr.optString(it) }.filter { it.isNotBlank() },
                missingPermissionIds = List(missArr.length()) { missArr.optString(it) }.filter { it.isNotBlank() },
                approvedIdIfAny = obj.optString("approvedIdIfAny").ifBlank { null },
                state = JarvisActionState.WAITING_FOR_OS_PERMISSION,
                createdAt = createdAt
            )
            _pendingOsAction.value = restored
            restored
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 1. UNIFIED ANDROID SYSTEM PERMISSION CHECKS (SINGLE SOURCE OF TRUTH)
    // =========================================================================

    fun isSystemPermissionGranted(context: Context, permissionId: String): Boolean {
        val spec = PermissionRegistry.get(permissionId)
        if (spec != null) {
            return try {
                spec.isGranted(context)
            } catch (_: Exception) {
                false
            }
        }
        return try {
            ContextCompat.checkSelfPermission(context, permissionId) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasSendSmsPermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasCallPhonePermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasContactsPermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasAudioPermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasCameraPermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasLocationPermission(context: Context): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasAccessibilityPermission(context: Context): Boolean =
        try {
            JarvisAccessibilityService.isAccessibilityEnabled(context)
        } catch (_: Exception) {
            false
        }

    fun hasNotificationListenerPermission(): Boolean =
        try {
            JarvisNotificationListenerService.isConnected()
        } catch (_: Exception) {
            false
        }

    /**
     * Resolves required Android OS permission specs for a tool.
     * Explicitly enforces WhatsApp != SEND_SMS separation.
     */
    fun getRequiredSpecs(toolName: String, spec: ToolSpec? = ToolRegistry.get(toolName)): List<PermissionSpec> {
        val raw = PermissionRegistry.getResolvedForTool(toolName, spec?.requiredPermissionId)
        val isWhatsAppTool = toolName.contains("WhatsApp", ignoreCase = true)
        return if (isWhatsAppTool) {
            raw.filterNot { it.id == "sms_send" || it.id == "send_sms" || it.id == "sms_read" || it.id == "sms_receive" }
        } else {
            raw
        }
    }

    /**
     * Checks real Android OS permission status without trusting any voice/prose claim.
     * Returns only currently MISSING PermissionSpecs.
     */
    fun getMissingPermissions(
        context: Context,
        toolName: String,
        spec: ToolSpec? = ToolRegistry.get(toolName)
    ): List<PermissionSpec> {
        val required = getRequiredSpecs(toolName, spec)
        return required.filter { permSpec ->
            val granted = try {
                permSpec.isGranted(context)
            } catch (_: Exception) {
                false
            }
            !granted
        }
    }

    fun isToolOsPermissionGranted(context: Context, toolName: String): Boolean {
        return getMissingPermissions(context, toolName).isEmpty()
    }

    /**
     * Refreshes real OS permission snapshot and records any newly revoked permissions honestly.
     */
    fun refreshSnapshotAndDetectRevocations(context: Context): Map<String, Boolean> {
        val current = PermissionOrchestrator.recheckAll(context)
        if (previousGrantedSnapshot.isNotEmpty()) {
            val newlyRevoked = mutableListOf<String>()
            for ((id, wasGranted) in previousGrantedSnapshot) {
                if (wasGranted && current[id] == false) {
                    val title = PermissionRegistry.get(id)?.titleBn ?: id
                    newlyRevoked.add("'$title' অনুমতিটি সিস্টেম থেকে বন্ধ বা প্রত্যাহার করা হয়েছে।")
                }
            }
            if (newlyRevoked.isNotEmpty()) {
                _revokedNotices.value = (newlyRevoked + _revokedNotices.value).distinct().take(10)
            }
        }
        previousGrantedSnapshot = current
        return current
    }

    // =========================================================================
    // 2. UNIFIED ACTION APPROVAL & EXECUTION-ID CONSENT COORDINATION
    // =========================================================================

    fun acquireActionApproval(
        executionId: String,
        toolName: String,
        args: JSONObject,
        caller: String
    ): ActionApprovalRecord? {
        return ActionApprovalRegistry.acquireExecutionApproval(
            toolName = toolName,
            args = args,
            caller = caller,
            executionId = executionId
        )
    }

    fun registerPendingUserApproval(
        executionId: String,
        toolName: String,
        actionType: String,
        args: JSONObject,
        promptBn: String,
        reasonBn: String,
        caller: String,
        requiresDoubleConfirm: Boolean = false
    ): ActionApprovalRecord {
        return ActionApprovalRegistry.registerPendingConfirmation(
            executionId = executionId,
            toolName = toolName,
            actionType = actionType,
            args = args,
            promptBn = promptBn,
            reasonBn = reasonBn,
            caller = caller,
            requiresDoubleConfirm = requiresDoubleConfirm
        )
    }

    fun approveUserAction(source: String, approvalId: String? = null): ActionApprovalRecord? {
        return ConsentManager.approvePendingAndExecution(source = source, approvalId = approvalId)
    }

    fun approveForExecutionId(
        executionId: String,
        toolName: String,
        args: JSONObject = JSONObject(),
        source: String = "user"
    ): ExecutionConsentGrant {
        return ConsentManager.approveForExecution(
            executionId = executionId,
            toolName = toolName,
            args = args,
            source = source
        )
    }

    fun hasExecutionConsent(
        executionId: String?,
        toolName: String,
        args: JSONObject = JSONObject()
    ): Boolean = ConsentManager.hasExecutionConsent(executionId, toolName, args)

    fun completeExecutionConsent(executionId: String?) {
        ConsentManager.completeExecution(executionId)
    }

    fun rejectUserAction(source: String, reason: String = "ব্যবহারকারী বাতিল করেছেন"): ActionApprovalRecord? {
        return ActionApprovalRegistry.rejectPending(source = source, reason = reason)
    }

    // =========================================================================
    // 3. HOLD & ON-RESUME AUTO-EXECUTION FOR MISSING OS PERMISSIONS
    // =========================================================================

    /**
     * Holds a pending command while waiting for Android OS Runtime or Special Access permission.
     * Re-uses any already granted approvalId and prevents duplicate prompts if the same dialog is already visible.
     */
    fun holdForMissingPermission(
        context: Context,
        executionId: String,
        toolName: String,
        descriptionBn: String,
        args: JSONObject,
        caller: String,
        missingSpecs: List<PermissionSpec>,
        approvedIdIfAny: String? = null,
        executor: suspend (String, JSONObject) -> JSONObject
    ): PendingOsPermissionAction {
        val requiredIds = getRequiredSpecs(toolName).map { it.id }
        val pending = PendingOsPermissionAction(
            executionId = executionId,
            toolName = toolName,
            descriptionBn = descriptionBn,
            argsJson = args.toString(),
            caller = caller,
            requiredPermissionIds = requiredIds,
            missingPermissionIds = missingSpecs.map { it.id },
            approvedIdIfAny = approvedIdIfAny,
            state = JarvisActionState.WAITING_FOR_OS_PERMISSION
        )
        pendingExecutor = executor
        _pendingOsAction.value = pending
        savePendingOsToPrefs(context, pending)
        ActionApprovalRegistry.updateActionState(JarvisActionState.WAITING_FOR_OS_PERMISSION)

        val firstMissing = missingSpecs.first()
        val alreadyPromptingSame = PermissionOrchestrator.currentRequest.value?.spec?.id == firstMissing.id
        if (!alreadyPromptingSame) {
            PermissionOrchestrator.clearPostponed(firstMissing.id)
            PermissionOrchestrator.request(
                spec = firstMissing,
                reason = "'$descriptionBn' সম্পন্ন করতে '${firstMissing.titleBn}' অনুমতি প্রয়োজন।"
            ) { granted ->
                if (granted) {
                    onResumeRecheckAndExecute(context)
                }
            }
        }
        Log.d(TAG, "Held '$toolName' waiting for OS permissions: ${pending.missingPermissionIds}")
        return pending
    }

    /**
     * Called on Activity.onResume() and immediately after runtime permission dialog completes.
     * Re-checks OS permissions; if all are now GRANTED, atomically pops and re-executes via ToolExecutionPipeline.
     */
    fun onResumeRecheckAndExecute(
        context: Context,
        fallbackExecutor: (suspend (String, JSONObject) -> JSONObject)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        scope.launch {
            refreshSnapshotAndDetectRevocations(context)

            val nextMissingHolder = arrayOfNulls<PermissionSpec>(1)
            val toRun: Pair<PendingOsPermissionAction, (suspend (String, JSONObject) -> JSONObject)>? = resumeMutex.withLock {
                val pending = restorePendingOsFromPrefsIfNeeded(context) ?: return@withLock null
                val stillMissing = pending.requiredPermissionIds.mapNotNull { PermissionRegistry.get(it) }
                    .filter { spec ->
                        try {
                            !spec.isGranted(context)
                        } catch (_: Exception) {
                            true
                        }
                    }

                if (stillMissing.isEmpty()) {
                    _pendingOsAction.value = null
                    savePendingOsToPrefs(context, null)
                    val exec = pendingExecutor ?: fallbackExecutor ?: { tool, argsObj ->
                        val dao = com.example.data.local.AssistantDatabase.getDatabase(context).assistantDao()
                        com.example.ai.AssistantToolExecutor(
                            dao = dao,
                            onNavigate = {},
                            context = context
                        ).executeToolWithCaller(
                            name = tool,
                            args = argsObj,
                            caller = pending.caller
                        )
                    }
                    pendingExecutor = null
                    ActionApprovalRegistry.updateActionState(JarvisActionState.OS_PERMISSION_GRANTED)
                    Pair(pending, exec)
                } else {
                    val updated = pending.copy(missingPermissionIds = stillMissing.map { it.id })
                    _pendingOsAction.value = updated
                    savePendingOsToPrefs(context, updated)
                    nextMissingHolder[0] = stillMissing.first()
                    null
                }
            }

            if (toRun == null) {
                val nextSpec = nextMissingHolder[0]
                if (nextSpec != null && PermissionOrchestrator.currentRequest.value == null) {
                    val desc = _pendingOsAction.value?.descriptionBn ?: nextSpec.titleBn
                    PermissionOrchestrator.clearPostponed(nextSpec.id)
                    PermissionOrchestrator.request(
                        spec = nextSpec,
                        reason = "'$desc' সম্পন্ন করতে '${nextSpec.titleBn}' অনুমতি প্রয়োজন।"
                    )
                }
            } else {
                val (pending, exec) = toRun
                val args = try {
                    JSONObject(pending.argsJson)
                } catch (_: Exception) {
                    JSONObject()
                }
                if (!pending.approvedIdIfAny.isNullOrBlank()) {
                    args.put("_approvalId", pending.approvedIdIfAny)
                }
                args.put("_executionId", pending.executionId)
                Log.i(TAG, "OS permission granted! Resuming '${pending.toolName}' via ToolExecutionPipeline")
                val res = ToolExecutionPipeline.execute(
                    toolName = pending.toolName,
                    args = args,
                    context = context,
                    caller = pending.caller,
                    executionId = pending.executionId,
                    executor = exec
                )
                val ok = res.optBoolean("success", false)
                val msg = res.optString("summaryBengali", res.optString("message", "পেন্ডিং কাজ সম্পন্ন হয়েছে।"))
                _lastAutoResumeResult.value = Pair(pending.toolName, msg)
                GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
                    eventKey = "auto_resume_${pending.executionId}",
                    rawText = msg,
                    category = QueuedAnnouncement.CATEGORY_SYSTEM_ALERT,
                    priority = QueuedAnnouncement.PRIORITY_NORMAL
                )
                onResult?.invoke(ok, msg)
            }
        }
    }

    fun cancelPendingOsAction(context: Context? = null) {
        _pendingOsAction.value = null
        pendingExecutor = null
        if (context != null) savePendingOsToPrefs(context, null)
    }

    fun clearRevokedNotices() {
        _revokedNotices.value = emptyList()
    }

    fun clearLastAutoResumeResult() {
        _lastAutoResumeResult.value = null
    }

    fun resetForTest() {
        _pendingOsAction.value = null
        pendingExecutor = null
        _revokedNotices.value = emptyList()
        _lastAutoResumeResult.value = null
        previousGrantedSnapshot = emptyMap()
        ConsentManager.clearAll()
    }
}
