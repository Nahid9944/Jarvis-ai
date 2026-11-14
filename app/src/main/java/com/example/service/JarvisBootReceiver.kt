package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.local.AssistantDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules pending future AiTaskEntity alarms automatically after device reboot,
 * app update (MY_PACKAGE_REPLACED), or timezone changes.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reschedulePendingAlarms(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling JARVIS alarms after $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    suspend fun reschedulePendingAlarms(context: Context): Int {
        val now = System.currentTimeMillis()
        val dao = AssistantDatabase.getDatabase(context).assistantDao()
        val pendingTasks = dao.getPendingFutureTasksSync(now)
        var scheduledCount = 0
        for (task in pendingTasks) {
            JarvisAlarmReceiver.scheduleAlarm(
                context = context,
                taskId = task.id,
                title = task.title,
                description = task.description.ifBlank { "JARVIS নির্ধারিত টাস্ক রিমাইন্ডার" },
                triggerTimeMillis = task.scheduledTime
            )
            scheduledCount++
        }
        Log.i(TAG, "Rescheduled $scheduledCount pending alarms after boot/update.")
        return scheduledCount
    }
}
