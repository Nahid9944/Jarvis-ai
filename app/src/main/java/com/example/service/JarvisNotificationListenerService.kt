package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Detailed representation of an intercepted Android notification
 */
data class EnhancedNotification(
    val key: String,
    val id: Int,
    val packageName: String,
    val appName: String,
    val sender: String,
    val title: String,
    val text: String,
    val bigText: String,
    val timestamp: Long,
    val category: String = "GENERAL",
    val isSensitiveOtp: Boolean = false,
    val hasReplyAction: Boolean = false,
    val actionsSummary: List<String> = emptyList(),
    @Transient val sbn: StatusBarNotification? = null
)

/**
 * Settings for Proactive Notification Intelligence
 */
data class NotificationIntelligenceSettings(
    val isEnabled: Boolean = true,
    val isInstantAnnouncementEnabled: Boolean = true,
    val readSenderName: Boolean = true,
    val readAppName: Boolean = true,
    val autoReadFullMessage: Boolean = false,
    val protectSensitiveOtp: Boolean = true,
    val announceWhatsApp: Boolean = true,
    val announceMessenger: Boolean = true,
    val announceFacebook: Boolean = true,
    val announceSms: Boolean = true,
    val announceGmail: Boolean = true,
    val quietHoursEnabled: Boolean = false
)

