package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class TaskStatus(val labelEnglish: String, val labelBengali: String) {
    PLANNING("Planning", "পরিকল্পনা করা হচ্ছে"),
    WAITING_FOR_PERMISSION("Waiting for Permission", "অনুমোদনের জন্য অপেক্ষমাণ"),
    WAITING_FOR_CONFIRMATION("Waiting for Confirmation", "ব্যবহারকারীর সম্মতির অপেক্ষা"),
    EXECUTING("Executing", "কাজ চলছে"),
    PAUSING("Pausing", "থামানো হচ্ছে"),
    PAUSED("Paused", "সাময়িক স্থগিত"),
    CANCELLING("Cancelling", "বাতিল করা হচ্ছে"),
    CANCELLED("Cancelled", "সফলভাবে বাতিল"),
    REPLANNING("Replanning", "পুনঃপরিকল্পনা চলছে"),
    REPLACED("Replaced", "নতুন কাজে প্রতিস্থাপিত"),
    COMMITTING("Committing", "চূড়ান্ত প্রেরণ চলছে"),
    PARTIAL_SUCCESS("Partial Success", "আংশিক সম্পন্ন"),
    COMPLETED("Completed", "সম্পূর্ণ সম্পন্ন"),
    FAILED("Failed", "ব্যর্থ"),
    UNVERIFIED("Unverified", "যাচাই করা যায়নি"),
    TOO_LATE_TO_CANCEL("Too Late to Cancel", "ইতিমধ্যে কার্যকর হওয়ায় বাতিল করা সম্ভব নয়")
}

