package com.example.service

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class AssistantRoleStatus(
    val isRoleApiSupported: Boolean,
    val isRoleAvailable: Boolean,
    val isDefaultAssistant: Boolean,
    val statusBengali: String
)

/**
 * Manages Android Default Assistant registration (RoleManager.ROLE_ASSISTANT on Android 10+ Q / API 29+)
 * and fallback Voice Input Settings intent.
 */
object JarvisAssistantRoleManager {

    fun getStatus(context: Context): AssistantRoleStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AssistantRoleStatus(
                isRoleApiSupported = false,
                isRoleAvailable = false,
                isDefaultAssistant = false,
                statusBengali = "Android 10 (API 29) বা তার উপরের সংস্করণে RoleManager.ROLE_ASSISTANT সমর্থিত।"
            )
        }

        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager == null) {
                AssistantRoleStatus(
                    isRoleApiSupported = true,
                    isRoleAvailable = false,
                    isDefaultAssistant = false,
                    statusBengali = "এই ডিভাইসে RoleManager পাওয়া যায়নি।"
                )
            } else {
                val available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                val held = available && roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                val msg = when {
                    held -> "সক্রিয় — NAHID AI এখন ডিফল্ট সিস্টেম অ্যাসিস্ট্যান্ট।"
                    available -> "ডিফল্ট অ্যাসিস্ট্যান্ট হিসেবে সেট করার জন্য প্রস্তুত (বর্তমানে অন্য অ্যাপ সেট করা আছে)।"
                    else -> "এই ডিভাইসের OEM/প্রোফাইলে Assistant Role অনুমোদিত নয়।"
                }
                AssistantRoleStatus(
                    isRoleApiSupported = true,
                    isRoleAvailable = available,
                    isDefaultAssistant = held,
                    statusBengali = msg
                )
            }
        } catch (e: Exception) {
            AssistantRoleStatus(
                isRoleApiSupported = true,
                isRoleAvailable = false,
                isDefaultAssistant = false,
                statusBengali = "অ্যাসিস্ট্যান্ট রোল যাচাইয়ে ত্রুটি: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Builds the system RoleManager consent Intent if available, or opens Voice Input Settings as fallback.
     */
    fun createSelectAssistantIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                }
            } catch (_: Exception) {
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
