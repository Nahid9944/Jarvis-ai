package com.example.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class ActionCommitStage {
    PREPARING,
    READY_TO_DISPATCH,
    DISPATCHED,
    ACTION_IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    TOO_LATE_TO_CANCEL
}

data class ActionCommitRecord(
    val actionId: String,
    val toolName: String,
    val isIrreversible: Boolean,
    var stage: ActionCommitStage = ActionCommitStage.PREPARING,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Enforces atomic commit boundaries and cancellation checks before and during
 * sensitive Android OS side effects (Phone Calls, SMS, WhatsApp Messages, Deletions).
 * Prevents race conditions where a cancelled task is dispatched after the user said "Cancel".
 */
object ExecutionCommitController {

    private val activeActions = ConcurrentHashMap<String, ActionCommitRecord>()
    private val cancelledActionIds = ConcurrentHashMap.newKeySet<String>()

    private val IRREVERSIBLE_TOOLS = setOf(
        "makePhoneCall",
        "sendSmsMessage",
        "controlWhatsApp",
        "startWhatsAppVoiceCall",
        "startWhatsAppVideoCall",
        "deleteMemory",
        "deleteSkill",
        "emergencyStopAllWork"
    )

    fun registerAction(actionId: String, toolName: String): ActionCommitRecord {
        val isIrreversible = IRREVERSIBLE_TOOLS.contains(toolName)
        val record = ActionCommitRecord(
            actionId = actionId,
            toolName = toolName,
            isIrreversible = isIrreversible,
            stage = ActionCommitStage.PREPARING
        )
        activeActions[actionId] = record
        return record
    }

    /**
     * Atomic pre-dispatch check.
     * Returns true ONLY if the action has NOT been cancelled and is safe to dispatch.
     */
    fun canDispatchAction(actionId: String): Boolean {
        if (cancelledActionIds.contains(actionId)) {
            val record = activeActions[actionId]
            record?.stage = ActionCommitStage.CANCELLED
            return false
        }
        val record = activeActions[actionId] ?: return false
        if (record.stage == ActionCommitStage.CANCELLED) return false

        record.stage = ActionCommitStage.READY_TO_DISPATCH
        return true
    }

    fun markDispatched(actionId: String) {
        activeActions[actionId]?.let {
            it.stage = ActionCommitStage.DISPATCHED
        }
    }

    fun markCompleted(actionId: String) {
        activeActions[actionId]?.let {
            it.stage = ActionCommitStage.COMPLETED
        }
        activeActions.remove(actionId)
        cancelledActionIds.remove(actionId)
    }

    /**
     * Attempts cancellation. Truthfully determines whether cancellation was possible.
     */
    fun cancelAction(actionId: String): Pair<ActionCommitStage, String> {
        cancelledActionIds.add(actionId)
        val record = activeActions[actionId] ?: return Pair(
            ActionCommitStage.CANCELLED,
            "অ্যাকশনটি কার্যকর হওয়ার পূর্বেই সফলভাবে বাতিল করা হয়েছে।"
        )

        return when (record.stage) {
            ActionCommitStage.PREPARING, ActionCommitStage.READY_TO_DISPATCH -> {
                record.stage = ActionCommitStage.CANCELLED
                Pair(ActionCommitStage.CANCELLED, "অ্যাকশনটি সিস্টেমে পাঠানোর পূর্বেই তাৎক্ষণিকভাবে বাতিল করা হয়েছে।")
            }
            ActionCommitStage.DISPATCHED, ActionCommitStage.ACTION_IN_PROGRESS -> {
                if (record.isIrreversible) {
                    record.stage = ActionCommitStage.TOO_LATE_TO_CANCEL
                    Pair(
                        ActionCommitStage.TOO_LATE_TO_CANCEL,
                        "সতর্কতা: '${record.toolName}' অ্যাকশনটি ইতিমধ্যে অ্যান্ড্রয়েড সিস্টেমে পাঠানো হয়ে গেছে। এটি আর পুরোপুরি প্রত্যাহার করা সম্ভব নয়।"
                    )
                } else {
                    record.stage = ActionCommitStage.CANCELLED
                    Pair(ActionCommitStage.CANCELLED, "চলমান অ্যাকশনটি মাঝপথে থামিয়ে বাতিল করা হয়েছে।")
                }
            }
            ActionCommitStage.COMPLETED -> {
                Pair(ActionCommitStage.TOO_LATE_TO_CANCEL, "কাজটি ইতিপূর্বেই সম্পন্ন হয়ে গিয়েছিল।")
            }
            ActionCommitStage.CANCELLED, ActionCommitStage.TOO_LATE_TO_CANCEL -> {
                Pair(record.stage, "অ্যাকশনটি ইতিমধ্যে প্রক্রিয়াজাত হয়েছে।")
            }
        }
    }

    fun isActionCancelled(actionId: String): Boolean {
        return cancelledActionIds.contains(actionId) || activeActions[actionId]?.stage == ActionCommitStage.CANCELLED
    }
}