data class ActiveTaskState(
    val taskId: String = UUID.randomUUID().toString(),
    val goalBengali: String,
    val toolName: String,
    var status: TaskStatus = TaskStatus.EXECUTING,
    var currentStep: String = "আরম্ভ",
    val completedSteps: MutableList<String> = mutableListOf(),
    val pendingSteps: MutableList<String> = mutableListOf(),
    val params: MutableMap<String, Any?> = mutableMapOf(),
    var isIrreversibleDispatched: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class InterruptionIntent(val labelBengali: String) {
    STOP_SPEECH_ONLY("শুধুমাত্র কথা বলা বন্ধ করা"),
    PAUSE_CURRENT_TASK("চলমান কাজ সাময়িক স্থগিত করা"),
    CANCEL_CURRENT_TASK("চলমান কাজ পুরোপুরি বাতিল করা"),
    REPLACE_CURRENT_TASK("চলমান কাজ বাতিল করে নতুন কাজ দেওয়া"),
    MODIFY_TASK_PARAMETER("চলমান কাজের প্যারামিটার পরিবর্তন করা"),
    ASK_NEW_QUESTION("কথা থামিয়ে নতুন প্রশ্ন করা"),
    RESUME_TASK("স্থগিত কাজ পুনরায় চালু করা"),
    EMERGENCY_STOP("জরুরি সব কাজ বন্ধ করা"),
    UNKNOWN_INTERRUPTION("সাধারণ কথোপকথন")
}

data class InterruptionHandlingResult(
    val intent: InterruptionIntent,
    val handled: Boolean,
    val messageBengali: String,
    val affectedTaskId: String? = null,
    val replacementTask: ActiveTaskState? = null
)

/**
 * Manages active task lifecycle, cancellation, pause/resume, and voice-driven replacement.
 */
object ActiveTaskInterruptionManager {

    private val _currentTask = MutableStateFlow<ActiveTaskState?>(null)
    val currentTask: StateFlow<ActiveTaskState?> = _currentTask.asStateFlow()

    private val _lastInterruptionStatus = MutableStateFlow<String?>(null)
    val lastInterruptionStatus: StateFlow<String?> = _lastInterruptionStatus.asStateFlow()

    fun registerNewTask(goalBn: String, toolName: String, steps: List<String> = emptyList(), params: Map<String, Any?> = emptyMap()): ActiveTaskState {
        val task = ActiveTaskState(
            goalBengali = goalBn,
            toolName = toolName,
            status = TaskStatus.EXECUTING,
            currentStep = steps.firstOrNull() ?: "প্রক্রিয়াকরণ",
            pendingSteps = steps.drop(1).toMutableList(),
            params = params.toMutableMap()
        )
        _currentTask.value = task
        VoiceTurnManager.setTurnState(TurnState.TASK_IN_PROGRESS)
        ExecutionCommitController.registerAction(task.taskId, toolName)
        return task
    }

    fun completeTask(taskId: String, success: Boolean, summaryBn: String) {
        val task = _currentTask.value
        if (task != null && task.taskId == taskId) {
            task.status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
            _lastInterruptionStatus.value = summaryBn
            VoiceTurnManager.setTurnState(TurnState.IDLE)
            ExecutionCommitController.markCompleted(taskId)
            _currentTask.value = null
        }
    }

    fun onSpeechInterrupted(reason: String) {
        // If user interrupts voice while a task is running, check if task should be paused
        val task = _currentTask.value ?: return
        if (task.status == TaskStatus.EXECUTING) {
            _lastInterruptionStatus.value = "ভয়েস ইন্টারাপশন সনাক্ত: ব্যবহারকারীর নতুন নির্দেশ শোনা হচ্ছে..."
        }
    }

    /**
     * Parses natural language utterances during assistant speaking or task execution.
     */
    fun classifyInterruptionIntent(utterance: String): InterruptionIntent {
        val lower = java.text.Normalizer.normalize(utterance.trim(), java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)

        return when {
            // Emergency stop
            lower.contains("সব বন্ধ") || lower.contains("ইমার্জেন্সি স্টপ") || lower.contains("emergency stop") -> {
                InterruptionIntent.EMERGENCY_STOP
            }

            // Stop speech only
            lower.contains("কথা থামাও") || lower.contains("চুপ করো") || lower.contains("কথা বন্ধ") ||
            lower.contains("কাজ চালিয়ে যাও") || lower.contains("stop speaking") || lower.contains("be quiet") -> {
                InterruptionIntent.STOP_SPEECH_ONLY
            }

            // Pause task
            lower.contains("একটু দাঁড়াও") || lower.contains("একটু অপেক্ষা করো") || lower.contains("পজ করো") ||
            lower.contains("থামো একটু") || lower.contains("hold on") || lower.contains("wait a minute") || lower.contains("pause") -> {
                InterruptionIntent.PAUSE_CURRENT_TASK
            }

            // Cancel task
            lower.contains("কাজটা বন্ধ করো") || lower.contains("কাজ বন্ধ করো") || lower.contains("থামো") ||
            lower.contains("cancel করো") || lower.contains("বাতিল করো") || lower.contains("আর এগিও না") ||
            lower.contains("ফোন দিও না") || lower.contains("আগের কাজ বাদ দাও") || lower.contains("cancel task") ||
            lower.contains("stop task") || lower.contains("stop koro") || lower.contains("stop all") ||
            lower.contains("sob kaj") || lower.contains("করো না") -> {
                InterruptionIntent.CANCEL_CURRENT_TASK
            }

            // Replace task (e.g. "না না ফোন দিও না, বরং WhatsApp-এ মেসেজ দাও")
            (lower.contains("না না") || lower.contains("বরং") || lower.contains("তার বদলে") || lower.contains("instead")) &&
            (lower.contains("মেসেজ") || lower.contains("কল") || lower.contains("সার্চ") || lower.contains("পাঠাও")) -> {
                InterruptionIntent.REPLACE_CURRENT_TASK
            }

            // Modify parameter (e.g. "না রহিম না, রাকিবকে ফোন করো")
            lower.contains("না রহিম না") || lower.contains("অন্য কাউকে") || lower.contains("নাম্বারটা পরিবর্তন") ||
            lower.contains("contact পরিবর্তন") || lower.contains("change contact") -> {
                InterruptionIntent.MODIFY_TASK_PARAMETER
            }

            // Resume task
            lower.contains("আবার শুরু করো") || lower.contains("চালিয়ে যাও") || lower.contains("রিজিউম করো") ||
            lower.contains("resume") || lower.contains("continue") -> {
                InterruptionIntent.RESUME_TASK
            }

            // Ask new question (e.g. "এক মিনিট, ফোনে কত চার্জ আছে?")
            lower.contains("কত চার্জ") || lower.contains("কয়টা বাজে") || lower.contains("আবহাওয়া কেমন") ||
            lower.contains("প্রশ্নের উত্তর দাও") || lower.contains("tell me") || lower.contains("what is") -> {
                InterruptionIntent.ASK_NEW_QUESTION
            }

            else -> InterruptionIntent.UNKNOWN_INTERRUPTION
        }
    }

    /**
     * Executes the appropriate interruption handling action.
     */
    fun handleInterruptionCommand(utterance: String): InterruptionHandlingResult {
        val intent = classifyInterruptionIntent(utterance)
        val active = _currentTask.value

        return when (intent) {
            InterruptionIntent.STOP_SPEECH_ONLY -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("user_requested_speech_stop")
                VoiceTurnManager.setTurnState(TurnState.TASK_IN_PROGRESS)
                InterruptionHandlingResult(
                    intent = intent,
                    handled = true,
                    messageBengali = "কথা থামানো হয়েছে। ব্যাকগ্রাউন্ডে কাজটি সক্রিয় রয়েছে।"
                )
            }

            InterruptionIntent.PAUSE_CURRENT_TASK -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("task_paused")
                if (active != null) {
                    active.status = TaskStatus.PAUSED
                    VoiceTurnManager.setTurnState(TurnState.TASK_PAUSED)
                    _lastInterruptionStatus.value = "কাজটি সাময়িক স্থগিত রাখা হয়েছে।"
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = true,
                        messageBengali = "'${active.goalBengali}' কাজটি সাময়িক স্থগিত রাখা হয়েছে। আপনি প্রস্তুত হলে 'আবার শুরু করো' বলুন।",
                        affectedTaskId = active.taskId
                    )
                } else {
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = true,
                        messageBengali = "বর্তমানে কোনো চলমান সক্রিয় কাজ নেই।"
                    )
                }
            }

            InterruptionIntent.CANCEL_CURRENT_TASK -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("task_cancelled")
                if (active != null) {
                    val (stage, cancelMsg) = ExecutionCommitController.cancelAction(active.taskId)
                    if (stage == ActionCommitStage.TOO_LATE_TO_CANCEL) {
                        active.status = TaskStatus.TOO_LATE_TO_CANCEL
                        VoiceTurnManager.setTurnState(TurnState.IDLE)
                        InterruptionHandlingResult(
                            intent = intent,
                            handled = true,
                            messageBengali = cancelMsg,
                            affectedTaskId = active.taskId
                        )
                    } else {
                        active.status = TaskStatus.CANCELLED
                        VoiceTurnManager.setTurnState(TurnState.TASK_CANCELLED)
                        _currentTask.value = null
                        InterruptionHandlingResult(
                            intent = intent,
                            handled = true,
                            messageBengali = "'${active.goalBengali}' সফলভাবে বাতিল করা হয়েছে। আর কোনো কার্যক্রম অগ্রসর হবে না।",
                            affectedTaskId = active.taskId
                        )
                    }
                } else {
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = true,
                        messageBengali = "বাতিল করার মতো কোনো চলমান কাজ পাওয়া যায়নি।"
                    )
                }
            }

            InterruptionIntent.REPLACE_CURRENT_TASK -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("task_replaced")
                val oldGoal = active?.goalBengali ?: "পূর্বের কাজ"
                active?.let {
                    ExecutionCommitController.cancelAction(it.taskId)
                    it.status = TaskStatus.REPLACED
                }
                VoiceTurnManager.setTurnState(TurnState.TASK_REPLACED)
                val replacement = TaskReplacementPlanner.planReplacement(utterance, active)
                _currentTask.value = replacement

                InterruptionHandlingResult(
                    intent = intent,
                    handled = true,
                    messageBengali = "$oldGoal বাতিল করা হয়েছে। তার পরিবর্তে নতুন কাজ প্রস্তুত করা হচ্ছে: ${replacement?.goalBengali ?: "নতুন নির্দেশ"}",
                    affectedTaskId = active?.taskId,
                    replacementTask = replacement
                )
            }

            InterruptionIntent.MODIFY_TASK_PARAMETER -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("parameter_modified")
                if (active != null) {
                    val newContact = utterance.substringAfter("না, ", "").substringBefore("কে", "").trim()
                    if (newContact.isNotBlank()) {
                        active.params["contact"] = newContact
                    }
                    active.status = TaskStatus.REPLANNING
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = true,
                        messageBengali = "কাজের প্যারামিটার আপডেট করা হয়েছে। নতুন তথ্যানুযায়ী সমন্বয় করা হচ্ছে।",
                        affectedTaskId = active.taskId
                    )
                } else {
                    InterruptionHandlingResult(intent, false, "প্যারামিটার পরিবর্তন করার মতো কোনো সক্রিয় কাজ নেই।")
                }
            }

            InterruptionIntent.RESUME_TASK -> {
                if (active != null && active.status == TaskStatus.PAUSED) {
                    active.status = TaskStatus.EXECUTING
                    VoiceTurnManager.setTurnState(TurnState.TASK_IN_PROGRESS)
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = true,
                        messageBengali = "'${active.goalBengali}' কাজটি পূর্বের স্থগিত বিন্দু থেকে পুনরায় চালু করা হয়েছে।",
                        affectedTaskId = active.taskId
                    )
                } else {
                    InterruptionHandlingResult(
                        intent = intent,
                        handled = false,
                        messageBengali = "পুনরায় চালু করার মতো কোনো স্থগিত কাজ নেই।"
                    )
                }
            }

            InterruptionIntent.EMERGENCY_STOP -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("emergency_stop")
                active?.let {
                    ExecutionCommitController.cancelAction(it.taskId)
                    it.status = TaskStatus.CANCELLED
                }
                _currentTask.value = null
                VoiceTurnManager.setTurnState(TurnState.TASK_CANCELLED)
                InterruptionHandlingResult(
                    intent = intent,
                    handled = true,
                    messageBengali = "জরুরি স্টপ সক্রিয় করা হয়েছে। সমস্ত চলমান ও অপেক্ষমাণ কাজ অবিলম্বে বাতিল করা হয়েছে।"
                )
            }

            InterruptionIntent.ASK_NEW_QUESTION -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("new_question")
                VoiceTurnManager.setTurnState(TurnState.LISTENING_TO_USER)
                InterruptionHandlingResult(
                    intent = intent,
                    handled = true,
                    messageBengali = "আপনার নতুন প্রশ্নের উত্তর তৈরি করছি..."
                )
            }

            InterruptionIntent.UNKNOWN_INTERRUPTION -> {
                PlaybackInterruptionBridge.triggerInstantBargeIn("user_speaking")
                InterruptionHandlingResult(
                    intent = intent,
                    handled = false,
                    messageBengali = "আমি শুনছি..."
                )
            }
        }
    }
}
