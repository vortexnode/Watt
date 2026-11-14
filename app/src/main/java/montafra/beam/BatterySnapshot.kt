package montafra.beam

class BatterySnapshot(
    private val currentScalar: Double,
    private val invertCurrent: Boolean,
    val chargeStatus: ChargeStatus,
    val currentRaw: Long?,
    val isChargingRaw: Boolean,
    val pluggedRaw: Long?,
    val voltsRaw: Long?,
) {
    val amps: Double?
        get() {
            val sign = if (invertCurrent) 1.0 else -1.0
            return currentRaw?.toDouble()?.times(currentScalar)?.times(sign)?.div(1_000_000.0)
        }

    val volts: Double?
        get() = voltsRaw?.toDouble()?.div(1_000.0)

    val watts: Double?
        get() {
            val voltage = volts ?: return null
            return amps?.times(voltage)
        }

    val plugged: Boolean?
        get() = pluggedRaw?.let { it != 0L }

    val charging: Boolean
        get() = when (chargeStatus) {
            ChargeStatus.Charging, ChargeStatus.Full -> true
            ChargeStatus.Discharging, ChargeStatus.NotCharging -> false
            ChargeStatus.Unknown -> isChargingRaw || (amps?.let { it < 0.0 } == true)
        }
}
