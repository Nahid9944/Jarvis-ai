package com.example.permissions

import com.example.automation.HumanGate
import com.example.data.local.ActionApprovalAuditEntity
import com.example.data.local.CompanionDao
import com.example.data.local.TrustedLowRiskRuleEntity
import com.example.tools.UserConfirmationTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single Source of Truth for One-Time Exact Action Approvals, Voice/Text Confirmation Resolution,
 * Scoped Trusted Low-Risk Rules, and Approval Audit Trail.
 */
object ActionApprovalRegistry {

    private val lock = Any()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var companionDao: CompanionDao? = null

    private val _pendingApproval = MutableStateFlow<ActionApprovalRecord?>(null)
    val pendingApproval: StateFlow<ActionApprovalRecord?> = _pendingApproval.asStateFlow()

    private val _lastActionState = MutableStateFlow(JarvisActionState.COMPLETED)
    val lastActionState: StateFlow<JarvisActionState> = _lastActionState.asStateFlow()

    private val _auditTrail = MutableStateFlow<List<PermissionAuditEntry>>(emptyList())
    val auditTrail: StateFlow<List<PermissionAuditEntry>> = _auditTrail.asStateFlow()

    private val _trustedRules = MutableStateFlow<List<TrustedActionRule>>(emptyList())
    val trustedRules: StateFlow<List<TrustedActionRule>> = _trustedRules.asStateFlow()

    // Active approved records keyed by approvalId (one-time-use until consumed or expired)
    private val approvedRecords = ConcurrentHashMap<String, ActionApprovalRecord>()

    // Active approval records currently executing (prevents double dispatch)
    private val inFlightDispatches = ConcurrentHashMap<String, ActionApprovalRecord>()

    private val INTERNAL_META_KEYS = setOf(
        "_confirmed",
        "_human_gate_approved",
        "confirmed",
        "_confirmationToken",
        "_approvalId",
        "_pipelineApprovalId",
        "_executionId",
        "executionId"
    )

    fun attachDao(dao: CompanionDao) {
        companionDao = dao
        ioScope.launch {
            try {
                val persistedRules = dao.getAllTrustedRulesSync().map {
                    TrustedActionRule(
                        ruleId = it.ruleId,
                        toolName = it.toolName,
                        scopeDescriptionBn = it.scopeDescriptionBn,
                        grantedAt = it.grantedAt,
                        expiresAt = it.expiresAt,
                        isEnabled = it.isEnabled
                    )
                }.filter { !ActionSecurityPolicy.isHighRiskNeverTrusted(it.toolName) }
                _trustedRules.value = persistedRules
            } catch (_: Exception) {
            }
        }
    }

    fun updateActionState(state: JarvisActionState) {
        _lastActionState.value = state
    }

    /**
     * Deterministic canonical hash of message content to guarantee modified messages require fresh approval.
     */
    fun computeMessageHash(args: JSONObject): String {
        val rawMsg = listOf(
            args.optString("message", ""),
            args.optString("messageText", ""),
            args.optString("text", ""),
            args.optString("replyText", ""),
            args.optString("postText", ""),
            args.optString("content", "")
        ).firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return sha256(rawMsg)
    }

