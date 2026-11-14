package montafra.beam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import montafra.beam.ui.theme.BeamTheme
import rikka.shizuku.Shizuku

const val namespace = "montafra.beam"
const val batteryDataReq = "$namespace.battery-data-req"
const val batteryDataResp = "$namespace.battery-data-resp"
const val intervalMs = 1_000L
const val noteChannelId = "$namespace.live.v1"
const val liveNoteId = 5
const val settingsName = "settings"
const val shizukuPermissionGranted = "$namespace.shizuku-permission-granted"
const val shizukuPermissionRequestCode = 7101

class MainActivity : ComponentActivity() {
    private var shizukuListenersRegistered = false
    private var chargingReceiverRegistered = false

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> startStatusServiceIfPlugged()
                Intent.ACTION_POWER_DISCONNECTED -> stopChargingService()
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        try {
            if (!Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    sendBroadcast(Intent(shizukuPermissionGranted).setPackage(packageName))
                } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
                    Shizuku.requestPermission(shizukuPermissionRequestCode)
                }
            }
        } catch (_: Throwable) {
            // Shizuku is optional; the service falls back to BatteryManager.
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuPermissionRequestCode &&
                grantResult == PackageManager.PERMISSION_GRANTED
            ) sendBroadcast(Intent(shizukuPermissionGranted).setPackage(packageName))
        }

    private fun registerShizukuListeners() {
        if (shizukuListenersRegistered) return
        shizukuListenersRegistered = true
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun startStatusService() {
        ChargingMonitorScheduler.schedule(this)
        startStatusServiceIfPlugged()
    }

    private fun isExternalPowerConnected(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun startStatusServiceIfPlugged() {
        val enabled = getSharedPreferences(settingsName, MODE_PRIVATE)
            .getBoolean("notificationEnabled", true)
        if (!enabled || !isExternalPowerConnected()) return
        try {
            startForegroundService(Intent(this, StatusService::class.java))
            Log.i("WattCharging", "Visible Activity requested charging monitor start")
        } catch (e: Exception) {
            Log.e("WattCharging", "Could not start charging monitor from visible Activity", e)
        }
    }

    private fun stopChargingService() {
        stopService(Intent(this, StatusService::class.java))
    }

    private fun syncNotificationPermission() {
        val enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        getSharedPreferences(settingsName, MODE_PRIVATE)
            .edit().putBoolean("notificationEnabled", enabled).commit()
    }

    override fun onStart() {
        super.onStart()
        if (!chargingReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(chargingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(chargingReceiver, filter)
            }
            chargingReceiverRegistered = true
        }
        startStatusServiceIfPlugged()
    }

    override fun onStop() {
        if (chargingReceiverRegistered) {
            unregisterReceiver(chargingReceiver)
            chargingReceiverRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.contains(Manifest.permission.POST_NOTIFICATIONS)) {
            syncNotificationPermission()
            startStatusService()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) registerShizukuListeners()
        }
    }

    override fun onDestroy() {
        if (shizukuListenersRegistered) {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
            shizukuListenersRegistered = false
        }
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appLanguageOverride(newBase)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences(settingsName, MODE_PRIVATE)
            if (!prefs.getBoolean("notificationPermissionAsked", false)) {
                prefs.edit().putBoolean("notificationPermissionAsked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            } else {
                syncNotificationPermission()
            }
        } else {
            syncNotificationPermission()
            startStatusService()
            registerShizukuListeners()
        }

        setContent {
            BeamTheme {
                ChargingPowerScreen()
            }
        }
    }
}
