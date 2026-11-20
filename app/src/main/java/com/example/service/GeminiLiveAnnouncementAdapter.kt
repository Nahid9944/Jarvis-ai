package com.example.service

import android.util.Log
import com.example.util.JarvisVoiceSpeaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Queued announcement item awaiting or dispatched via Gemini Live Audio Output.
 * Zero Android TextToSpeech is used anywhere in this pipeline.
 */
data class QueuedAnnouncement(
    val id: Long = System.currentTimeMillis(),
    val eventKey: String,
    val text: String,
    val category: String = CATEGORY_NOTIFICATION,
    val isSensitiveOtp: Boolean = false,
    val priority: Int = PRIORITY_NORMAL,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CATEGORY_INCOMING_CALL = "INCOMING_CALL"
        const val CATEGORY_NOTIFICATION = "NOTIFICATION"
        const val CATEGORY_ALARM_REMINDER = "ALARM_REMINDER"
        const val CATEGORY_SYSTEM_ALERT = "SYSTEM_ALERT"

        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_URGENT = 3
    }
}

enum class AnnouncementDispatchOutcome {
    DISPATCHED_LIVE,
    QUEUED_OFFLINE,
    DEFERRED_IN_CALL,
    SKIPPED_DUPLICATE,
    SKIPPED_BLANK
}

/**
 * Gemini Live Announcement Adapter:
 * - Routes spoken notifications, caller announcements, and reminders exclusively to the
 *   Gemini Live Audio Output session.
 * - Queues announcements when Gemini Live is disconnected or when an active phone call is
 *   in progress, and automatically flushes the queue when Gemini Live becomes ready.
 * - Enforces deduplication and strict OTP digit redaction so verification codes are never spoken.
 */
object GeminiLiveAnnouncementAdapter {

    private const val TAG = "GeminiLiveAnnounce"
    private const val MAX_QUEUE_SIZE = 25
    private const val DEDUP_WINDOW_MS = 4500L
    private const val CALL_DEDUP_WINDOW_MS = 12000L

    private val lock = Any()
    private val pendingQueue = mutableListOf<QueuedAnnouncement>()
    private val recentEventTimes = LinkedHashMap<String, Long>()

    @Volatile
    private var liveDispatcher: ((String) -> Unit)? = null

    @Volatile
    private var announcementPersistedCallback: ((String) -> Unit)? = null

    private val _isLiveSessionReady = MutableStateFlow(false)
    val isLiveSessionReady: StateFlow<Boolean> = _isLiveSessionReady.asStateFlow()

    private val _isInActiveCall = MutableStateFlow(false)
    val isInActiveCall: StateFlow<Boolean> = _isInActiveCall.asStateFlow()

    private val _pendingAnnouncements = MutableStateFlow<List<QueuedAnnouncement>>(emptyList())
    val pendingAnnouncements: StateFlow<List<QueuedAnnouncement>> = _pendingAnnouncements.asStateFlow()

    private val _lastDispatchedAnnouncement = MutableStateFlow<String>("")
    val lastDispatchedAnnouncement: StateFlow<String> = _lastDispatchedAnnouncement.asStateFlow()

    private val otpTokenRegex = Regex("""\b\d{4,8}\b""")
    private val otpKeywordRegex = Regex(
        """\b(otp|one[\s-]?time|verification\s*code|pin|passcode|security\s*code|auth\s*code)\b|ওটিপি|ভেরিফিকেশন|পিন|গোপন\s*কোড""",
        RegexOption.IGNORE_CASE
    )

    fun setAnnouncementPersistedCallback(callback: ((String) -> Unit)?) {
        announcementPersistedCallback = callback
    }

    fun attachLiveSession(dispatcher: (String) -> Unit) {
        liveDispatcher = dispatcher
        setLiveSessionReady(true)
    }

    fun detachLiveSession() {
        liveDispatcher = null
        _isLiveSessionReady.value = false
    }

    fun setLiveSessionReady(ready: Boolean, dispatcher: ((String) -> Unit)? = null) {
        if (dispatcher != null) {
            liveDispatcher = dispatcher
        }
        val wasReady = _isLiveSessionReady.value
        _isLiveSessionReady.value = ready && liveDispatcher != null
        if (_isLiveSessionReady.value && !wasReady) {
            flushPendingQueue()
        } else if (_isLiveSessionReady.value && pendingQueue.isNotEmpty()) {
            flushPendingQueue()
        }
    }

    fun setInActiveCall(inCall: Boolean) {
        val wasInCall = _isInActiveCall.value
        _isInActiveCall.value = inCall
        if (wasInCall && !inCall && _isLiveSessionReady.value) {
            flushPendingQueue()
        }
    }

    fun sanitizeForVoice(text: String, isSensitiveOtp: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        val containsOtpKeyword = isSensitiveOtp || otpKeywordRegex.containsMatchIn(trimmed)
        if (!containsOtpKeyword) return trimmed

        // Redact 4-8 digit codes so sensitive verification codes are never read aloud
        val redacted = otpTokenRegex.replace(trimmed, "[গোপন-কোড]")
        return if (redacted == trimmed && isSensitiveOtp) {
            "সিকিউরিটি ওটিপি বার্তা এসেছে। নিরাপত্তার জন্য কোডটি স্ক্রিনে দেখুন।"
        } else {
            redacted
        }
    }