    /**
     * Deterministic canonical hash of all non-internal parameters.
     */
    fun computeParamsHash(args: JSONObject): String {
        val keys = mutableListOf<String>()
        val iter = args.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k !in INTERNAL_META_KEYS) keys.add(k)
        }
        keys.sort()
        val canonical = keys.joinToString("|") { k ->
            val v = args.opt(k)?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            "$k=$v"
        }
        return sha256(canonical)
    }

    fun extractRecipient(args: JSONObject): Pair<String, String> {
        val phone = args.optString("phoneNumber", args.optString("phone", "")).trim()
        val contact = args.optString("contactName", args.optString("contact", args.optString("recipient", ""))).trim()
        val target = args.optString("target", args.optString("title", args.optString("customerName", ""))).trim()
        val id = (phone.ifBlank { contact }.ifBlank { target }).lowercase(Locale.ROOT)
        val name = contact.ifBlank { phone }.ifBlank { target }.ifBlank { "প্রাপক" }
        return Pair(id, name)
    }

    fun inferTargetApp(toolName: String, args: JSONObject): String {
        return when {
            toolName.contains("WhatsApp", ignoreCase = true) -> "com.whatsapp"
            toolName.contains("Sms", ignoreCase = true) -> "telephony_sms"
            toolName.contains("PhoneCall", ignoreCase = true) -> "telephony_dialer"
            toolName == "runPlaybook" -> args.optString("playbookId", "playbook")
            toolName.contains("Facebook", ignoreCase = true) -> "com.facebook.katana"
            toolName.contains("Notification", ignoreCase = true) -> args.optString("packageFilter", "notifications")
            else -> "jarvis_core"
        }
    }

    /**
     * Registers a pending confirmation in the single approval registry.
     * Avoids overwriting an identical pending record unnecessarily and cleans stale expired items.
     */
    fun registerPendingConfirmation(
        executionId: String = UUID.randomUUID().toString(),
        toolName: String,
        actionType: String,
        args: JSONObject,
        promptBn: String,
        reasonBn: String,
        caller: String,
        requiresDoubleConfirm: Boolean = false
    ): ActionApprovalRecord {
        cleanupExpired()
        val msgHash = computeMessageHash(args)
        val paramsHash = computeParamsHash(args)
        val (recipientId, recipientName) = extractRecipient(args)
        val targetApp = inferTargetApp(toolName, args)

        synchronized(lock) {
            val existing = _pendingApproval.value
            if (existing != null &&
                existing.toolName == toolName &&
                existing.paramsHash == paramsHash &&
                !existing.isExpired() &&
                existing.approvalStatus == ApprovalStatus.PENDING
            ) {
                return existing
            }

            val record = ActionApprovalRecord(
                executionId = executionId,
                actionType = actionType,
                toolName = toolName,
                targetApp = targetApp,
                recipientId = recipientId,
                recipientName = recipientName,
                messageContentHash = msgHash,
                paramsHash = paramsHash,
                promptBn = promptBn,
                reasonBn = reasonBn,
                argsJson = args.toString(),
                caller = caller,
                requiresDoubleConfirm = requiresDoubleConfirm,
                approvalStatus = ApprovalStatus.PENDING,
                dispatchStatus = DispatchStatus.NOT_DISPATCHED
            )
            _pendingApproval.value = record
            _lastActionState.value = JarvisActionState.WAITING_FOR_ACTION_APPROVAL
            recordAudit(
                approvalId = record.approvalId,
                toolName = toolName,
                targetLabel = recipientName,
                state = JarvisActionState.WAITING_FOR_ACTION_APPROVAL,
                approvalStatus = ApprovalStatus.PENDING,
                source = caller,
                detailsBn = promptBn
            )
            return record
        }
    }

    /**
     * Approves the active pending confirmation once and stores it for exact one-time dispatch.
     */
    fun approvePending(source: String, approvalId: String? = null): ActionApprovalRecord? {
        cleanupExpired()
        synchronized(lock) {
            val pending = _pendingApproval.value ?: return null
            if (approvalId != null && approvalId.isNotBlank() && pending.approvalId != approvalId) {
                return null
            }
            if (pending.isExpired()) {
                _pendingApproval.value = null
                recordAudit(
                    approvalId = pending.approvalId,
                    toolName = pending.toolName,
                    targetLabel = pending.recipientName,
                    state = JarvisActionState.CANCELLED,
                    approvalStatus = ApprovalStatus.EXPIRED,
                    source = source,
                    detailsBn = "সম্মতির মেয়াদ শেষ হওয়ায় অনুরোধটি বাতিল হয়েছে।"
                )
                return null
            }

            val approved = pending.copy(
                approvalStatus = ApprovalStatus.APPROVED,
                confirmationTime = System.currentTimeMillis(),
                expiryTime = System.currentTimeMillis() + ActionApprovalRecord.DEFAULT_APPROVAL_TTL_MS,
                approvalSource = source
            )
            _pendingApproval.value = null
            approvedRecords[approved.approvalId] = approved
            _lastActionState.value = JarvisActionState.APPROVED

            if (HumanGate.pendingRequest.value != null) {
                HumanGate.respond(true)
            }

            recordAudit(
                approvalId = approved.approvalId,
                toolName = approved.toolName,
                targetLabel = approved.recipientName,
                state = JarvisActionState.APPROVED,
                approvalStatus = ApprovalStatus.APPROVED,
                source = source,
                detailsBn = "একবারের জন্য সম্মতি গৃহীত হয়েছে ($source)।"
            )
            persistApprovalAudit(approved)
            return approved
        }
    }

    /**
     * Directly creates & approves an ActionApprovalRecord ONLY for trusted UI clicks or explicit test harness.
     */
    fun grantExplicitUiApproval(
        toolName: String,
        args: JSONObject,
        source: String = "ui_button"
    ): ActionApprovalRecord {
        cleanupExpired()
        val msgHash = computeMessageHash(args)
        val paramsHash = computeParamsHash(args)
        val (recipientId, recipientName) = extractRecipient(args)
        val targetApp = inferTargetApp(toolName, args)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val pending = _pendingApproval.value
            if (pending != null && pending.toolName == toolName && pending.paramsHash == paramsHash) {
                _pendingApproval.value = null
            }
            val record = ActionApprovalRecord(
                actionType = toolName,
                toolName = toolName,
                targetApp = targetApp,
                recipientId = recipientId,
                recipientName = recipientName,
                messageContentHash = msgHash,
                paramsHash = paramsHash,
                promptBn = "",
                reasonBn = "Explicit UI Confirmation",
                argsJson = args.toString(),
                caller = source,
                confirmationTime = now,
                expiryTime = now + ActionApprovalRecord.DEFAULT_APPROVAL_TTL_MS,
                approvalStatus = ApprovalStatus.APPROVED,
                dispatchStatus = DispatchStatus.NOT_DISPATCHED,
                approvalSource = source
            )
            approvedRecords[record.approvalId] = record
            _lastActionState.value = JarvisActionState.APPROVED
            recordAudit(
                approvalId = record.approvalId,
                toolName = toolName,
                targetLabel = recipientName,
                state = JarvisActionState.APPROVED,
                approvalStatus = ApprovalStatus.APPROVED,
                source = source,
                detailsBn = "UI বাটন থেকে অনুমোদিত ($source)।"
            )
            persistApprovalAudit(record)
            return record
        }
    }

    /**
     * Verifies and atomically marks DISPATCHED a valid matching one-time approval for (toolName, args).
     * Rejects mismatched recipient, modified message/parameters, expired records, or already dispatched records.
     */
    fun acquireExecutionApproval(
        toolName: String,
        args: JSONObject,
        caller: String,
        executionId: String = ""
    ): ActionApprovalRecord? {
        cleanupExpired()
        val effExecId = executionId.ifBlank {
            args.optString("_executionId", args.optString("executionId", ""))
        }.trim()
        val explicitApprovalId = args.optString("_approvalId", "").ifBlank {
            args.optString("_pipelineApprovalId", "")
        }.trim()
        val msgHash = computeMessageHash(args)
        val paramsHash = computeParamsHash(args)
        val (recipientId, recipientName) = extractRecipient(args)
        val targetApp = inferTargetApp(toolName, args)

        synchronized(lock) {
            // 0. Execution-ID scoped consent (subsequent triggers for an already-approved executionId execute immediately)
            val execConsent = ConsentManager.getExecutionConsent(effExecId, toolName, args)
            if (execConsent != null) {
                approvedRecords.remove(execConsent.approvalId)
                approvedRecords.entries.removeIf { it.value.executionId == effExecId }
                UserConfirmationTokenStore.consumeApproval(toolName, recipientId)
                UserConfirmationTokenStore.consumeApproval(toolName, "")
                val execRecord = ActionApprovalRecord(
                    approvalId = execConsent.approvalId,
                    executionId = effExecId,
                    actionType = toolName,
                    toolName = toolName,
                    targetApp = targetApp,
                    recipientId = recipientId,
                    recipientName = recipientName,
                    messageContentHash = msgHash,
                    paramsHash = paramsHash,
                    promptBn = "",
                    reasonBn = "Execution-ID Scoped Consent ($effExecId)",
                    argsJson = args.toString(),
                    caller = caller,
                    confirmationTime = execConsent.grantedAt,
                    expiryTime = execConsent.expiresAt,
                    approvalStatus = ApprovalStatus.APPROVED,
                    dispatchStatus = DispatchStatus.DISPATCHED,
                    approvalSource = execConsent.source
                )
                inFlightDispatches[execRecord.approvalId] = execRecord
                _lastActionState.value = JarvisActionState.DISPATCHED
                return execRecord
            }

            // 1. Check explicit approvalId first
            if (explicitApprovalId.isNotBlank()) {
                val matched = approvedRecords[explicitApprovalId]
                if (matched != null &&
                    !matched.isExpired() &&
                    matched.toolName == toolName &&
                    matched.targetApp == targetApp &&
                    matched.messageContentHash == msgHash &&
                    matched.paramsHash == paramsHash &&
                    matched.approvalStatus == ApprovalStatus.APPROVED &&
                    matched.dispatchStatus == DispatchStatus.NOT_DISPATCHED
                ) {
                    val dispatched = matched.copy(dispatchStatus = DispatchStatus.DISPATCHED)
                    approvedRecords.remove(explicitApprovalId)
                    inFlightDispatches[explicitApprovalId] = dispatched
                    UserConfirmationTokenStore.consumeApproval(toolName, recipientId)
                    UserConfirmationTokenStore.consumeApproval(toolName, "")
                    _lastActionState.value = JarvisActionState.DISPATCHED
                    return dispatched
                }
            }

            // 2. Match any active approved record for exact same tool, targetApp, recipient & paramsHash
            val candidate = approvedRecords.values.firstOrNull { rec ->
                !rec.isExpired() &&
                    rec.toolName == toolName &&
                    rec.targetApp == targetApp &&
                    rec.messageContentHash == msgHash &&
                    rec.paramsHash == paramsHash &&
                    (rec.recipientId.isBlank() || recipientId.isBlank() || rec.recipientId == recipientId) &&
                    rec.approvalStatus == ApprovalStatus.APPROVED &&
                    rec.dispatchStatus == DispatchStatus.NOT_DISPATCHED
            }
            if (candidate != null) {
                val dispatched = candidate.copy(dispatchStatus = DispatchStatus.DISPATCHED)
                approvedRecords.remove(candidate.approvalId)
                inFlightDispatches[candidate.approvalId] = dispatched
                UserConfirmationTokenStore.consumeApproval(toolName, recipientId)
                UserConfirmationTokenStore.consumeApproval(toolName, "")
                _lastActionState.value = JarvisActionState.DISPATCHED
                return dispatched
            }

            // 3. Explicit Trusted Low-Risk Rule (strictly disallowed for sensitive actions)
            if (!ActionSecurityPolicy.isHighRiskNeverTrusted(toolName)) {
                val trusted = _trustedRules.value.firstOrNull {
                    it.toolName == toolName && it.isValid()
                }
                if (trusted != null) {
                    val autoRecord = ActionApprovalRecord(
                        actionType = toolName,
                        toolName = toolName,
                        targetApp = targetApp,
                        recipientId = recipientId,
                        recipientName = recipientName,
                        messageContentHash = msgHash,
                        paramsHash = paramsHash,
                        promptBn = "",
                        reasonBn = "Trusted Low-Risk Rule: ${trusted.scopeDescriptionBn}",
                        argsJson = args.toString(),
                        caller = caller,
                        confirmationTime = System.currentTimeMillis(),
                        approvalStatus = ApprovalStatus.APPROVED,
                        dispatchStatus = DispatchStatus.DISPATCHED,
                        approvalSource = "trusted_low_risk_rule"
                    )
                    inFlightDispatches[autoRecord.approvalId] = autoRecord
                    return autoRecord
                }
            }

            // 4. Fallback compatibility for legacy UserConfirmationTokenStore / user_ui explicit flags
            val legacyApproved = ((caller == "user_ui" || caller == "test") &&
                (args.optBoolean("_confirmed", false) ||
                    args.optBoolean("_human_gate_approved", false) ||
                    args.optBoolean("confirmed", false))) ||
                UserConfirmationTokenStore.consumeApproval(toolName, recipientId) ||
                UserConfirmationTokenStore.consumeApproval(toolName, "")

            if (legacyApproved) {
                val compatRecord = ActionApprovalRecord(
                    actionType = toolName,
                    toolName = toolName,
                    targetApp = targetApp,
                    recipientId = recipientId,
                    recipientName = recipientName,
                    messageContentHash = msgHash,
                    paramsHash = paramsHash,
                    promptBn = "",
                    reasonBn = "Legacy UI/Token Confirmation",
                    argsJson = args.toString(),
                    caller = caller,
                    confirmationTime = System.currentTimeMillis(),
                    approvalStatus = ApprovalStatus.APPROVED,
                    dispatchStatus = DispatchStatus.DISPATCHED,
                    approvalSource = caller
                )
                inFlightDispatches[compatRecord.approvalId] = compatRecord
                _lastActionState.value = JarvisActionState.DISPATCHED
                return compatRecord
            }

            return null
        }
    }

    /**
     * Checks if an action was already approved/dispatched by the central pipeline
     * so inner components like PlaybookRunner do NOT prompt a second time.
     */
    fun isApprovalInFlight(approvalId: String?): Boolean {
        if (approvalId.isNullOrBlank()) return false
        return inFlightDispatches.containsKey(approvalId) || approvedRecords.containsKey(approvalId)
    }

    /**
     * Verifies whether an active approved or in-flight dispatch record currently authorizes
     * a sensitive action (e.g., WhatsApp send) for the matching tool, recipient, and message.
     */
    fun hasActiveApprovalFor(
        toolNames: Set<String>,
        recipientQuery: String = "",
        messageText: String = ""
    ): Boolean {
        cleanupExpired()
        val normRecipient = recipientQuery.trim().lowercase(Locale.ROOT)
        val normPhone = recipientQuery.replace("[^0-9+]".toRegex(), "")
        val msgHash = if (messageText.isNotBlank()) sha256(messageText.trim()) else null
        synchronized(lock) {
            val allActive = inFlightDispatches.values + approvedRecords.values
            return allActive.any { rec ->
                if (rec.isExpired() || rec.toolName !in toolNames) return@any false
                if (rec.approvalStatus != ApprovalStatus.APPROVED && rec.dispatchStatus != DispatchStatus.DISPATCHED) {
                    return@any false
                }
                val messageMatches = msgHash == null ||
                    rec.messageContentHash == msgHash ||
                    rec.messageContentHash == sha256("")
                val recipientMatches = normRecipient.isBlank() ||
                    rec.recipientId.isBlank() ||
                    rec.recipientId == normRecipient ||
                    normRecipient.contains(rec.recipientId) ||
                    rec.recipientId.contains(normRecipient) ||
                    (normPhone.length >= 6 && rec.recipientId.replace("[^0-9+]".toRegex(), "").endsWith(normPhone.takeLast(6)))
                messageMatches && recipientMatches
            }
        }
    }

    /**
     * Marks an in-flight approval as CONSUMED upon execution completion so it can never be replayed.
     */
    fun markExecutionFinished(approvalId: String?, success: Boolean, verified: Boolean) {
        if (approvalId.isNullOrBlank()) return
        synchronized(lock) {
            val rec = inFlightDispatches.remove(approvalId) ?: approvedRecords.remove(approvalId) ?: return
            val consumed = rec.copy(
                approvalStatus = ApprovalStatus.CONSUMED,
                dispatchStatus = if (success) DispatchStatus.COMPLETED else DispatchStatus.FAILED
            )
            _lastActionState.value = when {
                success && verified -> JarvisActionState.COMPLETED
                success && !verified -> JarvisActionState.UNVERIFIED
                else -> JarvisActionState.FAILED
            }
            recordAudit(
                approvalId = consumed.approvalId,
                toolName = consumed.toolName,
                targetLabel = consumed.recipientName,
                state = _lastActionState.value,
                approvalStatus = ApprovalStatus.CONSUMED,
                source = consumed.approvalSource.ifBlank { consumed.caller },
                detailsBn = if (success) "অনুমোদিত কাজটি সম্পন্ন হয়ে টোকেন CONSUMED করা হয়েছে।" else "কাজটি ব্যর্থ হওয়ায় টোকেন নিষ্ক্রিয় করা হয়েছে।"
            )
            persistApprovalAudit(consumed, completedAt = System.currentTimeMillis())
        }
    }

    fun rejectPending(source: String, reason: String = "ব্যবহারকারী বাতিল করেছেন"): ActionApprovalRecord? {
        synchronized(lock) {
            val pending = _pendingApproval.value ?: return null
            _pendingApproval.value = null
            if (HumanGate.pendingRequest.value != null) {
                HumanGate.respond(false)
            }
            val rejected = pending.copy(
                approvalStatus = ApprovalStatus.REJECTED,
                dispatchStatus = DispatchStatus.CANCELLED
            )
            _lastActionState.value = JarvisActionState.CANCELLED
            recordAudit(
                approvalId = rejected.approvalId,
                toolName = rejected.toolName,
                targetLabel = rejected.recipientName,
                state = JarvisActionState.CANCELLED,
                approvalStatus = ApprovalStatus.REJECTED,
                source = source,
                detailsBn = reason
            )
            persistApprovalAudit(rejected, completedAt = System.currentTimeMillis())
            return rejected
        }
    }

    fun invalidateExecutionApprovals(executionId: String?) {
        if (executionId.isNullOrBlank()) return
        synchronized(lock) {
            if (_pendingApproval.value?.executionId == executionId) {
                _pendingApproval.value = null
            }
            approvedRecords.entries.removeIf { it.value.executionId == executionId }
            inFlightDispatches.entries.removeIf { it.value.executionId == executionId }
        }
    }

    fun cancelAll(reason: String = "সিস্টেম বাতিল / জরুরি বিরতি") {
        synchronized(lock) {
            _pendingApproval.value?.let { pending ->
                recordAudit(
                    approvalId = pending.approvalId,
                    toolName = pending.toolName,
                    targetLabel = pending.recipientName,
                    state = JarvisActionState.CANCELLED,
                    approvalStatus = ApprovalStatus.CANCELLED,
                    source = "system_cancel",
                    detailsBn = reason
                )
            }
            _pendingApproval.value = null
            approvedRecords.clear()
            inFlightDispatches.clear()
            UserConfirmationTokenStore.revokeAll()
            ConsentManager.clearAll()
            if (HumanGate.pendingRequest.value != null) {
                HumanGate.respond(false)
            }
            _lastActionState.value = JarvisActionState.CANCELLED
        }
    }

    /**
     * Multilingual Bengali, Banglish, and English confirmation/rejection classifier.
     */
    fun classifyApprovalReply(utterance: String): ApprovalReplyDecision {
        val raw = utterance.trim()
        if (raw.isBlank()) return ApprovalReplyDecision.UNRELATED
        val lower = raw.lowercase(Locale.ROOT)
            .replace("।", " ")
            .replace(",", " ")
            .replace("!", " ")
            .replace(".", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        // Explicit parameter/correction detection ("না, মেসেজটা বদলাও", "change to...", "অন্য নম্বরে...")
        val correctionMarkers = listOf(
            "বদলাও", "পাল্টাও", "পরিবর্তন", "চেঞ্জ", "অন্য", "নতুন করে",
            "change", "edit", "instead", "different", "modify"
        )
        if (correctionMarkers.any { lower.contains(it) }) {
            return ApprovalReplyDecision.CORRECTION
        }

        val rejectPhrases = setOf(
            "না", "না থাক", "থাক", "বাতিল", "বাতিল করো", "ক্যানসেল", "পাঠাবে না", "পাঠিয়ো না", "পাঠিয়ো না",
            "কল দিও না", "কল দেবে না", "বন্ধ করো", "বাদ দাও", "দরকার নেই", "করো না", "লাগবে না",
            "no", "nope", "nah", "cancel", "cancel it", "cancel this", "stop", "stop it", "reject", "reject it",
            "deny", "don't", "do not", "nevermind", "never mind", "skip", "skip it",
            "na", "na thak", "batil", "batil koro", "bondho koro"
        )
        if (lower in rejectPhrases ||
            lower.startsWith("না ") ||
            lower.startsWith("no ") ||
            (lower.split(" ").size <= 5 && (
                lower.startsWith("cancel ") ||
                    lower.startsWith("stop ") ||
                    lower.startsWith("reject ") ||
                    lower.startsWith("don't ") ||
                    lower.startsWith("do not ")
                )) ||
            lower.contains("বাতিল করো") ||
            lower.contains("পাঠাবে না") ||
            lower.contains("পাঠিয়ো না") ||
            lower.contains("পাঠিয়ো না") ||
            lower.contains("করবে না")
        ) {
            return ApprovalReplyDecision.REJECT
        }

        val approveExact = setOf(
            "হ্যাঁ", "হ্যা", "হাঁ", "হু", "হুম", "জি", "জ্বি", "অবশ্যই",
            "হ্যাঁ করো", "হ্যাঁ পাঠাও", "হ্যাঁ পাঠিয়ে দাও", "হ্যাঁ পাঠিয়ে দাও", "হ্যাঁ কল দাও",
            "অনুমতি দিলাম", "সম্মতি দিলাম", "ঠিক আছে", "করো", "করুন", "পাঠাও", "পাঠিয়ে দাও", "পাঠিয়ে দাও",
            "চালিয়ে যাও", "চালিয়ে যাও", "এগিয়ে যাও", "এগিয়ে যাও", "কল দাও", "দিয়ে দাও", "দিয়ে দাও",
            "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "confirm", "confirmed",
            "approve", "approved", "proceed", "go ahead", "do it", "send it", "call now",
            "hae", "ha", "han", "ji", "hmm", "thik ache", "koro", "pathao", "pathiye dao",
            "thik ache koro", "thik ache pathao", "hae koro", "ha koro", "onumoti dilam"
        )
        if (lower in approveExact) {
            return ApprovalReplyDecision.APPROVE
        }

        val approvePrefixes = listOf(
            "হ্যাঁ ", "হ্যা ", "ঠিক আছে ", "অনুমতি দিলাম", "পাঠিয়ে দাও", "পাঠিয়ে দাও",
            "yes ", "ok ", "okay ", "sure ", "proceed", "confirm",
            "thik ache ", "hae ", "ha ", "han "
        )
        if (lower.split(" ").size <= 6 && approvePrefixes.any { lower.startsWith(it) }) {
            return ApprovalReplyDecision.APPROVE
        }

        return ApprovalReplyDecision.UNRELATED
    }

    // -------------------------------------------------------------
    // SCOPED TRUSTED LOW-RISK RULES (SAFE TOOLS ONLY)
    // -------------------------------------------------------------
    fun setTrustedLowRiskRule(
        toolName: String,
        scopeDescriptionBn: String,
        enabled: Boolean,
        durationHours: Long = 24L
    ): Pair<Boolean, String> {
        if (ActionSecurityPolicy.isHighRiskNeverTrusted(toolName)) {
            return Pair(
                false,
                "নিরাপত্তা নীতি: '$toolName' একটি সংবেদনশীল অ্যাকশন। কল, এসএমএস, হোয়াটসঅ্যাপ মেসেজ বা ডিলিট কাজে অটো-অনুমোদন নিষিদ্ধ।"
            )
        }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val updated = _trustedRules.value.filterNot { it.toolName == toolName }.toMutableList()
            val rule = TrustedActionRule(
                toolName = toolName,
                scopeDescriptionBn = scopeDescriptionBn,
                grantedAt = now,
                expiresAt = now + durationHours * 3_600_000L,
                isEnabled = enabled
            )
            if (enabled) updated.add(rule)
            _trustedRules.value = updated
            ioScope.launch {
                try {
                    if (enabled) {
                        companionDao?.upsertTrustedRule(
                            TrustedLowRiskRuleEntity(
                                ruleId = rule.ruleId,
                                toolName = rule.toolName,
                                scopeDescriptionBn = rule.scopeDescriptionBn,
                                grantedAt = rule.grantedAt,
                                expiresAt = rule.expiresAt,
                                isEnabled = true
                            )
                        )
                    } else {
                        companionDao?.deleteTrustedRulesForTool(toolName)
                    }
                } catch (_: Exception) {
                }
            }
            return Pair(true, if (enabled) "'$toolName' লো-রিস্ক রুল সক্রিয় করা হয়েছে।" else "'$toolName' লো-রিস্ক রুল বাতিল করা হয়েছে।")
        }
    }

    fun clearAllTrustedRules() {
        synchronized(lock) {
            _trustedRules.value = emptyList()
            ioScope.launch {
                try {
                    companionDao?.clearAllTrustedRules()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun recordAudit(
        approvalId: String?,
        toolName: String,
        targetLabel: String,
        state: JarvisActionState,
        approvalStatus: ApprovalStatus?,
        source: String,
        detailsBn: String
    ) {
        val entry = PermissionAuditEntry(
            approvalId = approvalId,
            toolName = toolName,
            targetLabel = targetLabel,
            state = state,
            approvalStatus = approvalStatus,
            source = source,
            detailsBn = detailsBn
        )
        _auditTrail.value = (listOf(entry) + _auditTrail.value).take(50)
    }

    private fun persistApprovalAudit(record: ActionApprovalRecord, completedAt: Long = 0L) {
        ioScope.launch {
            try {
                companionDao?.upsertApprovalAudit(
                    ActionApprovalAuditEntity(
                        approvalId = record.approvalId,
                        executionId = record.executionId,
                        actionType = record.actionType,
                        toolName = record.toolName,
                        targetApp = record.targetApp,
                        recipientName = record.recipientName,
                        paramsHash = record.paramsHash,
                        messageContentHash = record.messageContentHash,
                        approvalStatus = record.approvalStatus.name,
                        dispatchStatus = record.dispatchStatus.name,
                        source = record.approvalSource.ifBlank { record.caller },
                        createdAt = record.createdAt,
                        confirmationTime = record.confirmationTime ?: 0L,
                        expiryTime = record.expiryTime,
                        completedAt = completedAt
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        approvedRecords.entries.removeIf { it.value.isExpired(now) }
        val pending = _pendingApproval.value
        if (pending != null && pending.isExpired(now)) {
            _pendingApproval.value = null
            recordAudit(
                approvalId = pending.approvalId,
                toolName = pending.toolName,
                targetLabel = pending.recipientName,
                state = JarvisActionState.CANCELLED,
                approvalStatus = ApprovalStatus.EXPIRED,
                source = "ttl_expiry",
                detailsBn = "সম্মতির মেয়াদ উত্তীর্ণ হয়েছে।"
            )
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun resetForTest() {
        synchronized(lock) {
            _pendingApproval.value = null
            approvedRecords.clear()
            inFlightDispatches.clear()
            _trustedRules.value = emptyList()
            _auditTrail.value = emptyList()
            _lastActionState.value = JarvisActionState.COMPLETED
            UserConfirmationTokenStore.revokeAll()
            ConsentManager.clearAll()
        }
    }
}
