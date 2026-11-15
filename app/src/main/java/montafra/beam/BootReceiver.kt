package montafra.beam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // The old Quick Settings tile stored its own enabled/disabled state here.
                // After removing that tile, derive the flag only from notification permission.
                val notificationsAllowed =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
                    .edit().putBoolean("notificationEnabled", notificationsAllowed).commit()

                Log.i(TAG, "Scheduling charging monitor after ${intent.action}")
                ChargingMonitorScheduler.schedule(context)
            }
        }
    }

    private companion object {
        const val TAG = "WattBootReceiver"
    }
}
