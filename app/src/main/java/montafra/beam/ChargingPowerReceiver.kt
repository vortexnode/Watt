package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Owns the cable-edge lifecycle independently of MainActivity.
 *
 * ACTION_POWER_CONNECTED / DISCONNECTED are protected system broadcasts. The receiver
 * is manifest-declared so Android can wake the app process even when Watt is not running.
 */
class ChargingPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, StatusService::class.java)
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
                if (!prefs.getBoolean("notificationEnabled", true)) return
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(service)
                    } else {
                        context.startService(service)
                    }
                    Log.i(TAG, "Power connected: requested charging service start")
                } catch (e: Exception) {
                    Log.e(TAG, "Power connected: could not start charging service", e)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // stopService works even if the service is in its private :batteryStatus
                // process. onDestroy() stops polling, releases WakeLock and unbinds Shizuku.
                try {
                    context.stopService(service)
                    Log.i(TAG, "Power disconnected: requested charging service stop")
                } catch (e: Exception) {
                    Log.e(TAG, "Power disconnected: could not stop charging service", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ChargingPowerReceiver"
    }
}
