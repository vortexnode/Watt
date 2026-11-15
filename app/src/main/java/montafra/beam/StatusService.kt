package montafra.beam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vortexnode.chargingpower.IPowerSupplyReader
import kotlin.math.abs
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class StatusService : Service() {
    private lateinit var battery: Battery
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationIntent: PendingIntent
    private lateinit var snapshot: BatterySnapshot

    private var notificationEnabled = true
    private var chargingSessionActive = false
    private var foreground = false
    private var chargingWakeLock: PowerManager.WakeLock? = null
    private var powerSupplyReader: IPowerSupplyReader? = null
    private var shizukuServiceBound = false
    private var shizukuListenersRegistered = false

    private val task = PeriodicTask({ update() }, { intervalMs })

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, PowerSupplyUserService::class.java.name)
        ).daemon(true).tag("watt-power-reader").processNameSuffix("power-reader").version(3)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            powerSupplyReader = service?.let { IPowerSupplyReader.Stub.asInterface(it) }
            shizukuServiceBound = powerSupplyReader != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            powerSupplyReader = null
            shizukuServiceBound = false
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (chargingSessionActive) bindShizukuReader()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        powerSupplyReader = null
        shizukuServiceBound = false
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                batteryDataReq -> publishData()
                shizukuPermissionGranted -> if (chargingSessionActive) bindShizukuReader()
                Intent.ACTION_POWER_DISCONNECTED -> endChargingSession()
                Intent.ACTION_BATTERY_CHANGED -> {
                    if (chargingSessionActive &&
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
                    ) endChargingSession()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        battery = Battery(applicationContext).apply {
            // BatteryManager's Galaxy S22 current reading needs this unit correction.
            currentScalar = 1000.0
            invertCurrent = true
        }
        snapshot = battery.snapshot()
        notificationEnabled = getSharedPreferences(settingsName, MODE_MULTI_PROCESS)
            .getBoolean("notificationEnabled", true)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(noteChannelId, "Charging Power", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Live charging-power monitor while a charger is connected" }
        )
        notificationIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val filter = IntentFilter().apply {
            addAction(batteryDataReq)
            addAction(shizukuPermissionGranted)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(messageReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (chargingSessionActive && foreground && notificationEnabled) return START_NOT_STICKY

        notificationEnabled = getSharedPreferences(settingsName, MODE_MULTI_PROCESS)
            .getBoolean("notificationEnabled", true)
        snapshot = battery.snapshot()
        val plugged = snapshot.plugged ?: snapshot.charging
        if (!notificationEnabled || !plugged) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        chargingSessionActive = true
        try {
            startForeground(liveNoteId, buildNotification())
            foreground = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not publish charging foreground notification", e)
            chargingSessionActive = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Shizuku is optional and must never delay foreground promotion.
        try {
            ShizukuProvider.enableMultiProcessSupport(false)
            ShizukuProvider.requestBinderForNonProviderProcess(this)
            if (!shizukuListenersRegistered) {
                Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
                Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
                shizukuListenersRegistered = true
            }
            bindShizukuReader()
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku unavailable; using BatteryManager", e)
        }

        task.start()
        return START_NOT_STICKY
    }

    private fun bindShizukuReader() {
        if (!chargingSessionActive || shizukuServiceBound) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            shizukuServiceBound = true
            Shizuku.bindUserService(shizukuArgs, shizukuConnection)
        } catch (e: Throwable) {
            shizukuServiceBound = false
            Log.w(TAG, "Could not bind Shizuku power reader; using BatteryManager", e)
        }
    }

    /**
     * Prefer kernel power-supply readings when Shizuku can read valid current and voltage.
     * BatteryManager remains the automatic fallback when Shizuku or sysfs is unavailable.
     */
    private fun readBatterySnapshot(): BatterySnapshot {
        val apiSnapshot = battery.snapshot()
        if (!(apiSnapshot.plugged ?: apiSnapshot.charging)) return apiSnapshot

        val metrics = try {
            powerSupplyReader?.readBatteryMetrics()
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku battery read failed; using BatteryManager", e)
            null
        }
        val parts = metrics?.split('|')
        val sysfsCurrent = parts?.getOrNull(0)?.toLongOrNull()
        // Linux power_supply voltage_now is in microvolts; the snapshot stores millivolts.
        val sysfsVoltage = parts?.getOrNull(1)?.toLongOrNull()?.div(1_000L)

        return if (sysfsCurrent != null && sysfsCurrent != 0L &&
            sysfsVoltage != null && sysfsVoltage > 0L
        ) {
            battery.snapshot(
                currentOverride = sysfsCurrent,
                voltageOverride = sysfsVoltage,
                currentScalarOverride = 1.0,
            )
        } else {
            apiSnapshot
        }
    }

    private fun update() {
        if (!chargingSessionActive) return
        snapshot = readBatterySnapshot()
        val plugged = snapshot.plugged ?: snapshot.charging
        if (!notificationEnabled || !plugged) {
            endChargingSession()
            return
        }

        syncChargingWakeLock()
        val notification = buildNotification()
        if (foreground) {
            notificationManager.notify(liveNoteId, notification)
        } else {
            try {
                startForeground(liveNoteId, notification)
                foreground = true
            } catch (e: Exception) {
                Log.e(TAG, "Could not restart charging foreground session", e)
                endChargingSession()
                return
            }
        }
        publishData()
    }

    private fun publishData() {
        if (!::snapshot.isInitialized) return
        val plugged = snapshot.plugged ?: snapshot.charging
        val watts = snapshot.watts?.let { abs(it) }?.takeIf { it.isFinite() }
        sendBroadcast(
            Intent(batteryDataResp)
                .setPackage(packageName)
                .putExtra("plugged", plugged)
                .putExtra("powerWatts", watts ?: Double.NaN)
        )
    }

    private fun buildNotification(): Notification {
        val powerText = snapshot.watts?.let { fmt(abs(it)) } ?: "—"
        return Notification.Builder(this, noteChannelId)
            .setContentTitle("$powerText W")
            .setContentText("Charging")
            .setStyle(Notification.BigTextStyle().bigText("Charging"))
            .setSmallIcon(R.drawable.ico_lightning)
            .setContentIntent(notificationIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)

                // Samsung One UI uses this extra to render an opaque Now Bar chip.
                // Keep the foreground text styling under Samsung's control.
                putInt("android.ongoingActivityNoti.style", 1)
                putInt("android.ongoingActivityNoti.chipBgColor", 0xFF888888.toInt())
            })
            .build()
    }

    private fun syncChargingWakeLock() {
        val lock = chargingWakeLock ?: run {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$namespace:ChargingPowerPoll")
                .apply {
                    setReferenceCounted(false)
                    chargingWakeLock = this
                }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun unbindShizukuReader() {
        powerSupplyReader = null
        if (shizukuListenersRegistered) {
            try {
                Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
                Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            } catch (_: Throwable) {
            }
            shizukuListenersRegistered = false
        }
        if (shizukuServiceBound) {
            try {
                Shizuku.unbindUserService(shizukuArgs, shizukuConnection, true)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not stop Shizuku power reader", e)
            }
        }
        shizukuServiceBound = false
    }

    private fun endChargingSession() {
        if (!chargingSessionActive && !foreground) return
        chargingSessionActive = false
        task.stop()
        chargingWakeLock?.let { if (it.isHeld) it.release() }
        notificationManager.cancel(liveNoteId)
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        unbindShizukuReader()
        stopSelf()
    }

    override fun onDestroy() {
        chargingSessionActive = false
        task.stop()
        chargingWakeLock?.let { if (it.isHeld) it.release() }
        runCatching { unregisterReceiver(messageReceiver) }
        unbindShizukuReader()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private companion object {
        const val TAG = "WattStatusService"
    }
}
