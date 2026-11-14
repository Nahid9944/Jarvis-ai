package com.example.permissions

import java.util.UUID

/**
 * Explicit end-to-end execution lifecycle state machine for JARVIS actions.
 */
enum class JarvisActionState(val titleBn: String) {
    REQUEST_RECEIVED("অনুরোধ গৃহীত"),
    ACTION_RESOLVED("অ্যাকশন শনাক্ত"),
    PARAMETERS_VALIDATED("প্যারামিটার যাচাই সম্পন্ন"),
    OS_PERMISSION_CHECKED("সিস্টেম পারমিশন যাচাই"),
    WAITING_FOR_OS_PERMISSION("অ্যান্ড্রয়েড অনুমতির অপেক্ষায়"),
    OS_PERMISSION_GRANTED("অ্যান্ড্রয়েড অনুমতি প্রাপ্ত"),
    ACTION_APPROVAL_REQUIRED("ব্যবহারকারীর সম্মতি প্রয়োজন"),
    WAITING_FOR_ACTION_APPROVAL("ব্যবহারকারীর 'হ্যাঁ' সম্মতির অপেক্ষায়"),
    APPROVED("একবারের জন্য অনুমোদিত"),
    READY_TO_EXECUTE("কার্যকর করার জন্য প্রস্তুত"),
    DISPATCHED("কার্যকর করা হচ্ছে"),
    VERIFYING("ফলাফল যাচাই হচ্ছে"),
    COMPLETED("সফলভাবে সম্পন্ন"),
    FAILED("ব্যর্থ হয়েছে"),
    UNVERIFIED("আংশিক / যাচাই অসম্পূর্ণ"),
    CANCELLED("বাতিল করা হয়েছে")
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CONSUMED,
    CANCELLED
}

enum class DispatchStatus {
    NOT_DISPATCHED,
    DISPATCHED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ApprovalReplyDecision {
    APPROVE,
    REJECT,
    CORRECTION,
    UNRELATED
}

/**
 * Structured One-Time Action Approval Record.
 * Prevents double confirmation AND prevents unauthorized reuse if recipient, message, or target changes.
 */
data class ActionApprovalRecord(
    val approvalId: String = UUID.randomUUID().toString(),
    val executionId: String = UUID.randomUUID().toString(),
    val actionType: String,
    val toolName: String,
    val targetApp: String,
    val recipientId: String,
    val recipientName: String,
    val messageContentHash: String,
    val paramsHash: String,
    val promptBn: String,
    val reasonBn: String,
    val argsJson: String,
    val caller: String,
    val requiresDoubleConfirm: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val confirmationTime: Long? = null,
    val expiryTime: Long = System.currentTimeMillis() + DEFAULT_APPROVAL_TTL_MS,
    val approvalStatus: ApprovalStatus = ApprovalStatus.PENDING,
    val dispatchStatus: DispatchStatus = DispatchStatus.NOT_DISPATCHED,
    val approvalSource: String = ""
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiryTime

    companion object {
        const val DEFAULT_APPROVAL_TTL_MS = 120_000L
    }
}

/**
 * Pending task waiting for an Android Runtime or Special Access OS permission grant.
 * Preserves any prior user approvalId so returning from Settings resumes without asking twice.
 */
data class PendingOsPermissionAction(
    val pendingId: String = UUID.randomUUID().toString(),
    val executionId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val descriptionBn: String,
    val argsJson: String,
    val caller: String,
    val requiredPermissionIds: List<String>,
    val missingPermissionIds: List<String>,
    val approvedIdIfAny: String? = null,
    val state: JarvisActionState = JarvisActionState.WAITING_FOR_OS_PERMISSION,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Scoped, revocable, expirable rule ONLY allowed for safe/low-risk read-only tools.
 * External communication, calls, deletions, and financial tools are strictly barred from blanket rules.
 */
data class TrustedActionRule(
    val ruleId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val scopeDescriptionBn: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
    val isEnabled: Boolean = true
) {
    fun isValid(now: Long = System.currentTimeMillis()): Boolean = isEnabled && now <= expiresAt
}

data class PermissionAuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val approvalId: String?,
    val toolName: String,
    val targetLabel: String,
    val state: JarvisActionState,
    val approvalStatus: ApprovalStatus?,
    val source: String,
    val detailsBn: String
)

object ActionSecurityPolicy {
    /**
     * Sensitive tools that MUST NEVER support blanket or silent trusted rules.
     * Each execution requires explicit one-time confirmation bound to exact target & content hash.
     */
    val SENSITIVE_NEVER_AUTO_APPROVE_TOOLS: Set<String> = setOf(
        "makePhoneCall",
        "directCall",
        "callEmergencyNumber",
        "sendSmsMessage",
        "sendSms",
        "sendQuickSms",
        "sendWhatsAppMessage",
        "controlWhatsApp",
        "startWhatsAppVoiceCall",
        "startWhatsAppVideoCall",
        "replyFromNotification",
        "sendNotificationReply",
        "shareViaIntent",
        "deleteAiTask",
        "deleteMemory",
        "deleteNote",
        "runPlaybook",
        "runAutonomousTask"
    )

    fun isHighRiskNeverTrusted(toolName: String): Boolean =
        toolName in SENSITIVE_NEVER_AUTO_APPROVE_TOOLS
}
