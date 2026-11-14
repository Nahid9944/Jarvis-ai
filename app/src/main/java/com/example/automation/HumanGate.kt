package com.example.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class GateRequest(
    val id: String = UUID.randomUUID().toString(),
    val actionType: String,        // "SEND_MESSAGE", "MAKE_CALL", "POST_PUBLIC", "DELETE_MESSAGE", "BLOCK_USER"
    val targetName: String,
    val content: String,
    val promptBn: String,
    val isDoubleConfirm: Boolean = false,
    val onResponse: (Boolean) -> Unit
)

object HumanGate {

    private val _pendingRequest = MutableStateFlow<GateRequest?>(null)
    val pendingRequest: StateFlow<GateRequest?> = _pendingRequest.asStateFlow()

    // Flag for testing or programmatic simulation
    var autoApproveForTest: Boolean? = null

    fun requiresGate(risk: RiskLevel, actionType: String): Boolean {
        return risk == RiskLevel.EXTERNAL_COMM ||
               risk == RiskLevel.DESTRUCTIVE ||
               actionType.startsWith("SEND_") ||
               actionType.startsWith("POST_") ||
               actionType.startsWith("CALL_") ||
               actionType.startsWith("DELETE_") ||
               actionType.startsWith("BLOCK_")
    }

    fun buildPrompt(actionType: String, target: String, content: String): Pair<String, Boolean> {
        val cleanTarget = target.ifBlank { "প্রাপক" }
        return when (actionType.uppercase()) {
            "SEND_MESSAGE", "WA_SEND", "SMS_SEND" -> {
                val snippet = if (content.length > 30) content.take(30) + "..." else content
                Pair("$cleanTarget-কে লিখব: '$snippet' — পাঠাব?", false)
            }
            "MAKE_CALL", "WA_CALL", "PHONE_CALL" -> {
                Pair("$cleanTarget-কে কল দেব। নিশ্চিত?", false)
            }
            "POST_PUBLIC", "FB_POST" -> {
                val snippet = if (content.length > 30) content.take(30) + "..." else content
                Pair("ফেসবুকে পোস্ট করব: '$snippet' — প্রকাশ করব?", false)
            }
            "COMMENT", "YT_COMMENT", "FB_COMMENT" -> {
                Pair("এখানে কমেন্ট করব: '$content'। পোস্ট করব?", false)
            }
            "DELETE_MESSAGE", "WA_DELETE" -> {
                Pair("সবার জন্য মেসেজটি মুছে ফেলব — এটি ফেরানো যাবে না। নিশ্চিত?", true)
            }
            "BLOCK_USER" -> {
                Pair("$cleanTarget-কে ব্লক করব — নিশ্চিত?", true)
            }
            else -> {
                Pair("আপনি কি '$cleanTarget'-এর সাথে এই কাজটি সম্পন্ন করতে চান?", false)
            }
        }
    }

    fun requestConfirmation(
        actionType: String,
        target: String,
        content: String,
        onResponse: (Boolean) -> Unit
    ) {
        val testOverride = autoApproveForTest
        if (testOverride != null) {
            onResponse(testOverride)
            return
        }

        val (promptBn, isDouble) = buildPrompt(actionType, target, content)
        val request = GateRequest(
            actionType = actionType,
            targetName = target,
            content = content,
            promptBn = promptBn,
            isDoubleConfirm = isDouble,
            onResponse = onResponse
        )
        _pendingRequest.value = request
    }

    fun respond(approved: Boolean) {
        val req = _pendingRequest.value
        _pendingRequest.value = null
        req?.onResponse?.invoke(approved)
    }

    fun dismiss() {
        respond(false)
    }
}
