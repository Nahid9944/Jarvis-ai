package com.example.service

import android.content.Context
import android.telecom.Call
import android.util.Log
import com.example.data.repository.JarvisNotificationRepository
import com.example.util.JarvisVoiceSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Autonomous Voice Announcement Coordinator for NAHID JARVIS AI.
 * Bridges intercepted notifications and incoming telecom phone calls with
 * GeminiLiveAnnouncementAdapter (zero Android TTS) and privacy-first OTP protection.
 */
object NotificationAnnouncementCoordinator {

    private const val TAG = "NotifAnnouncementCoord"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _announcements = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    private var isStarted = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (isStarted) return
        isStarted = true
        appContext = context.applicationContext
        JarvisVoiceSpeaker.init(context)
        JarvisNotificationRepository.loadPersistedHistory(context)

        GeminiLiveAnnouncementAdapter.setAnnouncementPersistedCallback { eventKey ->
            val ctx = appContext ?: return@setAnnouncementPersistedCallback
            scope.launch(Dispatchers.IO) {
                JarvisNotificationRepository.markNotificationAnnounced(ctx, eventKey)
            }
        }

        // 1. Observe incoming Android notifications
        scope.launch {
            JarvisNotificationListenerService.notificationEvents.collect { notif ->
                handleIncomingNotification(notif)
            }
        }

        // 2. Observe incoming telecom phone calls
        scope.launch {
            JarvisInCallService.currentCallInfo.collectLatest { callInfo ->
                handleCallStateChange(callInfo)
            }
        }

        Log.i(TAG, "NotificationAnnouncementCoordinator initialized.")
    }

    private fun handleIncomingNotification(notif: EnhancedNotification) {
        val settings = JarvisNotificationListenerService.settings
        if (!settings.isEnabled || !settings.isInstantAnnouncementEnabled) {
            return
        }

        if (settings.quietHoursEnabled && isWithinQuietHours()) {
            Log.d(TAG, "Quiet hours active, skipping notification voice announcement.")
            return
        }

        val lowerPkg = notif.packageName.lowercase()
        val isAllowed = when {
            lowerPkg.contains("whatsapp") -> settings.announceWhatsApp
            lowerPkg.contains("orca") || lowerPkg.contains("messenger") -> settings.announceMessenger
            lowerPkg.contains("facebook") || lowerPkg.contains("katana") -> settings.announceFacebook
            lowerPkg.contains("messaging") || lowerPkg.contains("mms") || lowerPkg.contains("sms") -> settings.announceSms
            lowerPkg.contains("gm") || lowerPkg.contains("email") -> settings.announceGmail
            else -> true
        }

        if (!isAllowed) return

        // Sensitive OTP / PIN Security Protection: Never expose verification codes aloud!
        val speechText = if (notif.isSensitiveOtp) {
            "স্যার, ${notif.appName} থেকে একটি সংবেদনশীল ওটিপি বা ভেরিফিকেশন কোড এসেছে। নিরাপত্তার জন্য কোডটি স্ক্রিনে দেখে নিন।"
        } else if (settings.autoReadFullMessage && notif.text.isNotBlank()) {
            val messageToRead = if (notif.bigText.isNotBlank()) notif.bigText else notif.text
            "স্যার, ${notif.appName}-এ ${notif.sender} লিখেছেন: $messageToRead"
        } else {
            "স্যার, ${notif.sender} আপনার ${notif.appName}-এ একটি মেসেজ পাঠিয়েছেন।"
        }

        _announcements.tryEmit(speechText)
        GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = notif.key,
            rawText = speechText,
            category = QueuedAnnouncement.CATEGORY_NOTIFICATION,
            isSensitiveOtp = notif.isSensitiveOtp,
            priority = if (notif.isSensitiveOtp) QueuedAnnouncement.PRIORITY_HIGH else QueuedAnnouncement.PRIORITY_NORMAL
        )
    }

    private fun handleCallStateChange(callInfo: JarvisCallInfo) {
        val inOngoingCall = callInfo.state == Call.STATE_ACTIVE || callInfo.state == Call.STATE_DIALING
        GeminiLiveAnnouncementAdapter.setInActiveCall(inOngoingCall)

        if (callInfo.state != Call.STATE_RINGING) {
            return
        }

        val speech = when {
            callInfo.callerName.isNotBlank() && callInfo.callerName != callInfo.phoneNumber -> {
                "স্যার, ${callInfo.callerName} আপনাকে ফোন করছেন।"
            }
            callInfo.phoneNumber.isNotBlank() -> {
                "স্যার, একটি অপরিচিত নম্বর থেকে কল এসেছে: ${callInfo.phoneNumber}"
            }
            else -> {
                "স্যার, একজন অজ্ঞাত পরিচয়ের ব্যক্তি আপনাকে ফোন করছেন।"
            }
        }

        _announcements.tryEmit(speech)
        GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = "call_${callInfo.phoneNumber}_${callInfo.connectTimeMillis}",
            rawText = speech,
            category = QueuedAnnouncement.CATEGORY_INCOMING_CALL,
            isSensitiveOtp = false,
            priority = QueuedAnnouncement.PRIORITY_URGENT
        )
    }

    private fun isWithinQuietHours(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }
}
