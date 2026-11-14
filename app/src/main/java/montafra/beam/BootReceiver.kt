package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Scheduling persisted charging monitor job after ${intent.action}")
                ChargingMonitorScheduler.schedule(context)
            }
        }
    }

    private companion object {
        const val TAG = "WattBootReceiver"
    }
}
