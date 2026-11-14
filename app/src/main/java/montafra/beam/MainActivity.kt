package montafra.beam

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rikka.shizuku.Shizuku

const val namespace = "montafra.beam"
const val intervalMs = 1_000L
const val noteChannelId = "$namespace.live.v1"
const val liveNoteId = 5
const val settingsName = "settings"
const val settingsUpdateInd = "$namespace.settings-update-ind"
const val shizukuPermissionGranted = "$namespace.shizuku-permission-granted"
const val shizukuPermissionRequestCode = 7101

class MainActivity : ComponentActivity() {
    enum class Perm { Granted, Denied, NotAsked }

    private var shizukuListenersRegistered = false

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
        // The persisted charging-constrained job is the only automatic trigger. Android
        // does not deliver ACTION_POWER_CONNECTED to manifest receivers for modern targets.
        ChargingMonitorScheduler.schedule(this)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            WattTheme {
                ChargingPowerScreen()
            }
        }
    }
}
