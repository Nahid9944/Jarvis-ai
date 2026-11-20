package com.example.tools

import org.json.JSONObject

/**
 * Result verification engine for all 135 tools in Jarvis OS.
 * Validates post-conditions, data integrity, mutation success, navigation outcome,
 * and system state changes, annotating the output with deterministic verification metadata.
 */
object ToolResultVerifier {

    fun verifyAndEnrich(
        toolName: String,
        spec: ToolSpec?,
        args: JSONObject,
        rawResult: JSONObject
    ): JSONObject {
        val status = rawResult.optString("status", "")
        if (status in listOf(
                "PERMISSION_REQUIRED",
                "CONFIRMATION_REQUIRED",
                "CANCELLED_BEFORE_DISPATCH",
                "CANCELLED",
                "PAUSED",
                "MULTIPLE_MATCHES",
                "TRANSCRIPT_REQUIRED",
                "PARAMETERS_REQUIRED",
                "OFFLINE_LIMITED",
                "UNSUPPORTED",
                "AWAITING_PROJECTION_CONSENT",
                "LOCATION_UNRESOLVED"
            )
        ) {
            rawResult.put("verified", false)
            rawResult.put("verificationStatus", status)
            rawResult.put("actionStatus", rawResult.optString("actionStatus", status))
            if (status == "CANCELLED_BEFORE_DISPATCH" || status == "UNSUPPORTED" || status == "OFFLINE_LIMITED") {
                rawResult.put("success", false)
                if (!rawResult.has("errorCode")) rawResult.put("errorCode", status)
            }
            return rawResult
        }

        // Check for explicit error flags or LOCATION_NOT_FOUND
        val isExplicitError = rawResult.optBoolean("error", false) ||
                status == "FAILED" ||
                status == "ERROR" ||
                status == "NOT_IMPLEMENTED" ||
                rawResult.optString("errorCode") == "LOCATION_NOT_FOUND" ||
                rawResult.has("exception") ||
                (rawResult.has("error") && rawResult.opt("error") !is Boolean && rawResult.optString("error").isNotBlank())

        if (isExplicitError) {
            rawResult.put("success", false)
            rawResult.put("verified", false)
            rawResult.put("status", "FAILED")
            rawResult.put("actionStatus", rawResult.optString("actionStatus", "FAILED"))
            if (!rawResult.has("errorCode")) rawResult.put("errorCode", "EXECUTION_FAILED")
            rawResult.put("verificationStatus", "VERIFICATION_FAILED")
            rawResult.put("verificationDetails", rawResult.optString("summaryBengali", rawResult.optString("message", "টুল এক্সিকিউশনে ত্রুটি পাওয়া গেছে।")))
            return rawResult
        }

        val category = spec?.category ?: ToolCategory.SYSTEM_ACTIONS
        val isMutating = spec?.isMutating ?: false

        val (isVerified, details) = when {
            // 1. Destructive & Deletion Tools
            isDeletionTool(toolName) -> {
                val success = rawResult.optBoolean("success", false) &&
                        (rawResult.optBoolean("deleted", false) || rawResult.optString("status") == "SUCCESS" || !rawResult.has("deleted"))
                if (success) {
                    Pair(true, "রেকর্ডটি সফলভাবে এবং স্থায়ীভাবে মুছে ফেলা হয়েছে ও ডাটাবেজ হালনাগাদ নিশ্চিত।")
                } else {
                    Pair(false, rawResult.optString("summaryBengali", "মুছে ফেলার কার্যকারিতা নিশ্চিত করা যায়নি।"))
                }
            }

            // 2. Skills & Tutorial/Repo/Text Learning Imports
            toolName in listOf("executeLearnedSkill", "importSkillFromYouTube", "importSkillFromGitHub", "learnFromText") -> {
                val hasExplicitSuccess = rawResult.optBoolean("success", false)
                val statusStr = rawResult.optString("status", "")
                when {
                    statusStr == "PARTIAL_SUCCESS" -> {
                        Pair(false, rawResult.optString("summaryBengali", "আংশিক ধাপ সম্পন্ন হয়েছে; সম্পূর্ণ কাজ যাচাই করা যায়নি।"))
                    }
                    hasExplicitSuccess -> {
                        Pair(true, rawResult.optString("summaryBengali", "স্কিল/ইমপোর্ট কাজটি সফলভাবে সম্পন্ন ও যাচাইকৃত।"))
                    }
                    else -> {
                        Pair(false, rawResult.optString("summaryBengali", "স্কিল/ইমপোর্ট কাজটি সম্পন্ন হয়নি।"))
                    }
                }
            }

            // 3. GPS & Telemetry
            toolName == "getCurrentGpsLocation" -> {
                val hasSuccess = rawResult.optBoolean("success", false)
                val lat = rawResult.optDouble("latitude", 0.0)
                val lng = rawResult.optDouble("longitude", 0.0)
                val validCoords = lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
                if (hasSuccess && validCoords) {
                    Pair(true, "জিপিএস অবস্থান সফলভাবে নিশ্চিত ও কোঅর্ডিনেট সংগৃহীত ($lat, $lng)।")
                } else {
                    rawResult.put("success", false)
                    Pair(false, rawResult.optString("summaryBengali", "জিপিএস সিগন্যাল ও বৈধ কোঅর্ডিনেট নিশ্চিত করা যায়নি (UNVERIFIED)।"))
                }
            }

            // 4. Communication & External Actions
            toolName in listOf(
                "makePhoneCall",
                "sendWhatsAppMessage",
                "sendSmsMessage",
                "controlWhatsApp",
                "startWhatsAppVoiceCall",
                "startWhatsAppVideoCall",
                "openWhatsAppConversation",
                "endWhatsAppCall",
                "replyFromNotification",
                "sendNotificationReply"
            ) -> {
                val actionStatus = rawResult.optString("actionStatus", rawResult.optString("status", ""))
                val hasSuccess = rawResult.optBoolean("success", false)

                when {
                    actionStatus in listOf("SEND_CONFIRMED", "DELIVERY_CONFIRMED", "CALL_CONNECTED", "CALL_ACTIVE", "VERIFIED_ENDED") -> {
                        Pair(true, "যোগাযোগ সম্পন্ন ও ডেলিভারি/কল সক্রিয় অবস্থা নিশ্চিত করা হয়েছে।")
                    }
                    actionStatus in listOf(
                        "DIRECT_SMS_DISPATCHED",
                        "SEND_REQUEST_ACCEPTED",
                        "WHATSAPP_CALL_DISPATCHED",
                        "WHATSAPP_CHAT_OPENED_CALL_REQUESTED",
                        "CALL_DISPATCHED",
                        "CALL_INITIATED",
                        "COMPOSER_OPENED_READY_TO_SEND",
                        "DIALER_OPENED_REQUIRES_MANUAL_CALL",
                        "REQUEST_ACCEPTED"
                    ) -> {
                        rawResult.put("status", "REQUEST_ACCEPTED")
                        Pair(false, "যোগাযোগ অনুরোধ পাঠানো হয়েছে ($actionStatus); চূড়ান্ত ডেলিভারি/সংযোগ এখনো যাচাইকৃত নয়।")
                    }
                    hasSuccess && rawResult.optBoolean("verified", false) -> {
                        Pair(true, "যোগাযোগ অ্যাকশন সফলভাবে সম্পন্ন ও যাচাইকৃত।")
                    }
                    hasSuccess -> {
                        rawResult.put("status", "REQUEST_ACCEPTED")
                        Pair(false, "অ্যাকশনটি সিস্টেমে পাঠানো হয়েছে, কিন্তু ডেলিভারি/কল ফলাফল যাচাই করা যায়নি।")
                    }
                    else -> {
                        Pair(false, rawResult.optString("summaryBengali", "যোগাযোগ সংযোগ ব্যর্থ হয়েছে বা নিশ্চিত ফলাফল অপ্রাপ্ত (FAILED)।"))
                    }
                }
            }

            // 5. Mutations (Sales, Payments, Inventory Add/Update, Notes, Tasks)
            isMutating -> {
                val hasExplicitSuccess = rawResult.optBoolean("success", false)
                val statusOk = rawResult.optString("status") in listOf("SUCCESS", "COMPLETED", "SAVED")
                val success = (hasExplicitSuccess || statusOk) && !rawResult.has("error")
                if (success) {
                    Pair(true, "ডাটাবেজ পরিবর্তন ও সংশ্লিষ্ট রেকর্ড সফলভাবে সংরক্ষিত ও নিশ্চিত করা হয়েছে।")
                } else {
                    Pair(false, rawResult.optString("summaryBengali", "ডাটাবেজ পরিবর্তন নিশ্চিত হতে ব্যর্থ হয়েছে।"))
                }
            }

            // 6. Navigation Tools
            isNavigationTool(toolName) -> {
                val navigated = rawResult.optBoolean("navigated", false) || rawResult.optBoolean("success", false)
                if (navigated) {
                    Pair(true, "অ্যাপ স্ক্রিন নেভিগেশন ইন্টারফেসে সফলভাবে কার্যকর হয়েছে।")
                } else {
                    Pair(false, rawResult.optString("summaryBengali", "স্ক্রিন নেভিগেশন নিশ্চিত করা সম্ভব হয়নি (UNVERIFIED)।"))
                }
            }

            // 7. Automation & Screen Perception
            category == ToolCategory.APP_AUTOMATION || category == ToolCategory.DEVICE_CONTROL || category == ToolCategory.SCREEN_CAPTURE -> {
                val hasSuccess = rawResult.optBoolean("success", false)
                val verifiedField = rawResult.optBoolean("verified", false)
                val success = (hasSuccess || verifiedField) && !rawResult.has("error")
                if (success) {
                    Pair(true, "সিস্টেম বা অ্যাপ অটোমেশন অ্যাকশন সফলভাবে সম্পন্ন ও যাচাইকৃত।")
                } else {
                    Pair(false, rawResult.optString("summaryBengali", "অটোমেশন অ্যাকশন সফলভাবে নিশ্চিত হতে পারেনি (UNVERIFIED)।"))
                }
            }

            // 8. Read-Only Queries & Reports (Strict check: never treat found=false or success=false as VERIFIED!)
            else -> {
                val explicitFail = rawResult.has("success") && !rawResult.optBoolean("success", true)
                val explicitlyNotFound = rawResult.has("found") && !rawResult.optBoolean("found", true)
                val hasOfflineTemp = rawResult.has("isLiveData") && !rawResult.optBoolean("isLiveData", true)
                val validData = !explicitFail && !explicitlyNotFound && !hasOfflineTemp &&
                        (rawResult.optBoolean("success", false) ||
                                rawResult.optBoolean("found", false) ||
                                rawResult.has("count") ||
                                rawResult.has("batteryPercent") ||
                                (rawResult.has("temperature") && rawResult.optBoolean("isLiveData", true)) ||
                                rawResult.has("durationSeconds") ||
                                rawResult.has("connected") ||
                                rawResult.has("appPackage") ||
                                rawResult.has("data") ||
                                rawResult.has("progressPercent") ||
                                rawResult.has("projectHealthScore"))
                if (validData) {
                    Pair(true, "অনুরোধকৃত তথ্য সফলভাবে সংগৃহীত ও আউটপুট যাচাই করা হয়েছে।")
                } else {
                    Pair(false, rawResult.optString("summaryBengali", "কোনো বৈধ তথ্য পাওয়া যায়নি বা কুয়েরি ব্যর্থ হয়েছে (UNVERIFIED)।"))
                }
            }
        }

        val existingStatus = rawResult.optString("status", "")
        val finalStatus = when {
            isVerified -> "SUCCESS"
            existingStatus in listOf("PARTIAL_SUCCESS", "REQUEST_ACCEPTED") -> existingStatus
            rawResult.has("error") -> "FAILED"
            rawResult.has("success") && !rawResult.optBoolean("success", true) -> "FAILED"
            else -> "UNVERIFIED"
        }

        rawResult.put("verified", isVerified)
        rawResult.put("verificationStatus", if (isVerified) "VERIFIED" else "VERIFICATION_FAILED")
        rawResult.put("status", finalStatus)
        rawResult.put("verificationDetails", details)
        return rawResult
    }

    private fun isDeletionTool(name: String): Boolean {
        return name.startsWith("delete")
    }

    private fun isNavigationTool(name: String): Boolean {
        return name.startsWith("open") || name == "navigateApp" || name == "goBack" || name == "closeCurrentScreen"
    }
}
