package montafra.beam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

enum class ChargeStatus {
    Charging, Discharging, Full, NotCharging, Unknown;

    companion object {
        fun fromRaw(status: Int?): ChargeStatus = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> Charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> Discharging
            BatteryManager.BATTERY_STATUS_FULL -> Full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> NotCharging
            else -> Unknown
        }
    }
}

class Battery(private val context: Context) {
    private val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    var currentScalar = 1.0
    var invertCurrent = false

    private fun property(id: Int): Long? =
        manager.getLongProperty(id).takeUnless { it == Long.MIN_VALUE }

    private fun batteryExtra(intent: Intent?, key: String): Long? {
        if (intent?.hasExtra(key) != true) return null
        return intent.getIntExtra(key, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
            ?.toLong()
    }

    fun snapshot(
        currentOverride: Long? = null,
        voltageOverride: Long? = null,
        currentScalarOverride: Double? = null,
    ): BatterySnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return BatterySnapshot(
            currentRaw = currentOverride ?: property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentScalar = currentScalarOverride ?: currentScalar,
            invertCurrent = invertCurrent,
            chargeStatus = ChargeStatus.fromRaw(
                batteryExtra(batteryIntent, BatteryManager.EXTRA_STATUS)?.toInt()
            ),
            isChargingRaw = manager.isCharging,
            pluggedRaw = batteryExtra(batteryIntent, BatteryManager.EXTRA_PLUGGED),
            voltsRaw = voltageOverride ?: batteryExtra(batteryIntent, BatteryManager.EXTRA_VOLTAGE),
        )
    }
}
