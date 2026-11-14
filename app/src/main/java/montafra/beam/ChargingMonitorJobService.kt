package montafra.beam

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * The persistent charging-constrained job is a recovery path. Its execution time is controlled
 * by JobScheduler, so it must not be the only path that reacts to a newly connected cable.
 *
 * BootReceiver schedules a separate unconstrained one-shot job on the power-connected edge.
 * That job gets an immediate deadline and starts the foreground service through JobScheduler,
 * avoiding a direct background foreground-service launch from BroadcastReceiver.
 */
class ChargingMonitorJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = getSharedPreferences(settingsName, MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            jobFinished(params, false)
            return false
        }

        if (params.jobId == IMMEDIATE_JOB_ID) {
            val plugged = isPluggedIn()
            if (!plugged) {
                Log.i(TAG, "Immediate start job ran after power was removed")
                jobFinished(params, false)
                return false
            }
            return startStatusService(params, keepJobRunning = false)
        }

        if (!isPluggedIn()) {
            Log.i(TAG, "Charging constraint callback without external power; retrying")
            jobFinished(params, true)
            return false
        }

        return startStatusService(params, keepJobRunning = true)
    }

    private fun startStatusService(
        params: JobParameters,
        keepJobRunning: Boolean,
    ): Boolean {
        return try {
            val service = Intent(this, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
            Log.i(TAG, "Started StatusService from ${if (keepJobRunning) "charging" else "immediate"} job")
            if (keepJobRunning) {
                // Keep the persistent job active for the charging session. JobScheduler calls
                // onStopJob as soon as the charging constraint is lost.
                true
            } else {
                // The one-shot trigger has done its work; the persistent job remains as fallback.
                jobFinished(params, false)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start StatusService from job", e)
            jobFinished(params, true)
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (params.jobId == IMMEDIATE_JOB_ID) return false

        if (!isPluggedIn()) {
            Log.i(TAG, "Charging constraint lost; stopping StatusService")
            stopService(Intent(this, StatusService::class.java))
        } else {
            Log.i(TAG, "Charging job stopped while still plugged; allowing scheduler retry")
        }
        // Keep the persisted job scheduled for the next charging session. If JobScheduler
        // stopped it for a runtime limit while still plugged, it will also be retried.
        return true
    }

    private fun isPluggedIn(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    companion object {
        private const val TAG = "ChargingMonitorJob"
        private const val JOB_ID = 0x57415454
        private const val IMMEDIATE_JOB_ID = 0x57415455
    }
}

object ChargingMonitorScheduler {
    private const val JOB_ID = 0x57415454
    private const val IMMEDIATE_JOB_ID = 0x57415455

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            cancel(context)
            return
        }

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, ChargingMonitorJobService::class.java),
        )
            .setRequiresCharging(true)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = scheduler.schedule(job)
        Log.i("ChargingMonitorJob", "Scheduled charging monitor fallback: $result")

        // Covers the case where the app is opened or restored while the cable is already
        // connected. Do not wait for JobScheduler to reevaluate the charging constraint.
        if (isPluggedIn(context)) {
            startImmediately(context)
        } else {
            cancelImmediate(context)
        }
    }

    /**
     * Queue an unconstrained one-shot job for a power-connected broadcast. The zero deadline
     * asks JobScheduler to run it as soon as possible; using a job (rather than starting an FGS
     * directly from the receiver) respects Android's background FGS launch restrictions.
     */
    fun startImmediately(context: Context) {
        val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true) || !isPluggedIn(context)) {
            cancelImmediate(context)
            return
        }

        val job = JobInfo.Builder(
            IMMEDIATE_JOB_ID,
            ComponentName(context, ChargingMonitorJobService::class.java),
        )
            .setOverrideDeadline(0L)
            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
            .schedule(job)
        Log.i("ChargingMonitorJob", "Scheduled immediate charging start: $result")
    }

    fun cancelImmediate(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(IMMEDIATE_JOB_ID)
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
        scheduler.cancel(IMMEDIATE_JOB_ID)
    }

    private fun isPluggedIn(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}
