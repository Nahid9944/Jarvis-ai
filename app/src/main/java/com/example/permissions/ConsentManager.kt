package com.example.permissions

import com.example.automation.HumanGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Execution-scoped consent grant:
 * Once an action is approved for a specific executionId, subsequent triggers for that
 * same task/executionId proceed immediately without user re-intervention until
 * completed, revoked, cancelled, or mutated.
 */
data class ExecutionConsentGrant(
    val executionId: String,
    val approvalId: String,
    val toolName: String,
    val recipientId: String,
    val recipientKeys: Set<String> = if (recipientId.isNotBlank()) setOf(recipientId) else emptySet(),
    val messageContentHash: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + ActionApprovalRecord.DEFAULT_APPROVAL_TTL_MS,
    val source: String = "user",
    val isActive: Boolean = true
) {
    fun isValidFor(
        tool: String,
        candidateRecipients: Set<String>,
        msgHash: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isActive || now > expiresAt) return false
        val sameToolOrPlaybook = toolName == tool ||
            (tool == "runPlaybook" && toolName in setOf("sendWhatsAppMessage", "controlWhatsApp", "sendSmsMessage", "startWhatsAppVoiceCall", "startWhatsAppVideoCall")) ||
            (toolName == "runPlaybook" && tool in setOf("sendWhatsAppMessage", "controlWhatsApp", "sendSmsMessage", "startWhatsAppVoiceCall", "startWhatsAppVideoCall"))
        if (!sameToolOrPlaybook) return false
        val knownKeys = if (recipientKeys.isNotEmpty()) recipientKeys else setOfNotNull(recipientId.takeIf { it.isNotBlank() })
        if (knownKeys.isNotEmpty() && candidateRecipients.isNotEmpty() && knownKeys.none { it in candidateRecipients }) {
            return false
        }
        if (messageContentHash.isNotBlank() && msgHash.isNotBlank() && messageContentHash != msgHash) return false
        return true
    }
}

/**
 * Centralized ConsentManager paired with ActionApprovalRegistry.
 * Tracks one-time and execution-ID-scoped user approvals for sensitive actions
 * (WhatsApp messages, phone calls, SMS dispatch, deletions, playbooks).
 */
object ConsentManager {

    private val executionConsents = ConcurrentHashMap<String, ExecutionConsentGrant>()

    private val _activeExecutionConsents = MutableStateFlow<Map<String, ExecutionConsentGrant>>(emptyMap())
    val activeExecutionConsents: StateFlow<Map<String, ExecutionConsentGrant>> = _activeExecutionConsents.asStateFlow()

    /**
     * Grants consent bound to a specific executionId so subsequent triggers or playbook steps
     * under that same task executionId execute immediately without prompting again.
     */
    fun approveForExecution(
        executionId: String,
        toolName: String,
        args: JSONObject = JSONObject(),
        source: String = "user",
        approvalId: String = UUID.randomUUID().toString()
    ): ExecutionConsentGrant {
        cleanupExpired()
        val recipientId = ActionApprovalRegistry.extractRecipient(args).first
        val keys = extractRecipientKeys(args)
        val msgHash = if (hasMessagePayload(args)) ActionApprovalRegistry.computeMessageHash(args) else ""

        val grant = ExecutionConsentGrant(
            executionId = executionId,
            approvalId = approvalId,
            toolName = toolName,
            recipientId = recipientId,
            recipientKeys = keys,
            messageContentHash = msgHash,
            source = source
        )
        executionConsents[executionId] = grant
        syncFlow()
        return grant
    }

    /**
     * Checks whether a given executionId already holds valid user consent for (toolName, args).
     */
    fun hasExecutionConsent(
        executionId: String?,
        toolName: String,
        args: JSONObject = JSONObject()
    ): Boolean {
        return getExecutionConsent(executionId, toolName, args) != null
    }

    fun getExecutionConsent(
        executionId: String?,
        toolName: String,
        args: JSONObject = JSONObject()
    ): ExecutionConsentGrant? {
        if (executionId.isNullOrBlank()) return null
        cleanupExpired()
        val grant = executionConsents[executionId] ?: return null
        val keys = extractRecipientKeys(args)
        val msgHash = if (hasMessagePayload(args)) ActionApprovalRegistry.computeMessageHash(args) else ""
        return if (grant.isValidFor(toolName, keys, msgHash)) grant else null
    }

    private fun extractRecipientKeys(args: JSONObject): Set<String> {
        val rawKeys = listOf("phoneNumber", "phone", "contactName", "contact", "recipient", "target", "title", "customerName")
        return rawKeys.mapNotNull { k ->
            args.optString(k, "").trim().lowercase(java.util.Locale.ROOT).takeIf { it.isNotBlank() }
        }.toSet()
    }

    /**
     * Approves the currently pending one-time confirmation AND activates its executionId
     * consent for subsequent steps/triggers of that same task.
     */
    fun approvePendingAndExecution(source: String, approvalId: String? = null): ActionApprovalRecord? {
        val approved = ActionApprovalRegistry.approvePending(source = source, approvalId = approvalId) ?: return null
        val args = try {
            JSONObject(approved.argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        approveForExecution(
            executionId = approved.executionId,
            toolName = approved.toolName,
            args = args,
            source = source,
            approvalId = approved.approvalId
        )
        return approved
    }

    /**
     * Requests interactive UI consent only if the executionId is not already consented.
     * Used by inner automation components (e.g. standalone PlaybookRunner) so there are never duplicate dialogs.
     */
    suspend fun requestConsentIfNeeded(
        executionId: String?,
        pipelineApprovalId: String?,
        toolName: String,
        actionType: String,
        target: String,
        content: String
    ): Boolean {
        if (ActionApprovalRegistry.isApprovalInFlight(pipelineApprovalId)) return true
        val checkArgs = JSONObject().apply {
            if (target.isNotBlank()) put("contactName", target)
            if (content.isNotBlank()) put("message", content)
        }
        if (hasExecutionConsent(executionId, toolName, checkArgs)) return true

        val approved = suspendCancellableCoroutine<Boolean> { cont ->
            HumanGate.requestConfirmation(actionType, target, content) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        if (approved && !executionId.isNullOrBlank()) {
            approveForExecution(
                executionId = executionId,
                toolName = toolName,
                args = checkArgs,
                source = "human_gate"
            )
        }
        return approved
    }

    /**
     * Completes and closes consent for a finished executionId so future unrelated tasks cannot reuse it.
     */
    fun completeExecution(executionId: String?) {
        if (executionId.isNullOrBlank()) return
        val removed = executionConsents.remove(executionId) != null
        ActionApprovalRegistry.invalidateExecutionApprovals(executionId)
        if (removed) {
            syncFlow()
        }
    }

    fun revokeExecutionConsent(executionId: String?) {
        completeExecution(executionId)
    }

    fun clearAll() {
        executionConsents.clear()
        syncFlow()
    }

    private fun hasMessagePayload(args: JSONObject): Boolean {
        return listOf("message", "messageText", "text", "replyText", "postText", "content")
            .any { args.optString(it, "").isNotBlank() }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        var removed = false
        val iter = executionConsents.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!entry.value.isActive || now > entry.value.expiresAt) {
                iter.remove()
                removed = true
            }
        }
        if (removed) syncFlow()
    }

    private fun syncFlow() {
        _activeExecutionConsents.value = executionConsents.toMap()
    }
}
