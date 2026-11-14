package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private fun debug(msg: String) {
        Log.d(this::class.java.name, msg)
    }
    private fun error(msg: String) {
        Log.e(this::class.java.name, msg)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        debug("onReceive()")

        if (context == null) {
            error("context is null")
            return
        }

        if (intent == null) {
            error("intent is null")
            return
        }

        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            error("received non-boot action '${intent.action}'")
            return
        }

        val prefs = context.getSharedPreferences(settingsName, Context.MODE_MULTI_PROCESS)
        if (!prefs.getBoolean("notificationEnabled", true)) {
            debug("notifications disabled; skipping service start at boot")
            return
        }

        // Do not start the charging monitor at boot unless a charger is actually connected.
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) {
            debug("charger disconnected; skipping status service start at boot")
            return
        }

        debug("starting status service for an active charging session...")
        try {
            context.startForegroundService(Intent(context, StatusService::class.java))
        } catch (e: Exception) {
            error("Failed to start StatusService: ${e.message}")
        }
    }
}