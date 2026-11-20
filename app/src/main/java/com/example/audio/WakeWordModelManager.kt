package com.example.audio

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeWordCapabilityReport(
    val isSpeechRecognitionAvailable: Boolean,
    val isOnDeviceRecognitionAvailable: Boolean,
    val isBundledNeuralKeywordModelPresent: Boolean,
    val engineModeLabel: String,
    val detailBengali: String
)

/**
 * Manages wake-word configuration, custom trigger phrases, opt-in state (OFF by default),
 * and truthful reporting of on-device vs SpeechRecognizer capabilities.
 */
object WakeWordModelManager {

    private const val PREFS_NAME = "jarvis_wakeword_prefs"
    private const val KEY_OPT_IN = "wake_word_opt_in"
    private const val KEY_CUSTOM_PHRASES = "wake_word_custom_phrases"
    private const val KEY_ENERGY_THRESHOLD = "wake_word_vad_rms_threshold"
    private const val KEY_COOLDOWN_MS = "wake_word_cooldown_ms"

    val DEFAULT_WAKE_PHRASES = listOf(
        "জারভিস",
        "jarvis",
        "এই জারভিস",
        "শোনো জারভিস",
        "hey jarvis",
        "হ্যালো জারভিস"
    )

    private val _optInEnabled = MutableStateFlow(false)
    val optInEnabled: StateFlow<Boolean> = _optInEnabled.asStateFlow()

    private val _configuredPhrases = MutableStateFlow(DEFAULT_WAKE_PHRASES)
    val configuredPhrases: StateFlow<List<String>> = _configuredPhrases.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _optInEnabled.value = prefs.getBoolean(KEY_OPT_IN, false)
        val raw = prefs.getString(KEY_CUSTOM_PHRASES, null)
        _configuredPhrases.value = parsePhraseList(raw)
    }

    fun isOptInEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_OPT_IN, false)
        _optInEnabled.value = enabled
        return enabled
    }

    fun setOptInEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OPT_IN, enabled).apply()
        _optInEnabled.value = enabled
    }

    fun getCustomWakePhrases(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = parsePhraseList(prefs.getString(KEY_CUSTOM_PHRASES, null))
        _configuredPhrases.value = list
        return list
    }

    fun saveCustomWakePhrases(context: Context, rawCommaOrLineSeparated: String): List<String> {
        val custom = rawCommaOrLineSeparated
            .split(",", "\n", "|")
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        val combined = (DEFAULT_WAKE_PHRASES + custom).distinct()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_PHRASES, custom.joinToString("|")).apply()
        _configuredPhrases.value = combined
        return combined
    }

    fun getEnergyThreshold(context: Context): Float {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_ENERGY_THRESHOLD, 280f).coerceIn(80f, 3000f)
    }

    fun setEnergyThreshold(context: Context, rmsThreshold: Float) {
        val clamped = rmsThreshold.coerceIn(80f, 3000f)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_ENERGY_THRESHOLD, clamped).apply()
    }

    fun getCooldownMs(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_COOLDOWN_MS, 2500L).coerceIn(1000L, 10_000L)
    }

    private fun parsePhraseList(storedCustom: String?): List<String> {
        if (storedCustom.isNullOrBlank()) return DEFAULT_WAKE_PHRASES
        val custom = storedCustom.split("|").map { it.trim() }.filter { it.length >= 2 }
        return (DEFAULT_WAKE_PHRASES + custom).distinct()
    }

    /**
     * Truthful capability audit — never claims a bundled neural .tflite wake model exists
     * unless verified on disk. Reports actual Android SpeechRecognizer + On-Device API status.
     */
    fun inspectCapabilities(context: Context): WakeWordCapabilityReport {
        val recognitionAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
        val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        val hasBundledModel = try {
            val assets = context.assets.list("wakeword").orEmpty()
            assets.any { it.endsWith(".tflite") || it.endsWith(".ppn") || it.endsWith(".onnx") }
        } catch (_: Throwable) {
            false
        }

        val modeLabel = when {
            hasBundledModel -> "LOCAL_NEURAL_KWS"
            onDeviceAvailable -> "ON_DEVICE_SPEECH_RECOGNIZER"
            recognitionAvailable -> "ANDROID_SPEECH_RECOGNIZER_HYBRID"
            else -> "UNAVAILABLE"
        }

        val detailBn = when {
            onDeviceAvailable ->
                "ডিভাইস-ভিত্তিক অফলাইন স্পিচ রিকগনিশন ও 16kHz PCM VAD সাপোর্টেড আছে (আলাদা .tflite ওয়েক-ওয়ার্ড বাইনারি ছাড়া Android OnDevice SpeechRecognizer ব্যবহৃত হয়)।"
            recognitionAvailable ->
                "অ্যান্ড্রয়েড SpeechRecognizer (prefer-offline মোডসহ) উপলব্ধ, তবে ডেডিকেটেড লো-পাওয়ার .tflite ওয়েক-ওয়ার্ড বাইনারি বান্ডেল নেই; ডিভাইস ভেদে অফলাইন সাপোর্ট ভিন্ন হতে পারে।"
            else ->
                "এই ডিভাইসে SpeechRecognizer সার্ভিস অনুপলব্ধ থাকায় ভয়েস ওয়েক-ওয়ার্ড কাজ করবে না।"
        }

        return WakeWordCapabilityReport(
            isSpeechRecognitionAvailable = recognitionAvailable,
            isOnDeviceRecognitionAvailable = onDeviceAvailable,
            isBundledNeuralKeywordModelPresent = hasBundledModel,
            engineModeLabel = modeLabel,
            detailBengali = detailBn
        )
    }
}
