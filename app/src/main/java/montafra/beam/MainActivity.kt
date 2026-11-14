package montafra.beam

import android.Manifest.permission
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import montafra.beam.ui.AlarmsSettingsScreen
import montafra.beam.ui.ColorPickerScreen
import montafra.beam.ui.LanguageSettingsScreen
import montafra.beam.ui.LocalSilentHaptics
import montafra.beam.ui.LocalSoundEnabled
import montafra.beam.ui.LocalTapHaptics
import montafra.beam.ui.MainScreen
import montafra.beam.ui.NoOpHapticFeedback
import montafra.beam.ui.NotificationSettingsScreen
import montafra.beam.ui.PredictiveNavHost
import montafra.beam.ui.SettingsScreen
import montafra.beam.ui.TapFeedback
import montafra.beam.ui.ThemeSettingsScreen
import montafra.beam.ui.WorkaroundsSettingsScreen
import montafra.beam.ui.rememberBeamNavController
import montafra.beam.ui.theme.BeamTheme
import montafra.beam.ui.theme.rememberThemePrefs
import rikka.shizuku.Shizuku

const val namespace = "montafra.beam"
const val batteryDataReq = "$namespace.battery-data-req"
const val batteryDataResp = "$namespace.battery-data-resp"
const val intervalMs = 1_000L
const val noteChannelId = "$namespace.live.v1"
const val noteId = 1
const val liveNoteId = 5
const val alarmChannelId = "$namespace.alarms.v1"
const val alarmLowNoteId = 2
const val alarmHighNoteId = 3
const val alarmTempNoteId = 4
// Shared by StatusService and AlarmsSettingsScreen so the service can never disagree with the
// value the settings screen displays.
const val alarmLowDefaultThreshold = 20
const val alarmHighDefaultThreshold = 85
const val alarmTempDefaultThreshold = 40
const val alarmRepeatDefaultMin = 15
const val settingsName = "settings"
const val settingsUpdateInd = "$namespace.settings-update-ind"
const val shizukuPermissionGranted = "$namespace.shizuku-permission-granted"
const val shizukuPermissionRequestCode = 7101

val LocalHapticsEnabled = staticCompositionLocalOf { true }

class MainActivity : ComponentActivity() {
    enum class Perm { Granted, Denied, NotAsked }

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
            // Shizuku is optional; BatteryManager continues to work without it.
        }
    }

    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuPermissionRequestCode && grantResult == PackageManager.PERMISSION_GRANTED) {
            sendBroadcast(Intent(shizukuPermissionGranted).setPackage(packageName))
        }
    }

    private fun registerShizukuListeners() {
        if (shizukuListenersRegistered) return
        shizukuListenersRegistered = true
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun getPerm(name: String): Perm {
        val settings = getSharedPreferences(settingsName, MODE_PRIVATE)
        return when {
            checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED -> Perm.Granted
            settings.getBoolean("${name}_ASKED", false) -> Perm.Denied
            else -> Perm.NotAsked
        }
    }

    private fun requestPerm(name: String) {
        getSharedPreferences(settingsName, MODE_PRIVATE)
            .edit().putBoolean("${name}_ASKED", true).apply()
        requestPermissions(arrayOf(name), 0)
    }

    private fun startStatusService() {
        // Keep a persisted system job for the case where the app process is not running.
        ChargingMonitorScheduler.schedule(this)
        startStatusServiceIfPlugged()
    }

    private fun isExternalPowerConnected(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun startStatusServiceIfPlugged() {
        if (!getSharedPreferences(settingsName, MODE_PRIVATE)
                .getBoolean("notificationEnabled", true) || !isExternalPowerConnected()) return

        try {
            val service = Intent(this, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
            else startService(service)
            Log.i("WattCharging", "Visible Activity requested immediate charging monitor start")
        } catch (e: Exception) {
            Log.e("WattCharging", "Could not start charging monitor from visible Activity", e)
        }
    }

    private fun stopChargingService() {
        stopService(Intent(this, StatusService::class.java))
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

    private fun syncNotificationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(settingsName, MODE_PRIVATE)
                .edit().putBoolean("notificationEnabled", false).apply()
            BeamNotificationTileService.requestRefresh(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.contains(permission.POST_NOTIFICATIONS)) {
            syncNotificationEnabled()
            startStatusService()
            if (checkSelfPermission(permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                registerShizukuListeners()
            }
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
        // The in-app language pick (API < 33 only). Must land before anything touches resources.
        appLanguageOverride(newBase)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The default SystemBarStyle.auto() writes a scrim into navigationBarColor on API 28 and
        // turns isNavigationBarContrastEnforced back on from API 29, overriding what
        // values-v29/themes.xml asks for. A non-auto style keeps both off, so the nav bar stays
        // fully transparent - gesture pill / 3-button icons sit straight on the content.
        // BeamTheme owns the bar icon appearance, so dark() here only holds until first composition.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (getPerm(permission.POST_NOTIFICATIONS) == Perm.NotAsked) {
            requestPerm(permission.POST_NOTIFICATIONS)
            // startStatusService() is called from onRequestPermissionsResult once the user responds
        } else {
            syncNotificationEnabled()
            startStatusService()
            if (checkSelfPermission(permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                registerShizukuListeners()
            }
        }

        setContent {
            val themePrefs by rememberThemePrefs()
            BeamTheme(themePrefs) {
                ChargingPowerScreen()
            }
        }
    }
}
