package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import com.example.permissions.JarvisPermissionManager
import com.example.service.SmsStatusReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class SmsDeliveryStage {
    DISPATCH_ACCEPTED,
    COMPOSER_OPENED,
    SEND_CONFIRMED,
    SEND_FAILED,
    DELIVERY_CONFIRMED,
    DELIVERY_FAILED
}

data class SmsDeliveryRecord(
    val requestId: Int,
    val recipient: String,
    val stage: SmsDeliveryStage,
    val errorCode: Int = 0,
    val summaryBengali: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SmsDispatchResult(
    val success: Boolean,
    val status: String,
    val directSend: Boolean,
    val partsCount: Int,
    val recipient: String,
    val messageSnippet: String,
    val summaryBengali: String,
    val error: String? = null,
    val requestId: Int = -1
)

object JarvisSmsManager {

    const val STATUS_SEND_REQUEST_ACCEPTED = "SEND_REQUEST_ACCEPTED"
    const val STATUS_COMPOSER_OPENED = "COMPOSER_OPENED_REQUIRES_USER_SEND"
    const val STATUS_NO_TELEPHONY = "NO_TELEPHONY_HARDWARE"
    const val STATUS_NO_SIM = "NO_SIM_CARD"
    const val STATUS_PERMISSION_DENIED = "SEND_SMS_PERMISSION_DENIED"
    const val STATUS_SEND_FAILED = "SEND_FAILED"

    private val _latestSmsStatus = MutableStateFlow<SmsDeliveryRecord?>(null)
    val latestSmsStatus: StateFlow<SmsDeliveryRecord?> = _latestSmsStatus.asStateFlow()

    private val statusByRequestId = ConcurrentHashMap<Int, SmsDeliveryRecord>()

    fun updateDeliveryStatus(
        requestId: Int,
        recipient: String,
        stage: SmsDeliveryStage,
        errorCode: Int = 0,
        summaryBengali: String
    ) {
        val record = SmsDeliveryRecord(
            requestId = requestId,
            recipient = recipient,
            stage = stage,
            errorCode = errorCode,
            summaryBengali = summaryBengali,
            timestamp = System.currentTimeMillis()
        )
        if (requestId >= 0) {
            statusByRequestId[requestId] = record
        }
        _latestSmsStatus.value = record
    }

    fun getDeliveryStatus(requestId: Int): SmsDeliveryRecord? = statusByRequestId[requestId]

    fun clearStatusHistory() {
        statusByRequestId.clear()
        _latestSmsStatus.value = null
    }

    fun hasTelephonyHardware(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    fun hasReadySim(context: Context): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
        } catch (_: Exception) {
            false
        }
    }

    fun hasSendSmsPermission(context: Context): Boolean {
        return JarvisPermissionManager.hasSendSmsPermission(context)
    }

    fun dispatchSms(
        context: Context,
        recipient: String,
        message: String,
        preferDirectSend: Boolean = true
    ): SmsDispatchResult {
        val cleanNumber = JarvisContactSearchEngine.normalizePhoneNumber(recipient).filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) {
            return SmsDispatchResult(
                success = false,
                status = STATUS_SEND_FAILED,
                directSend = false,
                partsCount = 0,
                recipient = recipient,
                messageSnippet = message.take(50),
                summaryBengali = "সঠিক ফোন নম্বর পাওয়া যায়নি।",
                error = "Recipient number is invalid or empty"
            )
        }

        if (!hasTelephonyHardware(context)) {
            return openSmsComposer(
                context = context,
                recipient = cleanNumber,
                message = message,
                reason = "ডিভাইসে কোনো টেলিফোনি বা সেলুলার হার্ডওয়্যার পাওয়া যায়নি।"
            )
        }

        val hasPermission = hasSendSmsPermission(context)
        val simReady = hasReadySim(context)

        if (preferDirectSend && hasPermission && simReady) {
            return try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                if (smsManager == null) {
                    return openSmsComposer(
                        context = context,
                        recipient = cleanNumber,
                        message = message,
                        reason = "সিস্টেমের SmsManager এক্সেস করা যায়নি।"
                    )
                }

                val requestId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
                val sentIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    requestId,
                    Intent(SmsStatusReceiver.ACTION_SMS_SENT).apply {
                        setPackage(context.packageName)
                        putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, requestId)
                        putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, cleanNumber)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val deliveryIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    requestId,
                    Intent(SmsStatusReceiver.ACTION_SMS_DELIVERED).apply {
                        setPackage(context.packageName)
                        putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, requestId)
                        putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, cleanNumber)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    val sentIntents = java.util.ArrayList<android.app.PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
                    val deliveryIntents = java.util.ArrayList<android.app.PendingIntent>().apply { repeat(parts.size) { add(deliveryIntent) } }
                    smsManager.sendMultipartTextMessage(cleanNumber, null, parts, sentIntents, deliveryIntents)
                } else {
                    smsManager.sendTextMessage(cleanNumber, null, message, sentIntent, deliveryIntent)
                }

                val summary = "$cleanNumber নম্বরে সরাসরি সিমের মাধ্যমে এসএমএস প্রেরণ অনুরোধ গৃহীত হয়েছে (${parts.size} টি অংশে)। ক্যারিয়ার ডেলিভারি নিশ্চিতকরণের অপেক্ষায় রয়েছে।"
                updateDeliveryStatus(
                    requestId = requestId,
                    recipient = cleanNumber,
                    stage = SmsDeliveryStage.DISPATCH_ACCEPTED,
                    summaryBengali = summary
                )

                SmsDispatchResult(
                    success = true,
                    status = STATUS_SEND_REQUEST_ACCEPTED,
                    directSend = true,
                    partsCount = parts.size,
                    recipient = cleanNumber,
                    messageSnippet = message.take(50),
                    summaryBengali = summary,
                    error = null,
                    requestId = requestId
                )
            } catch (e: Exception) {
                openSmsComposer(
                    context = context,
                    recipient = cleanNumber,
                    message = message,
                    reason = "সরাসরি এসএমএস প্রেরণে ত্রুটি (${e.message})।"
                )
            }
        } else {
            val reasonBengali = when {
                !hasPermission -> "সরাসরি এসএমএস পাঠানোর অনুমতি (SEND_SMS) দেওয়া নেই।"
                !simReady -> "সিম কার্ড রেডি পাওয়া যায়নি।"
                else -> "ইউজার কম্পোজার রিকোয়েস্ট।"
            }
            return openSmsComposer(
                context = context,
                recipient = cleanNumber,
                message = message,
                reason = reasonBengali
            )
        }
    }

    private fun openSmsComposer(
        context: Context,
        recipient: String,
        message: String,
        reason: String
    ): SmsDispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$recipient")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return SmsDispatchResult(
                    success = false,
                    status = STATUS_SEND_FAILED,
                    directSend = false,
                    partsCount = 0,
                    recipient = recipient,
                    messageSnippet = message.take(50),
                    summaryBengali = "$reason এবং ডিভাইসে কোনো এসএমএস অ্যাপও পাওয়া যায়নি।",
                    error = "No SMS app available to handle ACTION_SENDTO"
                )
            }
            context.startActivity(intent)

            val summary = "$reason তাই $recipient নম্বরে এসএমএস প্রেরক স্ক্রিন খোলা হয়েছে। পাঠানোর জন্য 'Send' চাপুন।"
            updateDeliveryStatus(
                requestId = -1,
                recipient = recipient,
                stage = SmsDeliveryStage.COMPOSER_OPENED,
                summaryBengali = summary
            )

            SmsDispatchResult(
                success = true,
                status = STATUS_COMPOSER_OPENED,
                directSend = false,
                partsCount = 1,
                recipient = recipient,
                messageSnippet = message.take(50),
                summaryBengali = summary,
                error = null
            )
        } catch (e: Exception) {
            SmsDispatchResult(
                success = false,
                status = STATUS_SEND_FAILED,
                directSend = false,
                partsCount = 0,
                recipient = recipient,
                messageSnippet = message.take(50),
                summaryBengali = "এসএমএস প্রেরক স্ক্রিনও খোলা যায়নি: ${e.message}",
                error = e.message
            )
        }
    }
}
