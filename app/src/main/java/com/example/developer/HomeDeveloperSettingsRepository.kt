package com.example.developer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeDeveloperSettingsState(
    val selfDevelopmentEnabled: Boolean = false,
    val autonomyLevel: AutonomyLevel = AutonomyLevel.L2_SAFE_AUTONOMOUS,
    val developmentPriority: String = "BUG_FIX_AND_SECURITY", // BUG_FIX_AND_SECURITY, SKILL_EXPANSION, SELF_REPAIR, RESEARCH_AND_DOCS
    val allowYoutubeLearning: Boolean = true,
    val allowGithubLearning: Boolean = true,
    val allowTextLearning: Boolean = true,
    val allowVoiceLearning: Boolean = true,
    val allowLiveDemoLearning: Boolean = true,
    val allowScreenshotLearning: Boolean = true,
    val allowDocumentLearning: Boolean = true,
    val wifiOnly: Boolean = true,
    val batteryNotLow: Boolean = true,
    val requireCharging: Boolean = false,
    val requireDeviceIdle: Boolean = false,
    val requireStorageNotLow: Boolean = true,
    val scheduledHour: Int = -1 // -1 means on-demand/periodic without fixed hour
)

class HomeDeveloperSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("nahid_home_dev_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<HomeDeveloperSettingsState> = _settings.asStateFlow()

    private fun load(): HomeDeveloperSettingsState {
        return HomeDeveloperSettingsState(
            selfDevelopmentEnabled = prefs.getBoolean("self_dev_enabled", false),
            autonomyLevel = AutonomyLevel.fromString(prefs.getString("autonomy_level", AutonomyLevel.L2_SAFE_AUTONOMOUS.name)),
            developmentPriority = prefs.getString("dev_priority", "BUG_FIX_AND_SECURITY") ?: "BUG_FIX_AND_SECURITY",
            allowYoutubeLearning = prefs.getBoolean("src_youtube", true),
            allowGithubLearning = prefs.getBoolean("src_github", true),
            allowTextLearning = prefs.getBoolean("src_text", true),
            allowVoiceLearning = prefs.getBoolean("src_voice", true),
            allowLiveDemoLearning = prefs.getBoolean("src_live_demo", true),
            allowScreenshotLearning = prefs.getBoolean("src_screenshot", true),
            allowDocumentLearning = prefs.getBoolean("src_document", true),
            wifiOnly = prefs.getBoolean("res_wifi_only", true),
            batteryNotLow = prefs.getBoolean("res_battery_not_low", true),
            requireCharging = prefs.getBoolean("res_charging", false),
            requireDeviceIdle = prefs.getBoolean("res_idle", false),
            requireStorageNotLow = prefs.getBoolean("res_storage", true),
            scheduledHour = prefs.getInt("scheduled_hour", -1)
        )
    }

    fun update(transform: (HomeDeveloperSettingsState) -> HomeDeveloperSettingsState): HomeDeveloperSettingsState {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean("self_dev_enabled", next.selfDevelopmentEnabled)
            .putString("autonomy_level", next.autonomyLevel.name)
            .putString("dev_priority", next.developmentPriority)
            .putBoolean("src_youtube", next.allowYoutubeLearning)
            .putBoolean("src_github", next.allowGithubLearning)
            .putBoolean("src_text", next.allowTextLearning)
            .putBoolean("src_voice", next.allowVoiceLearning)
            .putBoolean("src_live_demo", next.allowLiveDemoLearning)
            .putBoolean("src_screenshot", next.allowScreenshotLearning)
            .putBoolean("src_document", next.allowDocumentLearning)
            .putBoolean("res_wifi_only", next.wifiOnly)
            .putBoolean("res_battery_not_low", next.batteryNotLow)
            .putBoolean("res_charging", next.requireCharging)
            .putBoolean("res_idle", next.requireDeviceIdle)
            .putBoolean("res_storage", next.requireStorageNotLow)
            .putInt("scheduled_hour", next.scheduledHour)
            .apply()
        _settings.value = next
        return next
    }

    fun setSelfDevelopmentEnabled(enabled: Boolean): HomeDeveloperSettingsState {
        return update { it.copy(selfDevelopmentEnabled = enabled) }
    }

    fun setAutonomyLevel(level: AutonomyLevel): HomeDeveloperSettingsState {
        return update { it.copy(autonomyLevel = level) }
    }

    fun isSourceAllowed(sourceType: String): Boolean {
        val s = _settings.value
        return when (sourceType.uppercase()) {
            "YOUTUBE", "YOUTUBE_TUTORIAL" -> s.allowYoutubeLearning
            "GITHUB", "GITHUB_IMPORT" -> s.allowGithubLearning
            "TEXT", "TEXT_TEACH" -> s.allowTextLearning
            "VOICE", "VOICE_TEACHING" -> s.allowVoiceLearning
            "LIVE_DEMO", "DEMONSTRATION" -> s.allowLiveDemoLearning
            "SCREENSHOT", "IMAGE" -> s.allowScreenshotLearning
            "DOCUMENT", "DOCS" -> s.allowDocumentLearning
            else -> true
        }
    }
}
