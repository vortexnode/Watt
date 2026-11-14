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
 * Android does not allow ACTION_POWER_CONNECTED to be received by manifest receivers
 * for apps targeting modern SDKs. A persisted JobScheduler job with a charging
 * constraint is the supported system-managed trigger: it starts only while charging
 * and is stopped by the system when external power is removed.
 */
class ChargingMonitorJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = getSharedPreferences(settingsName, MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            jobFinished(params, false)
            return false
        }

        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) {
            Log.i(TAG, "Charging constraint callback without external power; retrying")
            jobFinished(params, true)
            return false
        }

        return try {
            val service = Intent(this, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
            Log.i(TAG, "Charging job started StatusService")
            // Keep this lightweight job active for the charging session. JobScheduler
            // calls onStopJob as soon as the charging constraint is lost.
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start StatusService from charging job", e)
            jobFinished(params, true)
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) {
            Log.i(TAG, "Charging constraint lost; stopping StatusService")
            stopService(Intent(this, StatusService::class.java))
        } else {
            Log.i(TAG, "Charging job stopped while still plugged; allowing scheduler retry")
        }
        // Keep the persisted job scheduled for the next charging session. If JobScheduler
        // stopped it for a runtime limit while still plugged, it will also be retried.
        return true
    }

    companion object {
        private const val TAG = "ChargingMonitorJob"
    }
}

object ChargingMonitorScheduler {
    private const val JOB_ID = 0x57415454

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
        Log.i("ChargingMonitorJob", "Scheduled charging monitor job: $result")
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
    }
}
