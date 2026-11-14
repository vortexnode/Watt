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
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vortexnode.chargingpower.IPowerSupplyReader
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.math.abs

/**
 * Foreground service that exists only while a charger is connected. It publishes the live
 * charging-power notification and tears everything down on unplug.
 */
class StatusService : Service() {
    private lateinit var battery: Battery
    private lateinit var snapshot: BatterySnapshot
    private lateinit var noteIntent: PendingIntent
    private lateinit var noteMgr: NotificationManager
    private lateinit var msgReceiver: MsgReceiver

    private var notificationEnabled = true
    private var pollIntervalMs: Long = intervalMs
    private var initialized = false
    private var chargingWakeLock: PowerManager.WakeLock? = null
    private var powerSupplyReader: IPowerSupplyReader? = null
    private var shizukuServiceBound = false
    private var shizukuListenersRegistered = false
    private var chargingSessionActive = false
    private var isForeground = false

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, PowerSupplyUserService::class.java.name)
        ).daemon(true).tag("charging-power-reader").processNameSuffix("power-reader").version(2)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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

    private val task = PeriodicTask({ update() }, { pollIntervalMs })
    private val binder = Binder()

    private fun debug(msg: String) {
        Log.d(this::class.java.name, msg)
    }

    private inner class MsgReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                shizukuPermissionGranted -> if (chargingSessionActive) bindShizukuReader()
                settingsUpdateInd -> {
                    loadSettings()
                    update()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    // The sticky battery broadcast gives us the unplug edge without a sensor
                    // read. End immediately and do not sample any metrics afterward.
                    if (chargingSessionActive &&
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
                    ) {
                        endChargingSession()
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> update()
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Do not take another sensor snapshot on the unplug event. End the session
                    // immediately, release the wake lock and stop the Shizuku daemon reader.
                    endChargingSession()
                }
            }
        }
    }

    private fun bindShizukuReader() {
        if (!chargingSessionActive) return
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            if (shizukuServiceBound) return
            shizukuServiceBound = true
            Shizuku.bindUserService(shizukuArgs, shizukuConnection)
        } catch (e: Throwable) {
            shizukuServiceBound = false
            Log.w(this.javaClass.name, "Shizuku power reader unavailable; using BatteryManager", e)
        }
    }

    private fun readBatterySnapshot(): BatterySnapshot {
        val apiSnapshot = battery.snapshot()
        // BatteryManager is sampled once to detect the unplug edge. Do not access sysfs or
        // Shizuku at all when the cable is absent.
        if (!(apiSnapshot.plugged ?: apiSnapshot.charging)) return apiSnapshot
        val metrics = try {
            powerSupplyReader?.readBatteryMetrics()
        } catch (e: Throwable) {
            Log.w(this::class.java.name, "Shizuku battery read failed; using BatteryManager", e)
            null
        }
        val parts = metrics?.split('|')
        val sysfsCurrent = parts?.getOrNull(0)?.toLongOrNull()
        // Linux power_supply voltage_now is in microvolts; BatterySnapshot expects millivolts.
        val sysfsVoltage = parts?.getOrNull(1)?.toLongOrNull()?.div(1_000L)
        val sysfsIsUseful = sysfsCurrent != null && sysfsVoltage != null &&
            sysfsVoltage > 0L && (sysfsCurrent != 0L || apiSnapshot.currentRaw == null || apiSnapshot.currentRaw == 0L)
        if (sysfsIsUseful) return battery.snapshot(sysfsCurrent, sysfsVoltage, currentScalarOverride = 1.0)
        return apiSnapshot
    }

    private fun loadSettings() {
        val settings = getSharedPreferences(settingsName, MODE_MULTI_PROCESS)
        notificationEnabled = settings.getBoolean("notificationEnabled", true)
        if (!notificationEnabled) stopForeground(STOP_FOREGROUND_REMOVE)
        battery.currentScalar = settings.getFloat("currentScalar", 1000f).toDouble()
        battery.invertCurrent = settings.getBoolean("invertCurrent", true)
        pollIntervalMs = settings.getLong("pollIntervalMs", intervalMs)
    }

    private fun init() {
        if (initialized) return
        initialized = true

        battery = Battery(applicationContext)
        // Match the confirmed Samsung Galaxy S22 workaround from Beam:
        // this device reports CURRENT_NOW in mA instead of the expected uA.
        // A 1000x scalar restores the correct unit; invertCurrent fixes the
        // charging-current sign used by Beam's charging heuristics.
        battery.currentScalar = 1000.0
        battery.invertCurrent = true
        // Do not start Shizuku or its daemon reader during service construction. It is
        // attached only after onStartCommand has confirmed an active charging session.
        snapshot = battery.snapshot()

        noteMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        noteMgr.createNotificationChannel(
            NotificationChannel(
                noteChannelId,
                "Charging Power",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live charging power shown while a charger is connected"
            }
        )

        noteIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        msgReceiver = MsgReceiver()
        registerReceiver(
            msgReceiver,
            IntentFilter().apply {
                addAction(shizukuPermissionGranted)
                addAction(settingsUpdateInd)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreate() {
        super.onCreate()
        init()
        loadSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debug("onStartCommand()")
        super.onStartCommand(intent, flags, startId)
        loadSettings()
        // JobScheduler may re-enter its charging job after a system runtime limit. If the
        // foreground charging session is already active, keep the existing poller intact.
        if (chargingSessionActive && isForeground && notificationEnabled) {
            return START_NOT_STICKY
        }
        // First perform only the minimum BatteryManager state check. No Shizuku binding,
        // sysfs read, wake lock or recurring poll is allowed when unplugged.
        snapshot = battery.snapshot()
        val plugged = snapshot.plugged ?: snapshot.charging
        if (!notificationEnabled || !plugged) {
            task.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        chargingSessionActive = true

        // Promote immediately. Shizuku is an optional measurement fallback and must never
        // prevent the core Live Notification from appearing when its binder is slow,
        // unavailable, or throws during process initialization.
        try {
            startForeground(liveNoteId, buildNotification())
            isForeground = true
        } catch (e: Exception) {
            Log.e(this::class.java.name, "Could not publish charging foreground notification", e)
            chargingSessionActive = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Start BatteryManager polling independently of Shizuku.
        // task.start() performs the first update immediately.
        task.start()

        // Shizuku is strictly best-effort. Failure here leaves the notification and
        // BatteryManager measurements running normally.
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
            Log.w(this::class.java.name, "Shizuku unavailable; keeping BatteryManager Live Update active", e)
        }
        return START_NOT_STICKY
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
                Log.w(this::class.java.name, "Could not stop Shizuku reader", e)
            }
        }
        shizukuServiceBound = false
    }

    private fun endChargingSession() {
        chargingSessionActive = false
        task.stop()
        syncChargingWakeLock(false)
        noteMgr.cancel(liveNoteId)
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        unbindShizukuReader()
        stopSelf()
    }

    override fun onDestroy() {
        debug("onDestroy()")
        chargingSessionActive = false
        task.stop()
        chargingWakeLock?.let { if (it.isHeld) it.release() }
        if (initialized) unregisterReceiver(msgReceiver)
        unbindShizukuReader()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun syncChargingWakeLock(plugged: Boolean) {
        val lock = chargingWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$namespace:ChargingPowerPoll"
            ).apply {
                setReferenceCounted(false)
                chargingWakeLock = this
            }
        }
        if (plugged && !lock.isHeld) {
            // Keep the CPU awake while cabled so the one-second Live Update continues in Doze.
            lock.acquire()
        } else if (!plugged && lock.isHeld) {
            lock.release()
        }
    }

    private fun buildNotification(): Notification {
        val powerText = snapshot.watts?.let { fmt(abs(it)) } ?: "-"
        return Notification.Builder(this, noteChannelId)
            .setContentTitle("$powerText W")
            .setContentText("Charging")
            .setStyle(Notification.BigTextStyle().bigText("Charging"))
            .setSmallIcon(R.drawable.ico_lightning)
            .setContentIntent(noteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Android 16 Live Updates promotion request. The typed API is 36.1+;
            // using the documented extra keeps this source compatible with compileSdk 36.
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
            })
            .build()
    }

    private fun update() {
        debug("update()")

        snapshot = readBatterySnapshot()
        val plugged = snapshot.plugged ?: snapshot.charging
        if (!notificationEnabled || !plugged) {
            endChargingSession()
            return
        }
        syncChargingWakeLock(true)
        val live = buildNotification()
        if (!isForeground) {
            try {
                startForeground(liveNoteId, live)
                isForeground = true
            } catch (e: Exception) {
                Log.e(this::class.java.name, "Could not start charging foreground session", e)
                stopSelf()
            }
        } else {
            noteMgr.notify(liveNoteId, live)
        }
    }
}
