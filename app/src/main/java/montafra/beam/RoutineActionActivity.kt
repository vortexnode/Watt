package montafra.beam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * No-display entry point used by Samsung Modes and Routines app actions.
 *
 * Samsung can invoke the app shortcut without opening Watt's normal UI. This Activity dispatches
 * the selected operation and immediately finishes.
 */
class RoutineActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            ACTION_START -> startChargingMonitor()
            ACTION_STOP -> stopChargingMonitor()
            else -> Log.w(TAG, "Routine action ignored: missing or unknown action")
        }

        finish()
    }

    private fun startChargingMonitor() {
        val prefs = getSharedPreferences(settingsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            Log.i(TAG, "Routine start ignored: charging notification is disabled")
            return
        }

        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) {
            Log.i(TAG, "Routine start ignored: external power is not connected")
            return
        }

        try {
            val serviceIntent = Intent(this, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "Charging monitor requested by Samsung Modes and Routines")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start charging monitor from Samsung Routine", e)
        }
    }

    private fun stopChargingMonitor() {
        stopService(Intent(this, StatusService::class.java))
        Log.i(TAG, "Charging monitor stop requested by Samsung Modes and Routines")
    }

    companion object {
        const val ACTION_START = "montafra.beam.action.START_CHARGING_MONITOR"
        const val ACTION_STOP = "montafra.beam.action.STOP_CHARGING_MONITOR"
        private const val TAG = "WattRoutineAction"
    }
}
