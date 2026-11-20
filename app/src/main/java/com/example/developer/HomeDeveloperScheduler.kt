package com.example.developer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Native Android Constraint-Aware Background Self-Improvement Scheduler.
 * Enforces Wi-Fi only, Battery Not Low, Charging, Idle, and Storage constraints via JobScheduler.
 */
object HomeDeveloperScheduler {

    const val HOME_DEV_JOB_ID = 9407

    fun scheduleBackgroundDevelopment(
        context: Context,
        settings: HomeDeveloperSettingsState = HomeDeveloperSettingsRepository(context).settings.value
    ): Boolean {
        if (!settings.selfDevelopmentEnabled) {
            cancelScheduledWork(context)
            return false
        }

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
        val serviceComponent = ComponentName(context, HomeDevBackgroundJobService::class.java)

        val builder = JobInfo.Builder(HOME_DEV_JOB_ID, serviceComponent)
            .setRequiredNetworkType(
                if (settings.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            )
            .setRequiresCharging(settings.requireCharging)
            .setRequiresDeviceIdle(settings.requireDeviceIdle)
            .setPersisted(true)
            .setPeriodic(6L * 60L * 60L * 1000L) // Safe 6-hour interval when enabled

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setRequiresBatteryNotLow(settings.batteryNotLow)
            builder.setRequiresStorageNotLow(settings.requireStorageNotLow)
        }

        return scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }

    fun cancelScheduledWork(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        scheduler?.cancel(HOME_DEV_JOB_ID)
    }

    fun getScheduledJobInfo(context: Context): JobInfo? {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return null
        return scheduler.allPendingJobs.firstOrNull { it.id == HOME_DEV_JOB_ID }
    }
}

class HomeDevBackgroundJobService : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appCtx = applicationContext
        val settings = HomeDeveloperSettingsRepository(appCtx).settings.value
        if (!settings.selfDevelopmentEnabled) {
            jobFinished(params, false)
            return false
        }

        runningJob = scope.launch {
            try {
                HomeDeveloperOrchestrator.runSelfRepairAudit(appCtx)
                if (settings.autonomyLevel == AutonomyLevel.L4_SCHEDULED_BACKGROUND) {
                    HomeDeveloperOrchestrator.startTask(
                        context = appCtx,
                        goal = "Scheduled Safe Self-Audit (${settings.developmentPriority})",
                        category = "SELF_REPAIR",
                        priority = "LOW",
                        sourceType = "GAP_DETECTOR",
                        autonomyOverride = AutonomyLevel.L4_SCHEDULED_BACKGROUND
                    )
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        runningJob?.cancel()
        scope.launch {
            HomeDeveloperOrchestrator.pauseOrStopCleanly(
                reason = "ANDROID_JOB_CONSTRAINTS_LOST",
                context = applicationContext
            )
        }
        return true
    }
}
