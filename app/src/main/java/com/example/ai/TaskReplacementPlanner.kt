package com.example.ai

import java.util.Locale

/**
 * Plans task replacements when a user interrupts an ongoing task with a substituted intent.
 * (e.g. User said "Call Rahim", then interrupted: "No no, don't call, send him a WhatsApp message instead").
 */
object TaskReplacementPlanner {

    fun planReplacement(utterance: String, previousTask: ActiveTaskState?): ActiveTaskState? {
        val lower = utterance.trim().lowercase(Locale.ROOT)

        val previousContact = previousTask?.params?.get("contact") as? String
            ?: previousTask?.params?.get("phoneNumber") as? String
            ?: "কাঙ্ক্ষিত ব্যক্তি"

        return when {
            lower.contains("whatsapp") || lower.contains("মেসেজ") || lower.contains("হোয়াটসঅ্যাপ") -> {
                val extractedMsg = utterance.substringAfter("মেসেজ", "").replace(Regex("দাও|পাঠাও|যে|বলো"), "").trim()
                ActiveTaskState(
                    goalBengali = "$previousContact এর হোয়াটসঅ্যাপে বার্তা প্রেরণ",
                    toolName = "controlWhatsApp",
                    status = TaskStatus.EXECUTING,
                    currentStep = "হোয়াটসঅ্যাপ চ্যাট প্রস্তুতি",
                    pendingSteps = mutableListOf("মেসেজ টাইপ", "প্রেরণ ও যাচাই"),
                    params = mutableMapOf(
                        "contact" to previousContact,
                        "message" to extractedMsg.ifBlank { "জরুরি বার্তা" }
                    )
                )
            }

            lower.contains("ফোন") || lower.contains("কল") || lower.contains("call") -> {
                val newTarget = utterance.substringAfter("কল", "").substringAfter("ফোন", "").trim().ifBlank { previousContact }
                ActiveTaskState(
                    goalBengali = "$newTarget কে টেলিফোন কল",
                    toolName = "makePhoneCall",
                    status = TaskStatus.EXECUTING,
                    currentStep = "কল ডায়াল প্রস্তুতি",
                    pendingSteps = mutableListOf("কল সংযোগ"),
                    params = mutableMapOf("phoneNumber" to newTarget)
                )
            }

            lower.contains("সার্চ") || lower.contains("খুঁজে") || lower.contains("search") -> {
                val query = utterance.substringAfter("সার্চ", "").substringAfter("খুঁজে", "").trim()
                ActiveTaskState(
                    goalBengali = "'$query' বিষয়ে গুগল অনুসন্ধান",
                    toolName = "searchWikipedia",
                    status = TaskStatus.EXECUTING,
                    currentStep = "অনুসন্ধান চলছে",
                    pendingSteps = mutableListOf("তথ্য বিশ্লেষণ ও উপস্থাপন"),
                    params = mutableMapOf("query" to query)
                )
            }

            else -> {
                ActiveTaskState(
                    goalBengali = "নতুন প্রতিস্থাপিত কাজ: $utterance",
                    toolName = "executeGenericReplacement",
                    status = TaskStatus.EXECUTING,
                    currentStep = "নির্দেশ বিশ্লেষণ",
                    params = mutableMapOf("rawCommand" to utterance)
                )
            }
        }
    }
}
