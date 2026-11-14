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
                Log.i(TAG, "Scheduling charging monitor after ${intent.action}")
                ChargingMonitorScheduler.schedule(context)
            }

            Intent.ACTION_POWER_CONNECTED -> {
                Log.i(TAG, "External power connected; requesting immediate monitor start")
                ChargingMonitorScheduler.startImmediately(context)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.i(TAG, "External power disconnected; cancelling pending start and stopping monitor")
                ChargingMonitorScheduler.cancelImmediate(context)
                context.stopService(Intent(context, StatusService::class.java))
            }
        }
    }

    private companion object {
        const val TAG = "WattBootReceiver"
    }
}