class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"
        private var instance: JarvisNotificationListenerService? = null

        private val _recentNotifications = MutableStateFlow<List<EnhancedNotification>>(emptyList())
        val recentNotifications: StateFlow<List<EnhancedNotification>> = _recentNotifications.asStateFlow()

        private val _notificationEvents = MutableSharedFlow<EnhancedNotification>(extraBufferCapacity = 64)
        val notificationEvents: SharedFlow<EnhancedNotification> = _notificationEvents.asSharedFlow()

        var settings = NotificationIntelligenceSettings()

        fun isConnected(): Boolean = instance != null

        fun getActiveNotificationsList(): List<EnhancedNotification> = _recentNotifications.value

        fun getLatestNotification(packageFilter: String? = null): EnhancedNotification? {
            val list = _recentNotifications.value
            return if (packageFilter != null) {
                list.firstOrNull { it.packageName.contains(packageFilter, ignoreCase = true) }
            } else {
                list.firstOrNull()
            }
        }

        fun getNotificationsByApp(appNameOrPackage: String): List<EnhancedNotification> {
            val filter = appNameOrPackage.trim().lowercase(Locale.ROOT)
            return _recentNotifications.value.filter {
                it.packageName.lowercase(Locale.ROOT).contains(filter) ||
                it.appName.lowercase(Locale.ROOT).contains(filter)
            }
        }

        fun dismissNotificationByKey(key: String): Boolean {
            val service = instance ?: return false
            return try {
                service.cancelNotification(key)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss notification $key", e)
                false
            }
        }

        fun sendDirectReply(
            context: Context,
            packageFilter: String,
            sender: String? = null,
            replyText: String
        ): Boolean {
            val sbnList = instance?.activeNotifications ?: return false
            for (sbn in sbnList) {
                if (sbn.packageName.contains(packageFilter, ignoreCase = true)) {
                    val notif = sbn.notification ?: continue
                    val extras = notif.extras ?: continue
                    val notifTitle = extras.getString(Notification.EXTRA_TITLE) ?: ""

                    if (sender != null && sender.isNotBlank() && !notifTitle.contains(sender, ignoreCase = true)) {
                        continue
                    }

                    val actions = notif.actions ?: continue
                    for (action in actions) {
                        val remoteInputs = action.remoteInputs ?: continue
                        for (input in remoteInputs) {
                            val intent = Intent()
                            val bundle = Bundle().apply {
                                putCharSequence(input.resultKey, replyText)
                            }
                            RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
                            try {
                                action.actionIntent.send(context, 0, intent)
                                Log.i(TAG, "Direct reply sent successfully to ${sbn.packageName}")
                                return true
                            } catch (e: Exception) {
                                Log.e(TAG, "Direct reply failed", e)
                                return false
                            }
                        }
                    }
                }
            }
            return false
        }

        /**
         * Contextual Bengali Smart Reply Suggestions
         */
        fun suggestSmartReplies(messageText: String): List<String> {
            val lower = messageText.lowercase(Locale.ROOT)
            return when {
                lower.contains("আসবেন") || lower.contains("আসব") || lower.contains("আসছ") || lower.contains("coming") -> {
                    listOf("জি, ইনশাআল্লাহ আসব।", "হ্যাঁ, আমি কিছুক্ষণের মধ্যেই পৌঁছাচ্ছি।", "এখনো নিশ্চিত নই, একটু পরে জানাচ্ছি।")
                }
                lower.contains("কেমন আছেন") || lower.contains("how are you") -> {
                    listOf("আলহামদুলিল্লাহ, ভালো আছি। আপনি কেমন আছেন?", "জি বেশ ভালো। আপনার খবর কী?", "ভালো আছি, ধন্যবাদ।")
                }
                lower.contains("কোথায়") || lower.contains("where") -> {
                    listOf("আমি এখন একটু ব্যস্ত আছি, পরে কথা বলছি।", "আমি পথেই আছি।", "অফিসে আছি।")
                }
                lower.contains("টাকা") || lower.contains("payment") || lower.contains("বকেয়া") -> {
                    listOf("জি, আমি হিসাবটি দেখছি।", "আজকেই পরিশোধ করে দেব ইনশাআল্লাহ।", "পেমেন্টের রিসিট চেক করে কনফার্ম করছি।")
                }
                lower.contains("জরুরি") || lower.contains("urgent") -> {
                    listOf("জি বলুন, আমি শুনছি।", "আমি কল দিচ্ছি একটু অপেক্ষা করুন।", "ঠিক আছে, অবিলম্বে দেখছি।")
                }
                else -> {
                    listOf("জি, ঠিক আছে।", "ধন্যবাদ, আমি পরে জানাচ্ছি।", "ইনশাআল্লাহ।")
                }
            }
        }

        fun sendMediaAction(context: Context, action: String, param: Long? = null): Pair<Boolean, String?> {
            return try {
                val service = instance
                val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                val component = if (service != null) ComponentName(service, JarvisNotificationListenerService::class.java) else null

                val controllers: List<MediaController> = try {
                    if (sessionManager != null && component != null) {
                        sessionManager.getActiveSessions(component)
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                if (controllers.isEmpty()) {
                    return Pair(false, "কোনো সক্রিয় মিডিয়া সেশন পাওয়া যায়নি।")
                }

                val activeController = controllers.firstOrNull {
                    it.packageName.contains("youtube", ignoreCase = true)
                } ?: controllers.first()

                val controls = activeController.transportControls
                val metadata = activeController.metadata
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

                when (action.uppercase(Locale.ROOT)) {
                    "PLAY", "RESUME" -> controls.play()
                    "PAUSE" -> controls.pause()
                    "STOP" -> controls.stop()
                    "NEXT", "SKIP_NEXT" -> controls.skipToNext()
                    "PREVIOUS", "PREV" -> controls.skipToPrevious()
                    "SEEK" -> if (param != null) controls.seekTo(param)
                }
                Pair(true, title)
            } catch (e: Exception) {
                Pair(false, e.localizedMessage)
            }
        }

        fun getNowPlayingTitle(context: Context): String? {
            return try {
                val service = instance ?: return null
                val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
                val component = ComponentName(service, JarvisNotificationListenerService::class.java)
                val controllers = sessionManager.getActiveSessions(component)
                controllers.firstOrNull()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            } catch (e: Exception) {
                null
            }
        }

        private fun isSensitiveOtpContent(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase(Locale.ROOT)
            val otpKeywords = listOf("otp", "one time password", "verification code", "ভেরিফিকেশন", "কোড", "pin", "বিকাশ পিন", "নগদ পিন", "bank otp", "security code", "auth code")
            return otpKeywords.any { combined.contains(it) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Jarvis Notification Listener connected.")
        cacheActiveNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "Jarvis Notification Listener disconnected.")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val enhanced = extractEnhancedNotification(sbn)
        if (enhanced != null) {
            _notificationEvents.tryEmit(enhanced)
            com.example.data.repository.JarvisNotificationRepository.addNotification(enhanced, applicationContext)
        }
        cacheActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        cacheActiveNotifications()
    }

    private fun extractEnhancedNotification(sbn: StatusBarNotification): EnhancedNotification? {
        return try {
            val notif = sbn.notification ?: return null
            val extras = notif.extras ?: return null
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

            val pm = applicationContext.packageManager
            val appName = try {
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                sbn.packageName.substringAfterLast('.')
            }

            // Detect if reply action exists
            var hasReply = false
            val actionTitles = mutableListOf<String>()
            notif.actions?.forEach { act ->
                actionTitles.add(act.title?.toString() ?: "Action")
                if (act.remoteInputs != null && act.remoteInputs.isNotEmpty()) {
                    hasReply = true
                }
            }

            val category = notif.category ?: Notification.CATEGORY_MESSAGE
            val isOtp = isSensitiveOtpContent(title, text)

            EnhancedNotification(
                key = sbn.key,
                id = sbn.id,
                packageName = sbn.packageName,
                appName = appName,
                sender = title.ifBlank { appName },
                title = title,
                text = text,
                bigText = bigText,
                timestamp = sbn.postTime,
                category = category,
                isSensitiveOtp = isOtp,
                hasReplyAction = hasReply,
                actionsSummary = actionTitles,
                sbn = sbn
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting notification: ${e.message}")
            null
        }
    }

    private fun cacheActiveNotifications() {
        try {
            val list = activeNotifications?.mapNotNull { sbn ->
                extractEnhancedNotification(sbn)
            } ?: emptyList()
            _recentNotifications.value = list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching active notifications", e)
        }
    }
}
