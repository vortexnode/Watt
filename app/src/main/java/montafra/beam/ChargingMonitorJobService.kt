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
 * Keeps one persisted charging-constrained job registered with Android.
 *
 * JobScheduler is the supported fallback for detecting charging while the app process is
 * stopped. Its dispatch time is controlled by the system and is not guaranteed to be immediate.
 * When the UI is visible, MainActivity starts StatusService directly and listens dynamically
 * for cable changes instead of waiting for this job.
 */
class ChargingMonitorJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = getSharedPreferences(settingsName, MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            Log.i(TAG, "Charging job ignored: notifications are disabled")
            return false
        }

        if (!isPluggedIn()) {
            Log.i(TAG, "Charging job started without external power; requesting retry")
            jobFinished(params, true)
            return false
        }

        return try {
            startForegroundService(Intent(this, StatusService::class.java))
            Log.i(TAG, "Started StatusService from persisted charging job")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start StatusService from charging job", e)
            jobFinished(params, true)
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (!isPluggedIn()) {
            Log.i(TAG, "Charging constraint lost; stopping StatusService")
            stopService(Intent(this, StatusService::class.java))
        } else {
            Log.i(TAG, "Charging job stopped while still plugged; scheduler may retry")
        }
        return true
    }

    private fun isPluggedIn(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    companion object {
        private const val TAG = "ChargingMonitorJob"
        private const val JOB_ID = 0x57415454
    }
}

object ChargingMonitorScheduler {
    private const val JOB_ID = 0x57415454
    private const val TAG = "ChargingMonitorJob"

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!prefs.getBoolean("notificationEnabled", true)) {
            scheduler.cancel(JOB_ID)
            Log.i(TAG, "Charging monitor job cancelled: notifications are disabled")
            return
        }

        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, ChargingMonitorJobService::class.java),
        )
            .setRequiresCharging(true)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)

        // Prefer this user-visible charging task over ordinary background jobs once eligible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setPriority(JobInfo.PRIORITY_HIGH)
        }

        val result = scheduler.schedule(builder.build())
        Log.i(TAG, "Scheduled persisted charging monitor: result=$result")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.i(TAG, "Charging job pending reason=${scheduler.getPendingJobReason(JOB_ID)}")
        }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
    }
}
