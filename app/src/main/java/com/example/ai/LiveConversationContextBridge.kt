package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

data class QueuedContextPacket(
    val dedupKey: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Bridges Multimodal Personality, Adaptive Character Directives, Emotion Context,
 * Visual Observations, and Active Task state into the Live Conversational session.
 * Features thread-safe queuing, duplicate suppression, and offline/reconnecting resilience.
 */
object LiveConversationContextBridge {

    private const val MAX_QUEUE_SIZE = 12
    private val queueLock = Any()
    private val pendingQueue = ConcurrentLinkedDeque<QueuedContextPacket>()
    private var lastSubmittedKey: String? = null
    private var lastSubmittedTime: Long = 0L

    private val _liveState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    @Volatile
    private var flushListener: ((List<QueuedContextPacket>) -> Unit)? = null

    fun setFlushListener(listener: ((List<QueuedContextPacket>) -> Unit)?) {
        flushListener = listener
    }

    fun onConnectionStateChanged(newState: LiveSessionState) {
        _liveState.value = newState
        if (newState == LiveSessionState.CONNECTED || newState == LiveSessionState.LISTENING) {
            flushIfReady()
        }
    }

    /**
     * Queues a contextual observation (e.g., visual scene, emotion shift, screen update)
     * with duplicate suppression so noisy frames never spam the live session.
     */
    fun enqueueContextUpdate(dedupKey: String, content: String): Boolean {
        val cleanKey = dedupKey.trim()
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return false

        val now = System.currentTimeMillis()
        synchronized(queueLock) {
            if (lastSubmittedKey == cleanKey && now - lastSubmittedTime < 5_000L) {
                return false
            }
            if (pendingQueue.any { it.dedupKey == cleanKey && it.content == cleanContent }) {
                return false
            }
            if (pendingQueue.size >= MAX_QUEUE_SIZE) {
                pendingQueue.pollFirst()
            }
            pendingQueue.addLast(QueuedContextPacket(cleanKey, cleanContent, now))
            lastSubmittedKey = cleanKey
            lastSubmittedTime = now
            _queuedCount.value = pendingQueue.size
        }

        if (_liveState.value == LiveSessionState.CONNECTED || _liveState.value == LiveSessionState.LISTENING) {
            flushIfReady()
        }
        return true
    }

    fun flushIfReady(): List<QueuedContextPacket> {
        val state = _liveState.value
        if (state != LiveSessionState.CONNECTED && state != LiveSessionState.LISTENING) {
            return emptyList()
        }
        val listener = flushListener ?: return emptyList()
        val drained = drainPendingContexts()
        if (drained.isNotEmpty()) {
            try {
                listener.invoke(drained)
            } catch (_: Exception) {
                synchronized(queueLock) {
                    drained.reversed().forEach { pkt ->
                        if (pendingQueue.size < MAX_QUEUE_SIZE) {
                            pendingQueue.addFirst(pkt)
                        }
                    }
                    _queuedCount.value = pendingQueue.size
                }
                return emptyList()
            }
        }
        return drained
    }

    fun drainPendingContexts(): List<QueuedContextPacket> {
        synchronized(queueLock) {
            if (pendingQueue.isEmpty()) return emptyList()
            val list = pendingQueue.toList()
            pendingQueue.clear()
            _queuedCount.value = 0
            return list
        }
    }

    fun peekPendingCount(): Int = pendingQueue.size

    fun generateFullCompanionSystemPrompt(baseInstruction: String): String {
        val personalityPrompt = JarvisPersonalityManager.getPersonalityPrompt()
        val adaptiveDirective = AdaptivePersonalityEngine.computeDirective()
        val emotionPrompt = EmotionContextEngine.getPromptContext()
        val activeTask = ActiveTaskInterruptionManager.currentTask.value

        val taskContext = if (activeTask != null) {
            """
[CURRENT ACTIVE TASK & EXECUTION CONTEXT]:
- Goal: ${activeTask.goalBengali}
- Status: ${activeTask.status.labelEnglish} (${activeTask.status.labelBengali})
- Current Step: ${activeTask.currentStep}
- If the user interrupts to cancel or change this task, acknowledge immediately and adapt.
""".trimIndent()
        } else {
            ""
        }

        val queuedSnapshot = synchronized(queueLock) {
            if (pendingQueue.isEmpty()) "" else {
                "\n[RECENT QUEUED CONTEXT OBSERVATIONS]:\n" +
                    pendingQueue.joinToString("\n") { "- ${it.content}" }
            }
        }

        return """
$baseInstruction

$personalityPrompt

${adaptiveDirective.promptBlock}

$emotionPrompt

$taskContext
$queuedSnapshot
""".trimIndent()
    }

    fun resetForTest() {
        synchronized(queueLock) {
            pendingQueue.clear()
            lastSubmittedKey = null
            lastSubmittedTime = 0L
            _queuedCount.value = 0
            _liveState.value = LiveSessionState.DISCONNECTED
            flushListener = null
        }
    }
}
