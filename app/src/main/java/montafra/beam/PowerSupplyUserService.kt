package montafra.beam

import androidx.annotation.Keep
import com.vortexnode.chargingpower.IPowerSupplyReader
import java.io.File

@Keep
class PowerSupplyUserService : IPowerSupplyReader.Stub() {
    override fun readBatteryMetrics(): String {
        val supplies = File("/sys/class/power_supply").listFiles() ?: return ""
        val battery = supplies.firstOrNull { supply ->
            supply.name.equals("battery", ignoreCase = true) ||
                read(supply.resolve("type"))?.equals("Battery", ignoreCase = true) == true
        } ?: return ""

        val current = read(battery.resolve("current_now"))?.toLongOrNull() ?: return ""
        val voltage = read(battery.resolve("voltage_now"))?.toLongOrNull() ?: return ""
        return "$current|$voltage"
    }

    private fun read(file: File): String? = try {
        file.readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    override fun destroy() {
        System.exit(0)
    }
}