    fun enqueueOrDispatch(
        eventKey: String,
        rawText: String,
        category: String = QueuedAnnouncement.CATEGORY_NOTIFICATION,
        isSensitiveOtp: Boolean = false,
        priority: Int = QueuedAnnouncement.PRIORITY_NORMAL
    ): AnnouncementDispatchOutcome {
        val sanitized = sanitizeForVoice(rawText, isSensitiveOtp)
        if (sanitized.isBlank()) return AnnouncementDispatchOutcome.SKIPPED_BLANK

        val now = System.currentTimeMillis()
        val dedupKey = "${category}:${eventKey.ifBlank { sanitized.lowercase(Locale.ROOT).take(64) }}"
        val dedupWindow = if (category == QueuedAnnouncement.CATEGORY_INCOMING_CALL) CALL_DEDUP_WINDOW_MS else DEDUP_WINDOW_MS

        synchronized(lock) {
            val prevTime = recentEventTimes[dedupKey]
            if (prevTime != null && (now - prevTime) < dedupWindow) {
                return AnnouncementDispatchOutcome.SKIPPED_DUPLICATE
            }
            recentEventTimes[dedupKey] = now
            if (recentEventTimes.size > 100) {
                val oldestKey = recentEventTimes.entries.firstOrNull()?.key
                if (oldestKey != null) recentEventTimes.remove(oldestKey)
            }
        }

        val item = QueuedAnnouncement(
            id = now,
            eventKey = eventKey,
            text = sanitized,
            category = category,
            isSensitiveOtp = isSensitiveOtp,
            priority = priority,
            createdAt = now
        )

        // Update UI visible announcement history without triggering recursive dispatcher
        JarvisVoiceSpeaker.recordUiAnnouncement(sanitized)

        val dispatcher = liveDispatcher
        val liveReady = _isLiveSessionReady.value && dispatcher != null
        val inCallDeferred = _isInActiveCall.value && category != QueuedAnnouncement.CATEGORY_INCOMING_CALL

        if (liveReady && !inCallDeferred && dispatcher != null) {
            return try {
                dispatcher.invoke(sanitized)
                _lastDispatchedAnnouncement.value = sanitized
                if (eventKey.isNotBlank()) {
                    announcementPersistedCallback?.invoke(eventKey)
                }
                AnnouncementDispatchOutcome.DISPATCHED_LIVE
            } catch (e: Exception) {
                Log.w(TAG, "Live dispatch threw exception, queueing item instead", e)
                enqueueInternal(item)
                AnnouncementDispatchOutcome.QUEUED_OFFLINE
            }
        }

        enqueueInternal(item)
        return if (inCallDeferred) {
            AnnouncementDispatchOutcome.DEFERRED_IN_CALL
        } else {
            AnnouncementDispatchOutcome.QUEUED_OFFLINE
        }
    }

    private fun enqueueInternal(item: QueuedAnnouncement) {
        synchronized(lock) {
            pendingQueue.removeAll { it.eventKey.isNotBlank() && it.eventKey == item.eventKey }
            pendingQueue.add(item)
            if (pendingQueue.size > MAX_QUEUE_SIZE) {
                val dropCandidate = pendingQueue.minByOrNull { it.priority * 1_000_000_000_000L - it.createdAt }
                if (dropCandidate != null) {
                    pendingQueue.remove(dropCandidate)
                } else {
                    pendingQueue.removeAt(0)
                }
            }
            _pendingAnnouncements.value = ArrayList(pendingQueue)
        }
    }

    fun flushPendingQueue(): Int {
        val dispatcher = liveDispatcher
        if (!_isLiveSessionReady.value || dispatcher == null) return 0

        val toFlush: List<QueuedAnnouncement>
        synchronized(lock) {
            if (pendingQueue.isEmpty()) return 0
            val eligible = if (_isInActiveCall.value) {
                pendingQueue.filter { it.category == QueuedAnnouncement.CATEGORY_INCOMING_CALL }
            } else {
                pendingQueue.toList()
            }
            toFlush = eligible.sortedWith(compareByDescending<QueuedAnnouncement> { it.priority }.thenBy { it.createdAt })
            pendingQueue.removeAll(toFlush.toSet())
            _pendingAnnouncements.value = ArrayList(pendingQueue)
        }

        if (toFlush.isEmpty()) return 0

        var dispatched = 0
        for (item in toFlush) {
            try {
                dispatcher.invoke(item.text)
                _lastDispatchedAnnouncement.value = item.text
                if (item.eventKey.isNotBlank()) {
                    announcementPersistedCallback?.invoke(item.eventKey)
                }
                dispatched++
            } catch (e: Exception) {
                Log.w(TAG, "Failed flushing announcement ${item.eventKey}, re-queueing", e)
                enqueueInternal(item)
                break
            }
        }
        return dispatched
    }

    fun getPendingCount(): Int = synchronized(lock) { pendingQueue.size }

    fun removePendingByEventKey(eventKey: String) {
        if (eventKey.isBlank()) return
        synchronized(lock) {
            val removed = pendingQueue.removeAll { it.eventKey == eventKey }
            if (removed) {
                _pendingAnnouncements.value = ArrayList(pendingQueue)
            }
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            pendingQueue.clear()
            _pendingAnnouncements.value = emptyList()
        }
        _lastDispatchedAnnouncement.value = ""
    }
}
