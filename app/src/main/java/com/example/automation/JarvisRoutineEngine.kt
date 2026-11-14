package com.example.automation

import android.content.Context
import android.os.BatteryManager
import com.example.ai.WeatherService
import com.example.data.local.AssistantDao
import com.example.service.JarvisNotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RoutineResult(
    val routineName: String,
    val summaryBengali: String,
    val success: Boolean = true,
    val details: Map<String, Any?> = emptyMap()
)

/**
 * Autonomous Routine Execution Engine for J.A.R.V.I.S. OS.
 * Runs Morning Briefing, Office Protocol, Night Review, and custom multi-step routines.
 */
class JarvisRoutineEngine(
    private val context: Context,
    private val dao: AssistantDao
) {
    suspend fun executeRoutine(routineName: String): RoutineResult = withContext(Dispatchers.IO) {
        val normalized = routineName.trim().lowercase(Locale.ROOT)
        when {
            normalized.contains("morning") || normalized.contains("সকাল") -> runMorningRoutine()
            normalized.contains("office") || normalized.contains("অফিস") -> runOfficeRoutine()
            normalized.contains("night") || normalized.contains("রাত") || normalized.contains("sleep") -> runNightRoutine()
            else -> runGenericRoutine(routineName)
        }
    }

    private suspend fun runMorningRoutine(): RoutineResult {
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", Locale("bn", "BD"))
        val currentDateTime = dateFormat.format(Date())

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val rawBattery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryText = if (rawBattery in 0..100) "$rawBattery%" else "UNKNOWN"

        val weatherInfo = try {
            val w = com.example.ai.WeatherService.fetchWeather("Dhaka")
            w.fullSummaryBengali
        } catch (e: Exception) {
            "আবহাওয়া তথ্য লোড করা যায়নি"
        }

        val tasks = try {
            dao.getAllAiTasksSync().filter { it.status == "PENDING" }
        } catch (e: Exception) {
            emptyList()
        }

        val taskCount = tasks.size
        val taskSummary = if (taskCount > 0) {
            "আজকের জন্য $taskCount টি শিডিউলড প্রোটোকল/টাস্ক নির্ধারিত আছে।"
        } else {
            "আজকের জন্য কোনো পেন্ডিং টাস্ক নেই, স্যার।"
        }

        val notifs = JarvisNotificationListenerService.getActiveNotificationsList()
        val notifCount = notifs.size
        val notifSummary = if (notifCount > 0) {
            "রাতে মোট $notifCount টি আনরিড নোটিফিকেশন জমা হয়েছে।"
        } else {
            "কোনো জরুরি মিসড নোটিফিকেশন নেই।"
        }

        val speech = buildString {
            append("শুভ সকাল, স্যার। ")
            append("আজ $currentDateTime। ")
            append("ডিভাইস ব্যাটারি $batteryText চার্জড। ")
            append("বর্তমান আবহাওয়া: $weatherInfo। ")
            append("$taskSummary ")
            append("$notifSummary ")
            append("আপনার দিনটি চমৎকার ও ফলপ্রসূ হোক।")
        }

        return RoutineResult(
            routineName = "Morning Briefing",
            summaryBengali = speech,
            details = mapOf(
                "battery" to batteryText,
                "pendingTasks" to taskCount,
                "notifications" to notifCount
            )
        )
    }

    private suspend fun runOfficeRoutine(): RoutineResult {
        val tasks = try {
            dao.getAllAiTasksSync().filter { it.status == "PENDING" }
        } catch (e: Exception) {
            emptyList()
        }

        val speech = if (tasks.isNotEmpty()) {
            val list = tasks.take(3).joinToString(", ") { it.title }
            "স্যার, অফিসের কাজের শিডিউল প্রস্তুত। আপনার পেন্ডিং টাস্কসমূহ: $list।"
        } else {
            "স্যার, অফিসের কাজের জন্য কোনো নতুন পেন্ডিং টাস্ক নেই। আপনি সম্পূর্ণ প্রস্তুত।"
        }

        return RoutineResult(
            routineName = "Office Routine",
            summaryBengali = speech,
            details = mapOf("pendingTasks" to tasks.size)
        )
    }

    private suspend fun runNightRoutine(): RoutineResult {
        val tasks = try {
            dao.getAllAiTasksSync().filter { it.status == "PENDING" }
        } catch (e: Exception) {
            emptyList()
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val rawBattery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryText = if (rawBattery in 0..100) "$rawBattery%" else "UNKNOWN"

        val speech = buildString {
            append("শুভ রাত্রি। ")
            append("দিনের সমস্ত সিস্টেম পর্যালোচনা সম্পন্ন। ")
            append("আগামীকালের জন্য ${tasks.size}টি টাস্ক নির্ধারিত রয়েছে। ")
            append("ডিভাইস ব্যাটারি $batteryText। অনুগ্রহ করে প্রয়োজনে ফোনটি চার্জে রাখুন। ")
            append("শান্তিময় বিশ্রামের প্রত্যাশায় রইলাম।")
        }

        return RoutineResult(
            routineName = "Night Routine",
            summaryBengali = speech,
            success = true,
            details = mapOf("battery" to batteryText, "tomorrowTasks" to tasks.size)
        )
    }

    private fun runGenericRoutine(name: String): RoutineResult {
        val clean = name.trim()
        return RoutineResult(
            routineName = clean,
            summaryBengali = "'$clean' নামে কোনো নির্ধারিত রুটিন পাওয়া যায়নি। উপলব্ধ রুটিন: Morning Briefing, Office Routine, Night Routine।",
            success = false
        )
    }
}
