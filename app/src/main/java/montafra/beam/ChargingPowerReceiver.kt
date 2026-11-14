package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts the short-lived charging session when a cable is connected.
 * The foreground notification is the Live Update itself; StatusService
 * removes it and stops when ACTION_POWER_DISCONNECTED arrives.
 */
class ChargingPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notificationEnabled", true)) return
        try {
            val service = Intent(context, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            Log.e("ChargingPowerReceiver", "Could not start charging session", e)
        }
    }
}
