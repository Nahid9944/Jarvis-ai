package com.example.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.example.util.JarvisSmsManager
import com.example.util.SmsDeliveryStage

/**
 * Receives real Android SmsManager PendingIntent callbacks for SMS_SENT and SMS_DELIVERED
 * so JARVIS never reports false SMS delivery success before carrier confirmation.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsStatusReceiver"
        const val ACTION_SMS_SENT = "com.example.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.example.action.SMS_DELIVERED"
        const val EXTRA_REQUEST_ID = "EXTRA_ID"
        const val EXTRA_RECIPIENT = "EXTRA_RECIPIENT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT).orEmpty()

        when (action) {
            ACTION_SMS_SENT -> {
                val rc = resultCode
                if (rc == Activity.RESULT_OK) {
                    Log.i(TAG, "SMS sent confirmed for requestId=$requestId")
                    JarvisSmsManager.updateDeliveryStatus(
                        requestId = requestId,
                        recipient = recipient,
                        stage = SmsDeliveryStage.SEND_CONFIRMED,
                        errorCode = rc,
                        summaryBengali = if (recipient.isNotBlank()) {
                            "$recipient নম্বরে এসএমএস সফলভাবে পাঠানো হয়েছে (ক্যারিয়ার কনফার্মড)।"
                        } else {
                            "এসএমএস সফলভাবে পাঠানো হয়েছে (ক্যারিয়ার কনফার্মড)।"
                        }
                    )
                } else {
                    val errorReason = mapSmsErrorCode(rc)
                    Log.w(TAG, "SMS send failed: requestId=$requestId rc=$rc reason=$errorReason")
                    JarvisSmsManager.updateDeliveryStatus(
                        requestId = requestId,
                        recipient = recipient,
                        stage = SmsDeliveryStage.SEND_FAILED,
                        errorCode = rc,
                        summaryBengali = "এসএমএস পাঠানো ব্যর্থ হয়েছে: $errorReason"
                    )
                }
            }

            ACTION_SMS_DELIVERED -> {
                val rc = resultCode
                if (rc == Activity.RESULT_OK) {
                    Log.i(TAG, "SMS delivered confirmed for requestId=$requestId")
                    JarvisSmsManager.updateDeliveryStatus(
                        requestId = requestId,
                        recipient = recipient,
                        stage = SmsDeliveryStage.DELIVERY_CONFIRMED,
                        errorCode = rc,
                        summaryBengali = if (recipient.isNotBlank()) {
                            "$recipient নম্বরে এসএমএস ডেলিভারি নিশ্চিত হয়েছে।"
                        } else {
                            "প্রাপকের ফোনে এসএমএস ডেলিভারি নিশ্চিত হয়েছে।"
                        }
                    )
                } else {
                    Log.w(TAG, "SMS delivery failed/unconfirmed: requestId=$requestId rc=$rc")
                    JarvisSmsManager.updateDeliveryStatus(
                        requestId = requestId,
                        recipient = recipient,
                        stage = SmsDeliveryStage.DELIVERY_FAILED,
                        errorCode = rc,
                        summaryBengali = "এসএমএস ডেলিভারি নিশ্চিত হয়নি (কোড: $rc)।"
                    )
                }
            }
        }
    }

    private fun mapSmsErrorCode(resultCode: Int): String {
        return when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "সাধারণ নেটওয়ার্ক বা ক্যারিয়ার ত্রুটি"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "ফোনের মোবাইল নেটওয়ার্ক/রেডিও বন্ধ (Airplane Mode)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "এসএমএস ডেটা ফরম্যাট ত্রুটি (Null PDU)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "সিম নেটওয়ার্ক সার্ভিস পাওয়া যাচ্ছে না"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "দৈনিক এসএমএস সীমা অতিক্রম করেছে"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "শর্ট কোডে এসএমএস অনুমতি নেই"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "শর্ট কোড ব্লক করা আছে"
            else -> "ত্রুটি কোড $resultCode"
        }
    }
}
